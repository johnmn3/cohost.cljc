;; STM lane (continuation): probe_tx_reclaim's SECTIONS 1-3 on a seat
;; (the JVM run's sections; section 4 probes host GC reachability and
;; is JVM-only by construction). Written for jolt; seat-agnostic.
;;   jolt -Scp <cp> dev/stm/probe_tx_reclaim_sections.clj
(require 'cohost.hosts.auto)
(require '[cohost.tx :as tx])

(tx/set-fill! true)
(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))
(defn cell-of [r] @(:cell r))
(defn hist-values [r] (mapv :value (:history (cell-of r))))

(def r (tx/make-ref :v0 {:min-history 0 :max-history 3}))
(doseq [i (range 1 10)]
  (tx/run-in-transaction (fn [] (tx/ref-set* r (keyword (str "v" i))))))
(check "cell keeps at most max-history versions" 3 (count (:history (cell-of r))))
(check "retained versions are the newest" [:v8 :v7 :v6] (hist-values r))
(check "current value is the newest" :v9 (:value (cell-of r)))

(def sentinel (Object.))
(def r2 (tx/make-ref :s0 {:min-history 0 :max-history 2}))
(tx/run-in-transaction (fn [] (tx/ref-set* r2 sentinel)))
(doseq [i (range 5)]
  (tx/run-in-transaction (fn [] (tx/ref-set* r2 (keyword (str "t" i))))))
(check "a trimmed version leaves the history" false
       (boolean (some (fn [e] (identical? sentinel (:value e))) (:history (cell-of r2)))))
(check "a trimmed version is not the current value" false
       (identical? sentinel (:value (cell-of r2))))

(def live (tx/make-ref 0 {:min-history 2 :max-history 3}))
(def rp-holder (atom nil))
(def seen (atom nil))
(def reader
  (Thread. ^Runnable (fn [] (tx/run-in-transaction
                             (fn [] (reset! rp-holder (tx/deref-ref live))
                               (Thread/sleep 150)
                               (reset! seen (tx/deref-ref live)))))))
(.start reader)
(Thread/sleep 50)
(doseq [_ (range 3)] (tx/run-in-transaction (fn [] (tx/alter* live inc []))))
(.join reader)
(check "a live read point still reads its snapshot after trims" [0 0] [(deref rp-holder) (deref seen)])
(check "the live transaction's snapshot commits on fresh values" 4
       (do (tx/run-in-transaction (fn [] (tx/alter* live inc []))) (tx/deref-ref live)))

(println (if (empty? @failures)
           (str "PROBE-TX-RECLAIM-SECTIONS: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX-RECLAIM-SECTIONS: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
