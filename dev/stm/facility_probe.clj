;; Facility probe: what concurrency surface does glojure (glj-eve build) expose?
(defn try* [label f]
  (try (println label "=>" (pr-str (f)))
       (catch Throwable e (println label "THREW" (.getName (class e)) "-" (ex-message e)))))

(try* "resolve clojure.core/go" #(resolve 'clojure.core/go))
(try* "resolve go (unqualified)" #(resolve 'go))
(try* "resolve future" #(resolve 'future))
(try* "resolve clojure.core/chan" #(resolve 'clojure.core/chan))
(try* "Thread/sleep 1" #(do (Thread/sleep 1) :slept))
(try* "Thread/currentThread" #(Thread/currentThread))
(try* "Thread. construct" #(Thread. (fn [] 1)))
(try* "future deref" #(deref (future (+ 40 2)) 5000 ::timeout))
(try* "pmap" #(doall (pmap inc [1 2 3])))
(try* "agent send+deref" #(let [a (agent 0)] (send a inc) (await-for 5000 a) @a))
(try* "promise deliver from future" #(let [p (promise)] (future (deliver p 7)) (deref p 5000 ::timeout)))
(try* "glojure go ns" #(do (require 'glojure.go) :loaded))
(println "DONE")
