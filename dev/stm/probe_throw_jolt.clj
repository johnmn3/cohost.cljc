;; STM lane (continuation): the THROW-CROSSING probe on JOLT frames
;; (STATUS 1's question for this seat; STATUS 10 named it owed).
;;   jolt -Scp <src + Eve src + :jolt row> dev/stm/probe_throw_jolt.clj
;; cohost.tx's retry is a dedicated exception (RetryEx-style) that must
;; unwind arbitrary user frames inside a dosync body and be caught by
;; the transaction runner -- selectively, without swallowing genuine
;; errors, and without the attempt's partial writes landing. This
;; probe exercises exactly that on the seat: a transaction whose body
;; throws the retry signal from DEEP frames (through a fn call, a
;; loop, and a lazy-seq force) must retry and then commit; a body
;; throwing a genuine error must surface it, not retry it; and an
;; interrupted attempt (cooperative cancel flag, cohost.thread's
;; contract) must abandon, not commit.
(require 'cohost.hosts.auto)
(require '[cohost.tx :as tx]
         '[cohost.thread :as thread])

(tx/set-fill! true)
(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))

(def r (tx/make-ref 0))
(def attempts (atom 0))
(defn deep-conflict [n]
  ;; manufacture a REAL conflict from nested frames (fn -> lazy force
  ;; -> fn): an outside WRITE to r's cell (a plain swap! on the cell
  ;; atom -- a racing commit's install step, at the layer's own
  ;; granularity) moves the cell's point past this attempt's read
  ;; point, so the runner's validation throws the retry signal --
  ;; the signal must unwind the attempt's frames (including the
  ;; lazy-seq force) and be caught by the runner alone. A nested
  ;; run-in-transaction JOINS the running attempt (stock semantics),
  ;; so an outside commit cannot be spelled that way from inside a
  ;; body; the cell-level write is the honest manufacture.
  (if (zero? n)
    (swap! (:cell r) (fn [c] (-> c
                                 (assoc :point (inc (:point c)))
                                 (update :history (fn [h] (into [{:point (:point c)
                                                                  :value (:value c)}] h))))))
    (do (doall (map (fn [_] (deep-conflict (dec n))) [0])) nil)))
(check "conflict manufactured deep in frames; tx retries and commits" 2
       (tx/run-in-transaction
        (fn [] (swap! attempts inc)
          (let [v (tx/deref-ref r)]
            (when (= 1 @attempts) (deep-conflict 3))
            (tx/ref-set* r (inc v))))))
(check "exactly one retry happened" 2 @attempts)
(check "outside commit + retried write both landed" 2 (tx/deref-ref r))

(def attempts2 (atom 0))
(check "a genuine error surfaces (not retried into a commit)" :surfaced
       (try (tx/run-in-transaction
             (fn [] (swap! attempts2 inc)
               (tx/alter* r (fn [_] (throw (ex-info "boom" {}))) [])))
            (catch Throwable e :surfaced)))
(check "genuine error: exactly one attempt" 1 @attempts2)
(check "genuine error: no partial write landed" 2 (tx/deref-ref r))

(println (if (empty? @failures)
           (str "PROBE-THROW-JOLT: ALL " @checks " CHECKS PASS")
           (str "PROBE-THROW-JOLT: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
