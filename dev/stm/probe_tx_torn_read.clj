;; STM defect F1 (2026-11-06): the torn read across a commit's installs.
;; Run on the JVM seat, over Eve domain cells (the cell kind the lane
;; really uses -- millisecond-slow, so the window is wide) or host cells:
;;   clojure -Sdeps '{:deps {eve/eve {:local/root "<eve>"}}}' -M dev/stm/probe_tx_torn_read.clj eve
;;   clojure -Sdeps '{:paths ["src"]}' -M dev/stm/probe_tx_torn_read.clj host
;; (<eve>: an Eve checkout; run 2026-11-06 against Eve main 6502d37.
;; deps.edn's -M:eve alias puts only <eve>/src on the path, which no
;; longer loads current Eve: eve.host lives under adapters/, which
;; Eve's own deps.edn :paths supply -- hence :local/root.)
;;
;; The defect: an attempt fixes its read point lazily at its first ref
;; touch, as the commit-point clock's current value. commit! claims its
;; point cp from that clock and THEN installs its refs one CAS at a time.
;; A fresh attempt whose first touch lands between the claim and the last
;; install gets rp = cp: it reads the already-installed refs at cp and the
;; not-yet-installed refs at their OLD points -- a torn snapshot, which a
;; read-only transaction (not validated at commit -- stock's answer)
;; returns. Stock closes it with Ref's read lock (doGet blocks while the
;; committer holds the write locks across getCommitPoint and the installs).
;;
;; Section 1 -- the snapshot invariant: refs A and B transferred between by
;; committer threads (dosync + alter on both, A + B constant); reader
;; threads run read-only transactions reading both (half A-then-B, half
;; B-then-A) and count every pair whose sum is not the constant.
;; Section 2 -- outside-transaction deref: refs C and D stamped with the
;; same commit number k by every commit (C installs first: creation
;; order); outside-tx readers deref C then D. Stock's Ref.deref takes
;; the read lock too (Ref.currentVal), so once a reader has seen commit
;; k's C it cannot then see D older than k. A reader that does is the
;; same tear, outside a transaction.
;;
;; TEST-ONLY window widening: F1_WIDEN_MS=<n> wraps cohost.tx/install!
;; (with-redefs, probe-local) to sleep n ms after each install -- i.e.
;; between a commit's installs. It changes timing only, never the layer;
;; the layer never ships a delay. Default 0 (the natural window).

(require 'cohost.hosts.auto)
(require '[cohost.tx :as tx]
         '[cohost.thread :as thread]
         '[cohost.atom :as atom-layer])

(def mode (keyword (or (first *command-line-args*) "host")))
(def widen-ms (Long/parseLong (or (System/getenv "F1_WIDEN_MS") "0")))

(when (= mode :eve)
  (require 'eve.atom)
  (let [atom-domain (resolve 'eve.atom/atom-domain)
        lookup! (resolve 'eve.atom/lookup-or-create-mmap-atom!)
        n (atom 0)]
    ;; the probe-local factory: the same two Eve calls cohost.atom-eve's
    ;; :make-domain-atom makes (see probe_tx_domains.clj's header)
    (atom-layer/install-factory!
     {:make-domain-atom
      (fn [slab-init]
        (let [d (atom-domain {})
              k (swap! n inc)]
          (lookup! d (str "cohost.tx/f1-ref" k) slab-init)))})))
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

(def committers (Long/parseLong (or (System/getenv "F1_COMMITTERS") "2")))
(def per-committer (Long/parseLong (or (System/getenv "F1_COMMITS") "400")))
(def readers (Long/parseLong (or (System/getenv "F1_READERS") "4")))
(def total 2000)

(defn widened [f]
  (if (pos? widen-ms)
    (let [orig @#'cohost.tx/install!]
      (with-redefs [cohost.tx/install! (fn [att e cp]
                                         (let [r (orig att e cp)]
                                           (Thread/sleep widen-ms)
                                           r))]
        (f)))
    (f)))

(println "INFO mode" mode "| widen-ms" widen-ms "(TEST-ONLY, probe-local)"
         "| committers" committers "x" per-committer "| readers" readers)

;; -- 1. read-only transactions see a snapshot ------------------------
(def A (tx/make-ref 1000))
(def B (tx/make-ref 1000))
(println "INFO cell residence A/B:" (tx/cell-residence A) (tx/cell-residence B))

(def done (atom false))
(def commits (atom 0))
(def reads (atom 0))
(def reader-attempts (atom 0))
(def torn (atom 0))
(def torn-samples (atom []))

(defn reader [order]
  (fn []
    (loop []
      (when-not @done
        (let [[a b] (tx/run-in-transaction
                     (fn []
                       (swap! reader-attempts inc)
                       (if (= order :ab)
                         (let [a (tx/deref-ref A) b (tx/deref-ref B)] [a b])
                         (let [b (tx/deref-ref B) a (tx/deref-ref A)] [a b]))))]
          (swap! reads inc)
          (when (not= total (+ a b))
            (swap! torn inc)
            (when (< (count @torn-samples) 5) (swap! torn-samples conj [order a b])))
          (recur))))))

(defn committer [i]
  (fn []
    (dotimes [j per-committer]
      (let [amt (* (if (even? (+ i j)) 1 -1) (inc (rand-int 9)))]
        (tx/run-in-transaction
         (fn [] (tx/alter* A - [amt])
           (tx/alter* B + [amt]))))
      (swap! commits inc))))

(tx/reset-stats!)
(def t0 (System/nanoTime))
(widened
 (fn []
   (let [rs (mapv (fn [i] (thread/spawn (reader (if (even? i) :ab :ba)))) (range readers))
         cs (mapv (fn [i] (thread/spawn (committer i))) (range committers))]
     (doseq [c cs] (thread/join-thread c))
     (reset! done true)
     (doseq [r rs] (thread/join-thread r)))))
(def s1-ms (/ (- (System/nanoTime) t0) 1e6))
(def s1-stats (tx/stats))
(println (format "INFO section 1: %d commits, %d read-only txs (%d reader attempts, %d reader retries), %d torn, %.0f ms; tx/stats %s"
                 @commits @reads @reader-attempts (- @reader-attempts @reads) @torn s1-ms (pr-str s1-stats)))
(when (seq @torn-samples) (println "INFO torn samples [order a b]:" @torn-samples))
(check "every transfer committed" (* committers per-committer) @commits)
(check "transfer invariant holds at rest" total (+ (tx/deref-ref A) (tx/deref-ref B)))
(check-true "readers really read while commits ran" (pos? @reads))
(check "read-only transactions never see a torn pair (A + B constant)" 0 @torn)

;; -- 2. outside-transaction deref: a commit is seen whole -------------
(def C (tx/make-ref 0))
(def D (tx/make-ref 0))
(reset! done false)
(def stamps (atom 0))
(def outside-reads (atom 0))
(def outside-torn (atom 0))
(def outside-samples (atom []))

(defn outside-reader []
  (loop []
    (when-not @done
      (let [c (tx/deref-ref C)
            d (tx/deref-ref D)]
        (swap! outside-reads inc)
        (when (< d c)
          (swap! outside-torn inc)
          (when (< (count @outside-samples) 5) (swap! outside-samples conj [c d])))
        (recur)))))

(def t1 (System/nanoTime))
(widened
 (fn []
   (let [rs (mapv (fn [_] (thread/spawn outside-reader)) (range readers))
         c (thread/spawn
            (fn [] (dotimes [_ per-committer]
                     (tx/run-in-transaction
                      (fn [] (let [k (inc (tx/deref-ref C))]
                               (tx/ref-set* C k)
                               (tx/ref-set* D k))))
                     (swap! stamps inc))))]
     (thread/join-thread c)
     (reset! done true)
     (doseq [r rs] (thread/join-thread r)))))
(def s2-ms (/ (- (System/nanoTime) t1) 1e6))
(println (format "INFO section 2: %d stamped commits, %d outside deref pairs, %d saw D older than C, %.0f ms"
                 @stamps @outside-reads @outside-torn s2-ms))
(when (seq @outside-samples) (println "INFO outside samples [c d]:" @outside-samples))
(check "stamped commits landed" [per-committer per-committer]
       [(tx/deref-ref C) (tx/deref-ref D)])
(check-true "outside readers really read while commits ran" (pos? @outside-reads))
(check "outside-tx deref never sees a commit's later install older than its earlier one" 0 @outside-torn)

(println (if (empty? @failures)
           (str "PROBE-TX-TORN-READ: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX-TORN-READ: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
