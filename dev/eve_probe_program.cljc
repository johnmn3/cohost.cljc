(do
  (defn probe [nm f]
    (try (println (str "PROBE|" nm "|OK " (pr-str (f))))
         (catch Throwable t
           (println (str "PROBE|" nm "|FAIL " (.getName (class t)) " :: " (ex-message t))))))
  (probe "future-basic"        (fn [] (deref (future (+ 1 2)))))
  (probe "future-capture-local" (fn [] (let [x 41] (deref (future (inc x))))))
  (probe "future-capture-atom" (fn [] (let [a (atom 0)] (deref (future (swap! a inc))) @a)))
  (probe "future-binding"      (fn [] (binding [*print-length* 3] (deref (future *print-length*)))))
  (probe "promise"             (fn [] (let [p (promise)] (deliver p 7) (deref p))))
  (probe "delay"               (fn [] (deref (delay (+ 1 1)))))
  (probe "agent"               (fn [] (let [ag (agent 0)] (send ag inc) (await ag) (deref ag))))
  (probe "pmap"                (fn [] (doall (pmap inc [1 2 3]))))
  (probe "spec-conform" (fn [] (eval '(clojure.spec.alpha/conform even? 2))))
  (probe "test-stacktrace" (fn [] (eval '(count (.getStackTrace (Thread/currentThread))))))
  (println "PROBE|done|OK")
  (try (shutdown-agents) (catch Throwable _))
  (try (System/exit 0) (catch Throwable _)))
