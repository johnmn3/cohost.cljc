;; Eve-in-cohost unit 1: booted-context verification. Boots the stock core
;; on the JVM seat with policy/thread-names treated as host-lacked, so
;; cohost.boot fills future/agent/pmap and friends from Eve's thread
;; layer; then runs stock-shaped programs in the booted user namespace.
;;   clojure -Sdeps '{:paths ["src"]}' -M eve_cohost_check.clj
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.policy 'cohost.adapter)
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)

(def report (boot/boot! {:host-lacks cohost.policy/thread-names}))
(boot/print-report report)
(boot/make-user-ns!)

(def checks (atom []))
(defn check [label src expected]
  (let [[ok v] (host/try-all (fn [] [true (boot/eval-str src {:session? false})])
                             (fn [e] [false (str "ERR " (host/error-message e))]))
        pass (and ok (= v expected))]
    (swap! checks conj pass)
    (println (if pass "PASS" "FAIL") label "=>" (pr-str v) (when-not pass (str " (expected " (pr-str expected) ")")))))

(check "future deref"
       "@(future (+ 1 2))" 3)
(check "future? and future-done?"
       "(let [f (future 1)] @f [(future? f) (future-done? f)])" [true true])
(check "binding conveyance into future"
       "(do (def ^:dynamic *xv* 1) (binding [*xv* 42] @(future *xv*)))" 42)
(check "future error rethrown at deref"
       "(try @(future (/ 1 0)) (catch ArithmeticException e :caught))" :caught)
(check "future-cancel on a blocked future"
       "(let [p (promise) f (future @p)] [(future-cancel f) (future-cancelled? f)])" [true true])
(check "pmap"
       "(vec (pmap (fn [x] (* x x)) (range 10)))" [0 1 4 9 16 25 36 49 64 81])
(check "pvalues"
       "(vec (pvalues (+ 1 1) (* 2 3)))" [2 6])
(check "pcalls"
       "(vec (pcalls (fn [] 1) (fn [] 2)))" [1 2])
(check "promise deliver via future"
       "(do (def pr1 (promise)) (future (deliver pr1 7)) @pr1)" 7)
(check "promise deref timeout"
       "(deref (promise) 10 :none)" :none)
(check "agent send + await"
       "(do (def a1 (agent 0)) (send a1 inc) (send a1 (fn [s] (+ s 10))) (await a1) @a1)" 11)
(check "agent *agent* inside an action"
       "(do (def a2 (agent nil)) (send a2 (fn [s] *agent*)) (await a2) (= @a2 a2))" true)
(check "agent send-off + await-for"
       "(do (def a3 (agent [])) (send-off a3 conj :x) [(await-for 5000 a3) @a3])" [true [:x]])
(check "agent :continue error recorded"
       "(do (def a4 (agent 1)) (send a4 (fn [s] (throw (Exception. \"bad\")))) (send a4 inc) (await a4) [@a4 (count (agent-errors a4))])"
       [2 1])
(check "agent send-via a shim executor"
       "(do (def a5 (agent 0)) (send-via (java.util.concurrent.Executors/newSingleThreadExecutor) a5 inc) (await a5) @a5)" 1)
(check "CountDownLatch through the shim"
       "(let [l (java.util.concurrent.CountDownLatch. 2)] (future (.countDown l)) (future (.countDown l)) [(.await l) (.getCount l)])"
       [true 0])
(check "Thread through the shim"
       "(let [a (atom nil) t (java.lang.Thread. (fn [] (reset! a 5)))] (.start t) (.join t 5000) [@a (.isAlive t)])"
       [5 false])
(check "Thread/currentThread is stable"
       "(= (java.lang.Thread/currentThread) (java.lang.Thread/currentThread))" true)
(check "Executors + Future through the shim"
       "(let [e (java.util.concurrent.Executors/newFixedThreadPool 2) f (.submit e (fn [] 99))] (let [v [(.get f) (.isDone f)]] (.shutdown e) v))"
       [99 true])
(check "realized? on layer futures"
       "(let [f (future 1)] @f (realized? f))" true)
(check "shutdown-agents runs"
       "(do (shutdown-agents) :ok)" :ok)

(println "CHECKS:" (count (filter identity @checks)) "/" (count @checks) "passed")
(System/exit (if (every? identity @checks) 0 1))
