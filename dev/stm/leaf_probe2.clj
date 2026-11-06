;; Leaf micro-probe 2: eveglj:host.GoroutineID
(defn try* [label f]
  (try (println label "=>" (pr-str (f)))
       (catch Throwable e (println label "THREW" (ex-message e)))))

(try* "goid main stable"
      #(let [a (eveglj:host.GoroutineID) b (eveglj:host.GoroutineID)] [a b (= a b)]))
(try* "goid distinct across spawned goroutines"
      #(let [m (eveglj:host.GoroutineID)
             f1 (github.com:glojurelang:glojure:pkg:lang.AgentSubmit (fn [] (eveglj:host.GoroutineID)))
             f2 (github.com:glojurelang:glojure:pkg:lang.AgentSubmit (fn [] (eveglj:host.GoroutineID)))]
         [m (deref f1 5000 :to) (deref f2 5000 :to)]))
(try* "goid stable inside one goroutine across sleeps"
      #(let [f (github.com:glojurelang:glojure:pkg:lang.AgentSubmit
                (fn [] (let [a (eveglj:host.GoroutineID)] (Thread/sleep 30) [a (eveglj:host.GoroutineID)])))]
         (deref f 5000 :to)))
(try* "doorbell: promise timed wait not realized, then signal"
      #(let [p (promise)
             r1 (do (deref p 50 :to) (realized? p))
             _ (future (Thread/sleep 80) (deliver p true))
             r2 (do (deref p 5000 :to) (realized? p))]
         [r1 r2]))
(try* "channel make/recv"
      #(let [ch (go/make (go/chan-of go/int) 1)
             f (future (go/recv ch))]
         (go/send ch 41)
         (deref f 5000 :to)))
(println "DONE")
