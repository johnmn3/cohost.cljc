;; Leaf micro-probe: GoroutineID stability + promise doorbell + AgentSubmit handle
(defn try* [label f]
  (try (println label "=>" (pr-str (f)))
       (catch Throwable e (println label "THREW" (ex-message e)))))

(try* "GoroutineID main x2 (must be equal)"
      #(let [a (github.com:glojurelang:glojure:pkg:lang.GoroutineID)
             b (github.com:glojurelang:glojure:pkg:lang.GoroutineID)]
         [a b (= a b)]))
(try* "GoroutineID in spawned goroutine (must differ from main)"
      #(let [main (github.com:glojurelang:glojure:pkg:lang.GoroutineID)
             fut (github.com:glojurelang:glojure:pkg:lang.AgentSubmit
                  (fn [] (github.com:glojurelang:glojure:pkg:lang.GoroutineID)))]
         [main (deref fut 5000 ::timeout)]))
(try* "AgentSubmit handle realized?"
      #(let [fut (github.com:glojurelang:glojure:pkg:lang.AgentSubmit (fn [] 99))]
         [(deref fut 5000 ::timeout) (realized? fut)]))
(try* "promise blocks until deliver, sticky"
      #(let [p (promise)
             t0 (System/currentTimeMillis)
             f (future (Thread/sleep 120) (deliver p :rung))
             v @p]
         [v (>= (- (System/currentTimeMillis) t0) 100) @p]))
(try* "promise deref timeout returns timeout-val"
      #(let [p (promise)] (deref p 60 ::timeout)))
(try* "promise second deliver no-op"
      #(let [p (promise)] (deliver p 1) (deliver p 2) @p))
(try* "System/nanoTime" #(System/nanoTime))
(try* "IllegalStateException resolves" #(try (throw (IllegalStateException. "x")) (catch IllegalStateException e :caught)))
(try* "sleep in one goroutine does not block main"
      #(let [t0 (System/currentTimeMillis)
             f (future (Thread/sleep 300) :done)
             main-elapsed (do (Thread/sleep 10) (- (System/currentTimeMillis) t0))]
         [main-elapsed (deref f 5000 ::timeout)]))
(println "DONE")
