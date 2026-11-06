(ns probe-tx-domains-main
  "STM lane (continuation): probe_tx_domains on the NODE (cljs) seat --
   STATUS 11 work item 2, STATUS 10's named smallest probe, extended
   with probe_tx's core semantics subset (full 23-check port is
   follow-on work, named in the lane report).

   Run (from the repo root; see dev/stm/probe_domains.edn):
     clojure -Sdeps \"$(clojure -M -e \"(load-file \\\"dev/eve-cljs/eve_paths.clj\\\") (print (eve-paths/eve-cljs-build-sdeps \\\".\\\" nil))\")\"
             -M:cljs -m cljs.main -co dev/stm/probe_domains.edn -c probe-tx-domains-main
     node target/stm-domains/main.js

   In the core realm: cohost.hosts.cljs-eve/install-core! installs the
   Eve hosting (whose factory now carries :make-domain-atom, this
   lane's leaf), then the checks run against REAL per-ref Eve domains:
   residence, one domain minted per ref, pairwise-distinct domain
   states read off the Eve handles, value independence, a two-ref
   transaction across domains, and the cooperative single-realm
   disjoint run (STATUS 10: intra-realm tasks are cooperative, so
   concurrent contention is realized as sequential attempts -- the
   zero-retry claim is checked as commits/retry counts over the
   layer's own stats, and cross-task contention is a worker-realm
   question this probe does not claim)."
  (:require [cohost.atom :as atom-layer]
            [cohost.hosts.cljs-eve :as cljs-eve]
            [cohost.tx :as tx]
            [cljs.nodejs :as nodejs]))

(nodejs/enable-util-print!)

;; cljs.nodejscli only invokes *main-cli-fn*; without this the compiled
;; probe loads its namespaces and exits silently (cljs.main :nodejs
;; builds do not auto-call -main).
(set! *main-cli-fn* -main)

(def checks (atom 0))
(def failures (atom []))

(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))

(defn check-true [label actual] (check label true (boolean actual)))

(defn -main [& _]
  (cljs-eve/install-core! {})
  (tx/set-fill! true)
  ;; -- 1. residence + one domain per ref -------------------------------
  (let [t0 (js/Date.now)
        rs (mapv (fn [i] (tx/make-ref i)) (range 10))
        create-ms (- (js/Date.now) t0)]
    (println "INFO make-ref over Eve domains (cljs):" create-ms "ms for 10 refs")
    (check "every ref's cell is :eve-own-domain" [:eve-own-domain]
           (distinct (map tx/cell-residence rs)))
    (check "the refs' Eve handles sit in 10 distinct domains" 10
           (count (distinct (map (fn [r] (.-domain-state (atom-layer/eve-handle (:cell r))))
                                 rs))))
    (check "refs hold independent values" [0 1 2 3 4 5 6 7 8 9]
           (mapv tx/deref-ref rs))
    (tx/run-in-transaction (fn [] (tx/alter* (nth rs 0) inc [])
                               (tx/alter* (nth rs 9) dec [])))
    (check "a two-ref transaction commits across domains" [1 8]
           [(tx/deref-ref (nth rs 0)) (tx/deref-ref (nth rs 9))])
    ;; -- 2. probe_tx's core semantics subset over domain cells ---------
    (let [r1 (tx/make-ref 41)]
      (check "dosync alter returns new value" 42
             (tx/run-in-transaction (fn [] (tx/alter* r1 inc []))))
      (check "committed" 42 (tx/deref-ref r1))
      (check "ref-set" 7
             (tx/run-in-transaction (fn [] (tx/ref-set* r1 7))))
      (check "commute folds at commit" 17
             (tx/run-in-transaction (fn [] (tx/commute* r1 + [10])))))
    ;; -- 3. disjoint transactions: zero retries (single realm) ----------
    (let [drefs (mapv (fn [_] (tx/make-ref 0)) (range 8))]
      (tx/reset-stats!)
      (doseq [r drefs]
        (dotimes [_ 100]
          (tx/run-in-transaction (fn [] (tx/alter* r inc [])))))
      (let [st (tx/stats)]
        (println "INFO disjoint stats (cljs):" st)
        (check "disjoint values all landed" [100 100 100 100 100 100 100 100]
               (mapv tx/deref-ref drefs))
        (check "disjoint transactions: 800 commits" 800 (:commits st))
        (check "disjoint transactions: ZERO retries" 0 (:retries st))))
    (println (if (empty? @failures)
               (str "PROBE-TX-DOMAINS-CLJS: ALL " @checks " CHECKS PASS")
               (str "PROBE-TX-DOMAINS-CLJS: " (count @failures) " FAILURES: " @failures)))
    (js/process.exit (if (empty? @failures) 0 1))))
