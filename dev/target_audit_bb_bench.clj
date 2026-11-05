;; target_audit_bb_bench.clj -- TARGET-AUDIT bench (2026-11-05): Eve op costs
;; on the babashka (:bb) branch of standalone Eve (~/src/eve @ main c7dc390),
;; stock bb controls in the same process. The :bb branch had NO numbers
;; anywhere (heap spike measured JVM :clj; the redo measured cljs).
;; Run: cd ~/src/eve && bb -cp src <this file>
;; (bb selects Eve's :bb reader-conditional branches automatically.)
(ns target-audit-bb-bench
  (:require [eve.atom :as ea]
            [eve.map :as em]
            [eve.vec :as ev]))

(defn now [] (System/nanoTime))

(defn bench [label n f]
  (f 10)
  (let [t0 (now)]
    (f n)
    (let [dt (- (now) t0)]
      (println (str "BENCH|" label "|n=" n
                    "|ns-per-op=" (format "%.1f" (double (/ dt n))))))))

(defn run []
  (println "bb version:" (System/getProperty "babashka.version"))
  (let [a (atom 0)]
    (bench "stock-atom-deref" 200000 (fn [n] (dotimes [_ n] @a)))
    (bench "stock-atom-swap-inc" 100000 (fn [n] (dotimes [_ n] (swap! a inc)))))
  (let [m (into {} (map (fn [i] [i i])) (range 1000))]
    (bench "stock-map-get" 200000 (fn [n] (dotimes [_ n] (get m 500)))))
  ;; Eve heap atom (bb branch: in-memory domain via resolve-heap-domain)
  (let [a (ea/atom ::counter 0)]
    (println "eve-atom initial:" @a)
    (bench "eve-atom-deref" 20000 (fn [n] (dotimes [_ n] @a)))
    (bench "eve-atom-swap-inc" 10000 (fn [n] (dotimes [_ n] (swap! a inc))))
    (println "eve-atom after swaps:" @a))
  ;; Eve slab collections directly
  (let [m (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 1000))]
    (println "eve-map count:" (count m) "get500:" (get m 500))
    (bench "eve-map-get" 20000 (fn [n] (dotimes [_ n] (get m 500))))
    (bench "eve-map-assoc-build-1000" 20
           (fn [n] (dotimes [_ n] (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 1000))))))
  (let [v (reduce (fn [vv i] (conj vv i)) (ev/empty-vec) (range 1000))]
    (println "eve-vec count:" (count v) "nth500:" (nth v 500))
    (bench "eve-vec-nth" 20000 (fn [n] (dotimes [_ n] (nth v 500)))))
  ;; deref of a 100-entry Eve-map root
  (let [m (reduce (fn [mm i] (assoc mm i i)) (em/empty-hash-map) (range 100))
        a (ea/atom ::maproot m)]
    (bench "eve-atom-deref-maproot100" 2000 (fn [n] (dotimes [_ n] @a)))
    (println "deref-maproot type:" (pr-str (type @a)) "get42:" (get @a 42)))
  (println "DONE"))

(run)
