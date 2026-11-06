;; STM lane: the tx layer probe. Run on the JVM seat:
;;   clojure -Sdeps '{:paths ["src"]}' -M probe_tx.clj
;; Exercises cohost.tx directly (the shim/boot wiring is exercised by
;; eve_cohost_check_stm.clj through a booted core): the tx layer's STM
;; semantics against stock Clojure 1.12.0's answers, the send-hold seam
;; into cohost.thread's agents, the retry signal (including its cost),
;; and cooperative interruption.

(require 'cohost.hosts.auto)
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

;; -- construction, deref outside a transaction -----------------------
(def r1 (tx/make-ref 41))
(check-true "ref-value?" (tx/ref-value? r1))
(check "deref outside tx" 41 (tx/deref-ref r1))

;; -- a transaction commits -------------------------------------------
(check "dosync alter returns new value" 42
       (tx/run-in-transaction (fn [] (tx/alter* r1 inc []))))
(check "committed" 42 (tx/deref-ref r1))

;; -- writes outside a transaction throw -------------------------------
(check "alter outside tx throws" :threw
       (try (tx/alter* r1 inc []) :no-throw
            (catch IllegalStateException e :threw)))

;; -- an aborting transaction leaves no partial writes ------------------
(def r2 (tx/make-ref 100))
(check "abort rolls back both refs" [42 100]
       (do (try (tx/run-in-transaction
                 (fn [] (tx/ref-set* r1 0) (tx/ref-set* r2 0)
                   (throw (Exception. "boom"))))
                (catch Exception e :caught))
           [(tx/deref-ref r1) (tx/deref-ref r2)]))

;; -- snapshot: a transaction's read point does not move ----------------
(def seen (atom nil))
(def t-snap
  (thread/spawn
   (fn [] (tx/run-in-transaction
           (fn [] (let [x (tx/deref-ref r1)]
                    (Thread/sleep 80)
                    (reset! seen [x (tx/deref-ref r1)])))))))
(Thread/sleep 30)
(tx/run-in-transaction (fn [] (tx/ref-set* r1 555)))
(thread/join-thread t-snap)
(check "snapshot read point stable" [42 42] @seen)
(check "outside read sees the commit" 555 (tx/deref-ref r1))

;; -- write skew is possible bare, prevented by ensure ------------------
;; invariant: a + b >= 100 after every commit. A debit of 20 is allowed
;; only when the total AFTER it still honors the invariant (total >= 120
;; before). Both transactions read both accounts and debit their own:
;; exactly one debit may land.
(def acct-a (tx/make-ref 60))
(def acct-b (tx/make-ref 60))
(def skew-seen (atom nil))
(def t1 (thread/spawn
         (fn [] (tx/run-in-transaction
                 (fn [] (when (>= (- (+ (tx/deref-ref acct-a) (tx/deref-ref acct-b)) 20) 100)
                          (Thread/sleep 60)
                          (tx/alter* acct-a - [20])))))))
(def t2 (thread/spawn
         (fn [] (tx/run-in-transaction
                 (fn [] (when (>= (- (+ (tx/deref-ref acct-a) (tx/deref-ref acct-b)) 20) 100)
                          (Thread/sleep 60)
                          (tx/alter* acct-b - [20])))))))
(thread/join-thread t1) (thread/join-thread t2)
(reset! skew-seen (+ (tx/deref-ref acct-a) (tx/deref-ref acct-b)))
(check "write skew happens without ensure (stock answer too)" 80 @skew-seen)

(def acct-a2 (tx/make-ref 60))
(def acct-b2 (tx/make-ref 60))
(def t3 (thread/spawn
         (fn [] (tx/run-in-transaction
                 (fn [] (tx/ensure* acct-b2)
                   (when (>= (- (+ (tx/deref-ref acct-a2) (tx/deref-ref acct-b2)) 20) 100)
                     (Thread/sleep 60)
                     (tx/alter* acct-a2 - [20])))))))
(def t4 (thread/spawn
         (fn [] (tx/run-in-transaction
                 (fn [] (tx/ensure* acct-a2)
                   (when (>= (- (+ (tx/deref-ref acct-a2) (tx/deref-ref acct-b2)) 20) 100)
                     (Thread/sleep 60)
                     (tx/alter* acct-b2 - [20])))))))
(thread/join-thread t3) (thread/join-thread t4)
(check "ensure prevents the skew" 100
       (+ (tx/deref-ref acct-a2) (tx/deref-ref acct-b2)))

;; -- commute: not validated, folds over the current value --------------
(def counter (tx/make-ref 0))
(def workers
  (mapv (fn [_] (thread/spawn
                 (fn [] (dotimes [_ 200]
                          (tx/run-in-transaction
                           (fn [] (tx/commute* counter inc [])))))))
        (range 8)))
(doseq [w workers] (thread/join-thread w))
(check "8 threads x 200 commutes, none lost" 1600 (tx/deref-ref counter))

;; -- transfer invariant under concurrent alter load (validation retries)
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
(check "transfer invariant holds" 20000
       (+ (tx/deref-ref from) (tx/deref-ref to)))
(check "all transfers landed" [8000 12000]
       [(tx/deref-ref from) (tx/deref-ref to)])
(println "INFO transfer attempts (retries included):" @attempts
         "for 2000 transactions")

;; -- validators run at commit ------------------------------------------
(def vr (tx/make-ref 5 {:validator (fn [v] (>= v 0))}))
(check "validator rejects the commit" :threw
       (try (tx/run-in-transaction (fn [] (tx/ref-set* vr -1))) :no-throw
            (catch IllegalStateException e :threw)))
(check "value unchanged after rejected commit" 5 (tx/deref-ref vr))

;; -- watches fire once per written ref, at commit, with (old new) ------
(def wr (tx/make-ref 1))
(def watch-log (atom []))
(tx/add-watch* wr :w (fn [k r old new] (swap! watch-log conj [k old new])))
(tx/run-in-transaction (fn [] (tx/ref-set* wr 2) (tx/ref-set* wr 3)))
(check "watch fired once, pre-commit and final values" [[:w 1 3]] @watch-log)
(check "history count" 1 (tx/ref-history-count wr))

;; -- sends inside a transaction: held, exactly once, only on commit ----
(def ag (thread/new-agent [] {}))
(tx/run-in-transaction
 (fn [] (thread/dispatch! ag conj [:committed] (thread/send-executor))))
(Thread/sleep 150)
(check "send from a committing tx fired once" [:committed] (thread/agent-state ag))
(try (tx/run-in-transaction
      (fn [] (thread/dispatch! ag conj [:aborted] (thread/send-executor))
        (throw (Exception. "boom"))))
     (catch Exception e nil))
(Thread/sleep 150)
(check "send from an aborting tx never fired" [:committed] (thread/agent-state ag))

;; -- the retry signal: cost of the throw/catch path ---------------------
(def bench-ref (tx/make-ref 0))
(def bench-n 2000)
(def t0 (System/nanoTime))
(dotimes [_ bench-n]
  (tx/run-in-transaction (fn [] (tx/commute* bench-ref inc []))))
(def bench-ms (/ (- (System/nanoTime) t0) 1e6))
(println (format "INFO uncontended commute tx: %.0f tx/ms (no throw taken)"
                 (/ bench-n bench-ms)))
;; force the throw path directly: a body whose commit always conflicts
(def hot (tx/make-ref 0))
(def throw-t0 (System/nanoTime))
(def throw-n 200)
(dotimes [i throw-n]
  (let [done (atom false)]
    (tx/run-in-transaction
     (fn [] (tx/alter* hot inc [])
       (when-not @done
         (reset! done true)
         ;; a competing commit between this attempt's read point and
         ;; its own commit: the attempt must retry via the exception.
         ;; (The world-atom construction's probe manufactured this by
         ;; poking the world map's version counter; the per-ref
         ;; construction manufactures the identical conflict through
         ;; the public surface only: a phantom commit from another
         ;; thread -- same value, moved commit point.)
         (let [t (thread/spawn
                  (fn [] (tx/run-in-transaction
                          (fn [] (tx/ref-set* hot (tx/deref-ref hot))))))]
           (thread/join-thread t)))))))
(def throw-ms (/ (- (System/nanoTime) throw-t0) 1e6))
(println (format "INFO forced-retry tx (throw + rerun each): %.0f tx/ms"
                 (/ throw-n throw-ms)))
(check "hot ref landed every increment" throw-n (tx/deref-ref hot))

;; -- cooperative interruption -------------------------------------------
(def latch (thread/new-latch 1))
(def blocked-outcome (atom nil))
(def t-block (thread/spawn
              (fn [] (reset! blocked-outcome
                             (try (thread/await-latch latch) :returned
                                  (catch Exception e
                                    (if (thread/interrupted-ex? e)
                                      :interrupted
                                      (str "other: " e))))))))
(Thread/sleep 50)
(thread/interrupt-thread! t-block)
(thread/join-thread t-block)
(check "interrupt wakes a blocked latch wait with the interrupt exception"
       :interrupted @blocked-outcome)

;; an interrupted thread abandons a transaction instead of retrying:
;; the body blocks on a layer latch inside the transaction, and the
;; interrupt surfaces there, mid-body, before any commit
(def itx-outcome (atom nil))
(def never (thread/new-latch 1))
(def t-itx (thread/spawn
            (fn [] (reset! itx-outcome
                           (try (tx/run-in-transaction
                                 (fn [] (tx/ref-set* r1 1)
                                   (thread/await-latch never)
                                   :committed))
                                (catch Exception e
                                  (if (thread/interrupted-ex? e)
                                    :interrupted
                                    (str "other: " e))))))))
(Thread/sleep 50)
(thread/interrupt-thread! t-itx)
(thread/join-thread t-itx)
(check "interrupted tx thread abandons" :interrupted @itx-outcome)
(check "abandoned tx wrote nothing" 555 (tx/deref-ref r1))

(println (if (empty? @failures)
           (str "PROBE-TX: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
