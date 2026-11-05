;; heap_cljs_bench.cljs -- P3 redo bench (2026-11-05): Eve op costs on the
;; cljs/Node seat (SAB-backed eve.atom + eve.map/eve.vec), stock cljs controls
;; in the same process. Run record + numbers: HEAP-SPIKE.md section 5.2.
;; Build (from ~/src/eve, transient bench build): place as
;; test/heap_bench/main.cljs, add a :heap-bench :node-script build,
;; shadow-cljs release heap-bench, node target/heap-bench.js.
(ns heap-bench.main
  (:require [eve.process-slab :as eve-alloc] ;; RE-BENCH ADAPTATION (2026-11-05): init! moved from eve.deftype-proto.alloc to eve.process-slab between Eve c7dc390 and the impl pin d241de3 ("Moved from eve.deftype-proto.alloc's ClojureScript arm" -- process_slab.cljs). Same fn, same promise shape; bench shapes unchanged.
            [eve.atom :as ea]
            [eve.map :as em]
            [eve.vec :as ev]))

(defn now [] (js/performance.now))

(defn bench [label n f]
  (f 10) ;; warm
  (let [t0 (now)]
    (f n)
    (let [dt (- (now) t0)]
      (println (str "BENCH|" label "|n=" n "|ms=" (.toFixed dt 2)
                    "|ns-per-op=" (.toFixed (/ (* dt 1e6) n) 1))))))

(defn run []
  (println "node" js/process.version)
  ;; ---- stock cljs controls (same process, same loop shapes) ----
  (let [a (atom 0)]
    (bench "stock-atom-deref" 200000 (fn [n] (dotimes [_ n] @a)))
    (bench "stock-atom-swap-inc" 100000 (fn [n] (dotimes [_ n] (swap! a inc))))
    ;; ADDED 2026-11-05 re-bench: reset! had no spike-era row
    (bench "stock-atom-reset" 200000 (fn [n] (dotimes [_ n] (reset! a 0)))))
  (let [m (into {} (map (fn [i] [i i])) (range 1000))]
    (bench "stock-map-get" 200000 (fn [n] (dotimes [_ n] (get m 500))))
    (bench "stock-map-assoc-build-1000" 200
           (fn [n] (dotimes [_ n] (reduce (fn [mm i] (assoc mm i i)) {} (range 1000))))))
  ;; ---- Eve SAB atom ----
  (let [a (ea/atom ::counter 0)]
    (println "eve-atom initial:" @a)
    (bench "eve-sab-atom-deref" 200000 (fn [n] (dotimes [_ n] @a)))
    (bench "eve-sab-atom-swap-inc" 100000 (fn [n] (dotimes [_ n] (swap! a inc))))
    (println "eve-atom after swaps:" @a)
    ;; ADDED 2026-11-05 re-bench: reset! had no spike-era row
    (bench "eve-sab-atom-reset" 100000 (fn [n] (dotimes [_ n] (reset! a 0)))))
  ;; ---- Eve map (slab-backed, in the SAB domain) ----
  (let [m (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 1000))]
    (println "eve-map count:" (count m) "get500:" (get m 500))
    (bench "eve-map-get" 100000 (fn [n] (dotimes [_ n] (get m 500)))))
  (bench "eve-map-assoc-build-1000" 200
         (fn [n] (dotimes [_ n] (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 1000)))))
  ;; ---- capacity on the SAB seat (JVM heap domain died at these) ----
  (try
    (let [m (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 10000))]
      (println "CAPACITY|eve-map-10000|OK count=" (count m)))
    (catch :default e (println "CAPACITY|eve-map-10000|FAIL" (str e))))
  (try
    (let [v (reduce (fn [vv i] (conj vv i)) (ev/empty-vec) (range 1000))]
      (println "CAPACITY|eve-vec-1000|OK count=" (count v)))
    (catch :default e (println "CAPACITY|eve-vec-1000|FAIL" (str e))))
  ;; ---- Eve atom deref of a 100-entry Eve-map root ----
  (let [m (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 100))
        a (ea/atom ::maproot m)]
    (bench "eve-sab-atom-deref-maproot100" 100000 (fn [n] (dotimes [_ n] @a)))
    (println "deref-maproot type:" (pr-str (type @a)) "get42:" (get @a 42)))
  (println "DONE"))

(defn main []
  (-> (eve-alloc/init!)
      (.then (fn [_] (run)))
      (.catch (fn [e] (println "INIT FAIL" (str e)) (.exit js/process 1)))))

(set! *main-cli-fn* main)
