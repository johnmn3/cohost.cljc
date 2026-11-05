;; Eve-in-cohost load probe — the program run INSIDE each booted host.
;; It is fed to the host as one -e form (see dev/eve_probe.clj). Every case
;; prints exactly one line:  PROBE|<case>|<OK value | FAIL class :: message>
;; The runner classifies FAILs: a shim "not available on this host" message
;; names the unsupported entry (the threading-surface gap); anything else is
;; quoted verbatim.
(do
  (defn probe [nm f]
    (try (println (str "PROBE|" nm "|OK " (pr-str (f))))
         (catch Throwable t
           (println (str "PROBE|" nm "|FAIL " (.getName (class t)) " :: " (ex-message t))))))
  ;; --- the twelve libraries, each required on its own ---
  (doseq [lib '[clojure.walk clojure.set clojure.string clojure.zip
                clojure.data clojure.edn clojure.template clojure.test
                clojure.math clojure.stacktrace clojure.repl
                clojure.spec.alpha]]
    (probe (str "load-" lib) (fn [] (require lib) :loaded)))
  ;; --- the threading surface, as library code and its users call it ---
  (probe "future-basic"        (fn [] (deref (future (+ 1 2)))))
  (probe "future-capture-local" (fn [] (let [x 41] (deref (future (inc x))))))
  (probe "future-capture-atom" (fn [] (let [a (atom 0)] (deref (future (swap! a inc))) @a)))
  (probe "future-binding"      (fn [] (binding [*print-length* 3] (deref (future *print-length*)))))
  (probe "promise"             (fn [] (let [p (promise)] (deliver p 7) (deref p))))
  (probe "delay"               (fn [] (deref (delay (+ 1 1)))))
  (probe "agent"               (fn [] (let [ag (agent 0)] (send ag inc) (await ag) (deref ag))))
  (probe "pmap"                (fn [] (doall (pmap inc [1 2 3]))))
  ;; --- a library exercising its own internals on the surface's edge ---
  (probe "spec-conform" (fn [] (eval '(clojure.spec.alpha/conform even? 2))))
  (probe "test-stacktrace" (fn [] (eval '(count (.getStackTrace (Thread/currentThread))))))
  (println "PROBE|done|OK"))
