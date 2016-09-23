(ns zmqchans.test-core
  (:use
   [zmqchans.core]
   [zmqchans.utils]
   [clojure.test])
  (:require
   [clojure.core.async :refer
    [go go-loop thread close! >!! >! <!! <! chan timeout alts!!
     offer! poll! promise-chan]]
   [clojure.set :refer [map-invert]]
   )
  )

(defn get-pid []
  (-> (java.lang.management.ManagementFactory/getRuntimeMXBean) .getName))

(defn run-with-timeout
  "Run a function on another thread with maximum execution duration.

  f             - function to execute, takes return channel as an argument
  timeout-ms    - milliseconds to wait before timeout
  stop-delay-ms - milliseconds to wait between .interrupt and .stop calls
                  (default: 1000)

  Optional result of the function will be put to `:res` chan and
  execution status is always returned to `:status` chan.

  Will try nicely just `.interrupt` the thread first. This can be caught
  from the other thread to facilitate a controlled way to exit.
  If `.interrupt` doesn't kill the thread (after `stop-delay-ms`),
  `.stop` will be called. .interrupt can be caught from the thread to allow a
  controlled way to exit. Sometimes threads just refuse to die. Not sure
  if there are any good solutions for this.

  Make sure to design the function in a way that stopping won't cause
  undefined behavior.

  If function `f` spawns new threads, those are not managed by this function
  and may continue running indefinitely if their life cycle is not managed
  properly in `f`."
  ([f timeout-ms] (run-with-timeout f timeout-ms 1000))
  ([f timeout-ms stop-delay-ms]
   (let [res-ch    (chan 1)
         status-ch (chan 1)
         name      (str (gensym "run-with-timeout-"))
         inf-ex    (atom nil)
         launcher  (bound-fn []
                     (let [t (Thread.
                              (bound-fn []
                                (when-let [res (try (f)
                                               (catch Exception e
                                                 (reset! inf-ex e)))]
                                  (>!! res-ch res)))
                              (str name "-inferior"))]
                       (.start t)
                       (.join t timeout-ms)
                       (if (.isAlive t)
                         (do
                           ;; Interrupt if thread is waiting/sleeping,
                           ;; will throw an exception in the target thread.
                           ;; Should kill deadlocked threads but cannot kill
                           ;; a thread that is simply executing code.
                           (.interrupt t)
                           (Thread/sleep stop-delay-ms)
                           (if (.isAlive t)
                             (do
                               ;; Stops a thread forcefully.
                               ;; Risky if this thread is
                               ;; sharing data with other threads.
                               (.stop t)
                               (>!! status-ch :stop))
                             (>!! status-ch :interrupt)))
                         (if @inf-ex
                           (>!! status-ch @inf-ex)
                           (>!! status-ch :normal)))))
         t         (Thread. launcher name)]
     (.start t)
     {:res res-ch :status status-ch})))

