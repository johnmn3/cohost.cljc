;; STM lane (continuation): probe_tx_domains' RESIDENCE CHECK on the
;; JOLT seat -- STATUS 10's named smallest probe for this target.
;;   jolt -Scp src -m probe-tx-domains-jolt   (see note)
;; Jolt is JVM-shaped (src/cohost/hosts/jolt.clj), so the probe is the
;; JVM probe's first section with the factory installed probe-locally
;; -- the same two Eve calls cohost.atom-eve's :make-domain-atom makes
;; (eve.atom/atom-domain + lookup-or-create-mmap-atom!). What it
;; separates: whether Eve's standalone-line namespaces load on jolt
;; at all (the atom-eve drift -- eve.atom/seal, eve.digest -- blocks
;; cohost.atom-eve on every seat; the namespaces this probe requires
;; directly are the question), and whether per-ref domains mint and
;; stay distinct on the seat. 10 refs, 10 pairwise-distinct domains,
;; values independent, one two-ref transaction across domains.
;;
;; NOTE the run line: cohost boots on jolt via `jolt -Scp src -m
;; cohost.run` (bb boot:jolt); a bare script runs the same way with
;; the probe's own -main. Eve's namespaces come from the sibling Eve
;; checkout's src (the :eve alias's path), added to the classpath.

(require 'cohost.hosts.auto)
(require '[eve.atom :as eve-atom]
         '[cohost.atom :as atom-layer]
         '[cohost.tx :as tx])

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

(def rs (mapv (fn [i] (tx/make-ref i)) (range 10)))
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

(println (if (empty? @failures)
           (str "PROBE-TX-DOMAINS-JOLT: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX-DOMAINS-JOLT: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
