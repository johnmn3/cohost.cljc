;; STM retry-bound lane (2026-11-06): the bounded fault for
;; IMPOSSIBLE progress, per the operator's disposition of the STM
;; lane's STATUS 14 item (contract + bounded fault, stock scoping;
;; re-basing rejected). Run on the JVM seat:
;;   clojure -Sdeps '{:paths ["src"]}' -M dev/stm/probe_tx_retry_bound.clj
;;
;; STATUS 14's finding: when a ref's cell stands beyond the layer's
;; commit-point clock over history that does not reach an attempt's
;; read point, value-at answers ::too-short on EVERY retry and the
;; pre-fix runner re-ran the attempt identically forever (observed
;; firsthand by the STM lane on jolt AND on the JVM seat: "the
;; runner never returns"). That manufacture needs an out-of-band
;; writer -- no legal sequence of layer commits produces it -- which
;; is exactly the single-writer contract's violation. This probe
;; manufactures the same shape directly (the cell poisoned to a
;; point beyond any clock this process will reach, history emptied)
;; and checks the ruled behavior: the transaction FAULTS with an
;; IllegalStateException in bounded time instead of spinning; the
;; poisoned cell is left exactly as found (no silent re-basing);
;; and honest contention -- two refs transferred between four
;; threads, the transfer shape of probe_tx -- still completes with
;; unbounded contention retries (the bound is scoped to impossible
;; progress, not to contention).

(require 'cohost.hosts.auto)
(require '[cohost.tx :as tx]
         '[cohost.thread :as thread]
         '[cohost.atom :as atom-layer])

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

;; -- contention control: honest transactions, the transfer shape ----
;; (run FIRST, in an unpoisoned world)
(def from (tx/make-ref 10000))
(def to (tx/make-ref 10000))
(def attempts (atom 0))
(def movers
  (mapv (fn [_] (thread/spawn
                 (fn [] (dotimes [_ 500]
                          (tx/run-in-transaction
                           (fn [] (swap! attempts inc)
                             (tx/alter* from - [1])
                             (tx/alter* to + [1])))))))
        (range 4)))
(doseq [w movers] (thread/join-thread w))
(check "contention control: transfer invariant holds" 20000
       (+ (tx/deref-ref from) (tx/deref-ref to)))
(check "contention control: all transfers landed" [8000 12000]
       [(tx/deref-ref from) (tx/deref-ref to)])
(println "INFO contention control attempts (retries included):" @attempts
         "for 2000 transactions -- contention retries unbounded, none faulted")

;; -- the fault: a cell moved out-of-band, basis destroyed ------------
(def victim (tx/make-ref :clean))
(println "INFO victim cell residence:" (tx/cell-residence victim))
(let [poison (fn [c] (assoc c :point Long/MAX_VALUE :history [] :value :poisoned))]
  (if (atom-layer/atom-value? (:cell victim))
    (atom-layer/reset-atom! (:cell victim)
                            (poison (atom-layer/deref-atom (:cell victim))))
    (swap! (:cell victim) poison)))

(tx/reset-stats!)
(def t0 (System/nanoTime))
(def outcome
  (try (tx/run-in-transaction (fn [] (tx/deref-ref victim)))
       :no-throw
       (catch IllegalStateException e
         {:faulted (.getMessage e)})))
(def elapsed-ms (/ (- (System/nanoTime) t0) 1e6))
(check "out-of-band basis destruction faults (does not spin)" :faulted
       (if (map? outcome) :faulted outcome))
(check "fault message is stock's" "Transaction failed after reaching retry limit"
       (:faulted outcome))
(check "attempts = basis-fault-limit + 1 (bounded, counted)"
       (inc tx/basis-fault-limit)
       (:attempts (tx/stats)))
(println "INFO faulted after" elapsed-ms "ms and" (:attempts (tx/stats))
         "attempts (pre-fix runner: never returns -- STATUS 14)")
(check-true "fault is bounded in wall-clock too" (< elapsed-ms 30000))

;; no silent re-basing: the poisoned cell stands exactly as poisoned
(check "poisoned cell unmoved by the faulted transaction" Long/MAX_VALUE
       (:point (if (atom-layer/atom-value? (:cell victim))
                 (atom-layer/deref-atom (:cell victim))
                 @(:cell victim))))

;; the layer is not wedged: an honest transaction still commits
(check "honest transaction still commits after the fault" 43
       (tx/run-in-transaction (fn [] (tx/alter* from + [43]))))

(println (if (empty? @failures)
           (str "PROBE-TX-RETRY-BOUND: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX-RETRY-BOUND: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