(defmacro deftest-with-timeout
  "Defines a test function with maximum running time.

  Uses `run-with-timeout` to run contents of `body` with timeout `timeout-ms`.
  Creates an `is` statement at the end to check that function exited normally,
  which means no timeout or exceptions.

  Will try to `.interrupt` or eventually `.stop` the thread if timeout has
  reached but sometimes threads just refuse to die. Not sure if there are any
  good solutions for this.

  Unhandled exceptions will be delegated back to the calling thread for the
  test runner to process them normally.

  If `body` creates new threads, those are not managed by this macro. They
  will keep on running indefinitely if their life cycle is not properly
  managed."
  [name timeout-ms & body]
  (when *load-tests*
    (let [f (gensym "f")]
      `(let [~f (fn []
                  (let [res# (run-with-timeout (fn [] ~@body) ~timeout-ms)
                        res# (<!! (res# :status))]
                    (if (instance? java.lang.Exception res#)
                      (throw res#)
                      (is (= :normal res#)))
                    )
                  )]
         (def ~(vary-meta name assoc :test f)
           (fn [] (test-var (var ~name))))))))

(defn random-socket [ctx]
  ;; :router and :rep trigger a bug in libzmq
  ;; when connecting without binding first.
  ;; See: https://github.com/zeromq/libzmq/issues/2117
  (socket (rand-nth (remove #{:rep :router} (keys socket-types)))
          :context ctx
          (rand-nth [:bind :connect])
          (str (gensym "ipc://test-random-socket-"))))

(deftest-with-timeout startup-shutdown 1000
  (let [n       100 ; too high n and inproc runs out of fds
        ctx     (context)
        sockets (vec (take n (repeatedly #(random-socket ctx))))]
    (is (context-alive ctx))
    ((ctx :shutdown))
    (is (not (context-alive ctx)))))

(deftest-with-timeout deadlock-seeker 2000
  (let [ctx  (context)
        addr (str (gensym "inproc://test-deadlock-seeker-"))
        pub  (socket :pub :context ctx :bind addr)
        n-sub 10
        subs (for [i (range n-sub)]
               (socket :sub
                       :context ctx
                       :connect addr
                       :subscribe (str (mod i 10))))
        ex-prom (promise-chan)
        tstamps (atom {})
        pub-term (thread
                   (loop [i 0]
                     (if (poll! ex-prom)
                       (do
                         (doseq [sub subs] (close! (sub :in)))
                         (close! (pub :in)))
                       (do
                         (>!! (pub :in) (str (mod i 10)))
                         (recur (inc i))))))
        sub-term (thread
                   (let [chans (concat [ex-prom] (map #(% :out) subs))]
                     (loop [[v c] (alts!! chans)]
                       (when (not= c ex-prom)
                         (let [i (Long/parseLong (String. v))]
                           (swap! tstamps #(assoc % i (java.util.Date.))))
                         (recur (alts!! chans))))))
        ext-term (thread
                   (loop [i 0]
                     (when (not (poll! ex-prom))
                       (let [sock (socket :sub
                                          :context ctx
                                          :connect addr
                                          :subscribe (str (mod i 10)))]
                         (close! (:in sock)))
                       (recur (inc i)))))]
    (Thread/sleep (* 1000 1))
    (>!! ex-prom :exit)
    (<!! pub-term)
    (<!! sub-term)
    (<!! ext-term)
    ((ctx :shutdown))
    (is (= (count @tstamps) (min 10 n-sub)))
    (let [now (java.util.Date.)
          deltas (map #(- (.getTime now) (.getTime %)) (vals @tstamps))]
      ;;(println "max delta: " (apply max deltas))
      (is (< (apply max deltas) 200)))))

(deftest-with-timeout ping-pong 1000
  (let [ctx   (context)
        addr  (str (gensym "inproc://test-ping-pong-"))
        rep   (socket :rep :context ctx :bind addr)
        req   (socket :req :context ctx :connect addr)
        n     1000
        parse (fn [b] (Long/parseLong (String. b)))
        rep-l (go-loop [i (parse (<!! (rep :out)))]
                ;;(println "rep" i)
                (when (<= i n)
                  (>!! (rep :in) (str (inc i))))
                (if (< i n)
                  (recur (parse (<!! (rep :out))))
                  (do (close! (rep :in)) i)))
        req-l (go-loop [i -1]
                ;;(println "req" i)
                (when (<= i n)
                  (>!! (req :in) (str (inc i))))
                (if (< i n)
                  (recur (parse (<!! (req :out))))
                  (do (close! (req :in)) i)))
        req-r (<!! req-l)
        rep-r (<!! rep-l)
        ]
    (is (= (min rep-r req-r) n))
    (is (= (Math/abs (- rep-r req-r)) 1))
    ((ctx :shutdown))))

(defmacro timed-run-ms [& forms]
  `(let [start-time# (System/nanoTime)
         res# (do ~@forms)
         end-time# (System/nanoTime)]
     [res# (double (/ (- end-time# start-time#) 1e6))]))

;; If one context is processing huge messages, it's more efficient to transfer
;; smaller messages in another context.
(deftest-with-timeout big-packets 10000
  (letfn
      [(run-test! [ctx1 ctx2 data-mibs n]
         (let [data-b (byte-array (* 1024 1024 data-mibs))
               len-b  n
               addr-b (str (gensym "inproc://test-big-file-big-"))
               addr-s (str (gensym "inproc://test-big-file-small-"))
               pull-b (socket :pull :context ctx1 :bind addr-b)
               push-b (socket :push :context ctx1 :connect addr-b)
               pull-s (socket :pull :context ctx2 :bind addr-s)
               push-s (socket :push :context ctx2 :connect addr-s)
               exprom (promise-chan)
               res-b  (thread
                        (loop [i 1]
                          ;;(println "BIG start" i)
                          (>!! (push-b :in) data-b)
                          (<!! (pull-b :out))
                          ;;(println "BIG end" i)
                          (if (and (< i len-b) (not (poll! exprom)))
                            (recur (inc i))
                            (do (>!! exprom :exit) i))))
               res-s  (thread
                        (loop [i 1]
                          (>!! (push-s :in) "")
                          (<!! (pull-s :out))
                          ;;(println i)
                          (if (not (poll! exprom))
                            (recur (inc i))
                            i)))]
           (let [res (try [(<!! res-b) (<!! res-s)]
                          (catch java.lang.InterruptedException e
                            (>!! exprom :exit)))]
             ((ctx1 :shutdown))
             ((ctx2 :shutdown))
             res)))]
    (let [;; single context
          ctx (context)
          [[b1 s1] ms1]    (timed-run-ms (run-test! ctx ctx 100 5))
          perf1 (/ s1 ms1)
          ;; two contexts
          [[b2 s2] ms2]    (timed-run-ms (run-test! (context) (context) 100 5))
          perf2 (/ s2 ms2)]
      ;;(println perf1 perf2)
      (is (> perf2 perf1)))))

;; ;; If one context is processing huge messages, it's more efficient to transfer
;; ;; smaller messages in another context.
;; (deftest-with-timeout big-packets 100000000
;;   (letfn
;;       [(run-test! [ctx1 ctx2 data-mibs n]
;;          (let [data-b (byte-array (* 1024 1024 data-mibs))
;;                len-b  n
;;                addr-b (str (gensym "inproc://test-big-file-big-"))
;;                addr-s (str (gensym "inproc://test-big-file-small-"))
;;                pull-b (socket :pull :context ctx1 :bind addr-b)
;;                push-b (socket :push :context ctx1 :connect addr-b)
;;                pull-s (socket :pull :context ctx2 :bind addr-s)
;;                push-s (socket :push :context ctx2 :connect addr-s)
;;                exprom (promise-chan)
;;                res-b  (thread
;;                         (loop [i 1]
;;                           ;;(println "BIG start" i)
;;                           (>!! (push-b :in) data-b)
;;                           (<!! (pull-b :out))
;;                           ;;(println "BIG end" i)
;;                           (if (and (< i len-b) (not (poll! exprom)))
;;                             (recur (inc i))
;;                             (do (>!! exprom :exit) i))))
;;                res-s  (thread
;;                         (loop [i 1]
;;                           (>!! (push-s :in) "")
;;                           (<!! (pull-s :out))
;;                           ;;(println i)
;;                           (if (not (poll! exprom))
;;                             (recur (inc i))
;;                             i)))]
;;            (let [res (try [(<!! res-b) (<!! res-s)]
;;                           (catch java.lang.InterruptedException e
;;                             (>!! exprom :exit)))]
;;              ((ctx1 :shutdown))
;;              ((ctx2 :shutdown))
;;              res)))]
;;     (loop [i 0]
;;       (let [start-time (java.util.Date.)
;;             ctx     (context)
;;             ;; single context
;;             [b1 s1] (run-test! ctx ctx 10 1)
;;             _ (println (float (/ s1 (- (.getTime (java.util.Date.))
;;                                        (.getTime start-time)))))
;;             ;; two contexts
;;             start-time (java.util.Date.)
;;             [b2 s2] (run-test! (context) (context) 10 1)]
;;         (is (> s2 s1))
;;         (println i s1 s2 (float (/ s2 (- (.getTime (java.util.Date.))
;;                                          (.getTime start-time)))) "\n")
;;         (recur (inc i))
;;         ))))



;; (deftest-with-timeout context-creator 100000000
;;   (println (get-pid))
;;   (loop [i 0]
;;     (let [ctx (context)
;;           ;;sock (random-socket ctx)
;;           ]
;;       (init-context! ctx)
;;       ((ctx :shutdown))
;;       ;;(.destroy (ctx :zcontext))
;;       ;;(Thread/sleep 10)
;;       (printf "\r%d" i)
;;       (flush)
;;       (recur (inc i))
;;       )))


;; (deftest-with-timeout context-creator 100000000
;;   (println (get-pid))
;;   (loop [i 0]
;;     (let [zctx (org.zeromq.ZContext.)
;;           [addr-b addr-c] (first (rand-tcp-addr 1))
;;           push (thread
                 
;;                  (doto (.createSocket zctx (socket-types :push))
;;                          (.connect addr-c)))
;;           pull (thread
;;                  (Thread/sleep 10)
;;                  (doto (.createSocket zctx (socket-types :pull))
;;                          (.bind addr-b)))
;;           c (chan 1)
;;           t (java.lang.Thread. (fn []
;;                                  (printf "\rT: %d" (<!! c))
;;                                  (flush)))]
;;       (<!! push)
;;       (<!! pull)
;;       ;;(Thread/sleep 10)
;;       (.destroy zctx)
;;       (.setDaemon t true)
;;       (.start t)
;;       (>!! c i)
;;       ;;(close! c)
;;       ;;(Thread/sleep 100)
;;       ;;(printf "\r%d" i)
;;       ;;(flush)
;;       (recur (inc i))
;;       )))













;; Crashes on zmq 4.1.4 with both ipc / inproc / tcp
;; :rep / :req pair seems to trigger this crash...
;; Can't repro in C for some reason, maybe bug in jzmq?
;; Error on ipc:
;; SIGFPE (0x8) at pc=0x00007fc11fd5af1a, pid=24327, tid=140467386873600
;; zmq::lb_t::sendpipe(zmq::msg_t*, zmq::pipe_t**)+0x2d2
;; (deftest-with-timeout ping-pong 1000000
;;   (while true
;;     (let [ctx   (context)
;;           addr  (str (gensym "ipc://test-pingpong"))
;;           rep   (socket :rep :context ctx :bind addr)
;;           ;;_ (Thread/sleep 200)
;;           req   (socket :req :context ctx :connect addr)
;;           ]
;;       (>!! (req :in) "ping?")
;;       (is (= (String. (<!! (rep :out))) "ping?"))
;;       (Thread/sleep 100)
;;       ((ctx :shutdown))
;;       )))

;; (deftest-with-timeout ping-pong 1000000
;;   (while true
;;     (let [ctx   (context)
;;           [b-addr c-addr] (rand-tcp-addr)
;;           rep   (socket :pair :context ctx :bind b-addr)
;;           ;;_ (Thread/sleep 200)
;;           req   (socket :pair :context ctx :connect c-addr)
;;           ]
;;       (>!! (req :in) "ping?")
;;       (is (= (String. (<!! (rep :out))) "ping?"))
;;       ;;(Thread/sleep 100)
;;       ((ctx :shutdown))
;;       )))

;; Deadlocks on zmq 4.1.4 when using inproc.
;; Getting the first message recognized by the poller seems
;; problematic.
;; (deftest-with-timeout ping-pong 1000000
;;   (while true
;;     (let [ctx   (context)
;;           addr  (str (gensym "inproc://test-pingpong"))
;;           rep   (socket :pull :context ctx :bind addr)
;;           ;;_ (Thread/sleep 200)
;;           req   (socket :push :context ctx :connect addr)
;;           ]
;;       (>!! (req :in) "ping?")
;;       (<!! (rep :out))
;;       ((ctx :shutdown))
;;       )))





;; TODO: test ideas
;; - tcp transport tests
;; - transducers
;; - disconnects and reconnects (visiting req test)
