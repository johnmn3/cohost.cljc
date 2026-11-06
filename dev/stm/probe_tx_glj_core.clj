;; STM lane (continuation): probe_tx's NON-THREAD sections on GLOJURE,
;; verbatim from probe_tx.clj up to the snapshot section's first spawn
;; (the thread sections are the seat gap named in STATUS 16). If this
;; file's check count drifts from probe_tx.clj's pre-thread prefix,
;; the drift is visible: it prints the same PASS lines.
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(require '[cohost.tx :as tx]
         '[cohost.thread :as thread]
         '[cohost.adapter :as host])
(tx/set-fill! true)
(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))
(defn check-true [label actual] (check label true (boolean actual)))
(def r1 (tx/make-ref 41))
(check-true "ref-value?" (tx/ref-value? r1))
(check "deref outside tx" 41 (tx/deref-ref r1))
(check "dosync alter returns new value" 42
       (tx/run-in-transaction (fn [] (tx/alter* r1 inc []))))
(check "committed" 42 (tx/deref-ref r1))
(check "alter outside tx throws" :threw
       (try (tx/alter* r1 inc []) :no-throw (catch Throwable e :threw)))
(def r2 (tx/make-ref 100))
(check "abort rolls back both refs" [42 100]
       (do (try (tx/run-in-transaction
                 (fn [] (tx/ref-set* r1 0) (tx/ref-set* r2 0)
                   (throw (Exception. "boom"))))
                (catch Exception e :caught))
           [(tx/deref-ref r1) (tx/deref-ref r2)]))
(println (str "PROBE-TX-GLJ-CORE: " @checks " checks, " (count @failures) " failures"))
(System/exit (if (empty? @failures) 0 1))
