(ns eve-cljs-probe
  "Step-1 proof for the cljs-dep lane: Eve is a dependency of cohost's
  ClojureScript build. Requires eve.shared-atom from the Eve git dep,
  constructs a SharedArrayBuffer-backed Eve atom under Node, and
  swap!/reset!/deref round-trips a value through it.

  Build + run (from the repo root, standard cohost env):
    clojure -Sdeps '{:paths [\"src\" \"resources\" \"dev/eve-cljs\"]}' -M:cljs \\
      -m cljs.main -co '{:target :nodejs :output-to \"target/eve-cljs-probe/main.js\" :optimizations :none}' \\
      -c eve-cljs-probe
    node target/eve-cljs-probe/main.js"
  (:require [eve.process-slab :as ps]
            [eve.shared-atom :as sa]))

(defn -main [& _args]
  ;; Eve's slab layer first: init! creates the six slab classes' SABs and
  ;; regions plus the root SAB synchronously (its Promise covers only the
  ;; WASM upgrade). Every Eve entry point does this before atoms exist.
  (ps/init!)
  (let [a (sa/atom {:n 0 :msg "hello"})]
    (println "EVE-PROBE shared-atom?" (sa/shared-atom? a))
    (println "EVE-PROBE initial" (pr-str @a))
    (swap! a update :n inc)
    (swap! a assoc :msg "round-trip")
    (println "EVE-PROBE after-swap" (pr-str @a))
    (reset! a {:n 42})
    (println "EVE-PROBE after-reset" (pr-str @a))
    (println "EVE-PROBE RESULT" (if (= {:n 42} @a) "PASS" "FAIL"))))

(set! *main-cli-fn* -main)
