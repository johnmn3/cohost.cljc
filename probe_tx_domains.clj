;; STM lane (redesign): the per-ref/per-domain probe. Run on the JVM seat:
;;   clojure -M:eve probe_tx_domains.clj
;; Installs an Eve factory FIRST, so cohost.tx's refs are minted as
;; cohost.atom domain atoms -- one Eve atom domain per ref -- and
;; verifies the redesign's three claims with evidence:
;;   1. residence: every ref's cell is :eve-own-domain, the factory
;;      minted exactly one domain per ref, and the refs' Eve handles
;;      sit in pairwise-distinct domain states (read off the handles
;;      themselves, not a counter's word);
;;   2. disjoint transactions never contend: concurrent transactions on
;;      disjoint ref sets commit with ZERO retries (tx/stats);
;;   3. retries are scoped to the conflicting ref: under heavy contention
;;      on ref P, transactions touching only ref Q retry only as often
;;      as Q's own (rare) conflicts explain -- in the world-atom design
;;      they would retry on EVERY commit anywhere.
;; Plus: an unrepresentable ref value (a fn) still transacts, seated in
;; its domain atom's wrapper host seat, as unit 5's atoms behave.
;;
;; The factory is installed probe-locally, with the same two Eve calls
;; cohost.atom-eve's :make-domain-atom makes (eve.atom/atom-domain +
;; lookup-or-create-mmap-atom!), because cohost.atom-eve itself does
;; not compile against the current Eve checkout: its unit-7
;; root-digests references eve.atom/seal and eve.digest, neither of
;; which exists in Eve at the sibling checkout's current state. That
;; drift predates this lane and is named in its report as a remainder
;; for the mainline; the factory path exercised here (cohost.atom/
;; make-domain-atom -> the seat's :make-domain-atom fn) is the code
;; under test either way.

(require 'cohost.hosts.auto)
(require '[eve.atom :as eve-atom]
         '[cohost.atom :as atom-layer]
         '[cohost.tx :as tx]
         '[cohost.thread :as thread])

(def domains-minted (atom 0))
(atom-layer/install-factory!
 {:make-domain-atom
  (fn [slab-init]
    (let [d (eve-atom/atom-domain {})
          n (swap! domains-minted inc)]
      (eve-atom/lookup-or-create-mmap-atom! d (str "cohost.tx/ref" n)
                                            slab-init)))})
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

;; -- 1. residence + one domain per ref ---------------------------------
(def t0 (System/nanoTime))
(def rs (mapv (fn [i] (tx/make-ref i)) (range 10)))
(def create-ms (/ (- (System/nanoTime) t0) 1e6))
(println (format "INFO make-ref over Eve domains: %.0f ms for 10 refs (%.0f ms/ref)"
                 create-ms (/ create-ms 10.0)))
(check "every ref's cell is :eve-own-domain" [:eve-own-domain]
       (distinct (map tx/cell-residence rs)))
(check "the factory minted one domain per ref" 10 @domains-minted)
(check "the refs' Eve handles sit in 10 distinct domains" 10
       (count (distinct (map (fn [r] (.-domain-state (atom-layer/eve-handle (:cell r))))
                             rs))))
(check "refs hold independent values" [0 1 2 3 4 5 6 7 8 9]
       (mapv tx/deref-ref rs))
(tx/run-in-transaction (fn [] (tx/alter* (nth rs 0) inc [])
                              (tx/alter* (nth rs 9) dec [])))
(check "a two-ref transaction commits across domains" [1 8]
       [(tx/deref-ref (nth rs 0)) (tx/deref-ref (nth rs 9))])

;; an unrepresentable value: the cell seats in the wrapper's host seat,
;; the domain atom still the ref's own; semantics unchanged
(def fr (tx/make-ref (fn [x] (inc x))))
(check "fn-valued ref residence" :eve-own-domain (tx/cell-residence fr))
(check "fn-valued ref cell gate is :host" :host
       (:gate (atom-layer/state-of (:cell fr))))
(tx/run-in-transaction (fn [] (tx/alter* fr (fn [f] (fn [x] (+ 2 (f x)))) [])))
(check "fn-valued ref transacts" 5 ((tx/deref-ref fr) 2))

;; -- 2. disjoint transactions: zero retries -----------------------------
(def disjoint-refs (mapv (fn [_] (tx/make-ref 0)) (range 8)))
(tx/reset-stats!)
(def dthreads
  (mapv (fn [r] (thread/spawn
                 (fn [] (dotimes [_ 100]
                          (tx/run-in-transaction
                           (fn [] (tx/alter* r inc [])))))))
        disjoint-refs))
(doseq [t dthreads] (thread/join-thread t))
(def dstats (tx/stats))
(println "INFO disjoint stats:" dstats)
(check "disjoint values all landed" [100 100 100 100 100 100 100 100]
       (mapv tx/deref-ref disjoint-refs))
(check "disjoint transactions: 800 commits" 800 (:commits dstats))
(check "disjoint transactions: ZERO retries" 0 (:retries dstats))

;; -- 3. retries scoped to the conflicting ref ---------------------------
;; P is hammered by an aggressor; a victim transacts over P+Q and must
;; retry (its conflicts are real); a bystander transacts over Q only.
;; Q's only other writer is the victim (20 transactions), so bystander
;; retries are bounded by the victim's commits -- while total commits
;; exceed 800. A shared-snapshot design would retry the bystander on
;; commits anywhere (hundreds of times).
(def P (tx/make-ref 0))
(def Q (tx/make-ref 0))
(def victim-runs (atom 0))
(def bystander-runs (atom 0))
(def aggressor
  (thread/spawn (fn [] (dotimes [_ 400]
                         (tx/run-in-transaction (fn [] (tx/alter* P inc [])))))))
(def victim
  (thread/spawn (fn [] (dotimes [_ 20]
                         (tx/run-in-transaction
                          (fn [] (swap! victim-runs inc)
                            (tx/alter* P inc [])
                            (Thread/sleep 5)
                            (tx/alter* Q inc [])))))))
(def bystander
  (thread/spawn (fn [] (dotimes [_ 400]
                         (tx/run-in-transaction
                          (fn [] (swap! bystander-runs inc)
                            (tx/alter* Q inc [])))))))
(thread/join-thread aggressor)
(thread/join-thread victim)
(thread/join-thread bystander)
(def victim-retries (- @victim-runs 20))
(def bystander-retries (- @bystander-runs 400))
(println (format "INFO scoped contention: victim retries %d, bystander retries %d, P=%d Q=%d"
                 victim-retries bystander-retries (tx/deref-ref P) (tx/deref-ref Q)))
(check "P landed every write" 420 (tx/deref-ref P))
(check "Q landed every write" 420 (tx/deref-ref Q))
(check-true "the victim really retried (contention was real)" (pos? victim-retries))
(check-true "bystander retries bounded by Q's own conflicts (<= victim's 20 commits)"
            (<= bystander-retries 20))

(println (if (empty? @failures)
           (str "PROBE-TX-DOMAINS: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX-DOMAINS: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
