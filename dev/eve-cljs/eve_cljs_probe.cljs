(ns eve-cljs-probe
  "Step-1 proof for the cljs-dep lane: Eve is a dependency of cohost's
  ClojureScript build. Requires Eve's atom root from the Eve git dep,
  constructs a SharedArrayBuffer-backed Eve atom under Node, and
  swap!/reset!/deref round-trips a value through it.

  The recipe is Eve's own test surface (test/eve/batch4_validation_test.cljs):
  eve.process-slab/init! creates the slab classes' SABs and regions;
  eve.atom is the current SharedAtom root (the #eve/shared-atom type);
  fresh-env resets the collection pools and binds the global domain.

  Build + run (from the repo root, standard cohost env): see
  doc/eve-cohost/CLJS-DEP-STATUS.md for the full command (the Eve
  hosts.edn :cljs row paths and the node: foreign-lib bridges are part
  of it; both are computed from dev/eve-cljs/eve_paths.clj)."
  (:require [eve.atom :as a]
            [eve.map :as sm]
            [eve.process-slab :as ps]
            [eve.sab :as sab]
            [eve.set :as ss]
            [eve.shared-atom :as sa]))

(defn- round-trip! [label mk]
  (try
    (let [x (mk {:n 0 :msg "hello"})]
      (println "EVE-PROBE" label "initial" (pr-str @x))
      (swap! x update :n inc)
      (swap! x assoc :msg "round-trip")
      (println "EVE-PROBE" label "after-swap" (pr-str @x))
      (reset! x {:n 42})
      (println "EVE-PROBE" label "after-reset" (pr-str @x))
      (println "EVE-PROBE" label "RESULT" (if (= {:n 42} @x) "PASS" "FAIL")))
    (catch :default e
      (println "EVE-PROBE" label "ERROR" (.-message e)))))

(defn -main [& _args]
  (ps/init!)
  ;; batch4 fresh-env
  (sm/reset-pools!)
  (ss/reset-pools!)
  (set! a/*global-atom-instance* (a/atom-domain {}))
  (sab/get-env a/*global-atom-instance*)
  (println "EVE-PROBE eve.atom shared-atom?" (a/shared-atom? (a/atom {:probe true})))
  (round-trip! "eve.atom" a/atom)
  (round-trip! "eve.shared-atom" sa/atom))

(set! *main-cli-fn* -main)
