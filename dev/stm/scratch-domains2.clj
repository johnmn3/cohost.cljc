(require '[eve.atom :as eve-atom])
(defn rate [label n f]
  (let [s (System/nanoTime)] (f n)
    (println (format "%-28s %8.0f ops/ms  (%6.2f µs/op)" label (/ n (/ (- (System/nanoTime) s) 1e6)) (* (/ (- (System/nanoTime) s) 1e3) (/ 1.0 n))))))
(def d1 (eve-atom/atom-domain {}))
(def a1 (eve-atom/lookup-or-create-mmap-atom! d1 "a" {:value 0}))
;; warmup curve for deref on a sole-domain atom
(doseq [chunk (range 5)]
  (rate (str "deref sole (chunk " chunk ")") 20000 (fn [n] (dotimes [_ n] @a1))))
(doseq [chunk (range 3)]
  (rate (str "swap! sole (chunk " chunk ")") 2000 (fn [n] (dotimes [_ n] (swap! a1 update :value inc)))))
;; shared domain comparison
(def ds (eve-atom/atom-domain {}))
(def as (eve-atom/lookup-or-create-mmap-atom! ds "a" {:value 0}))
(doseq [chunk (range 3)]
  (rate (str "deref shared (chunk " chunk ")") 20000 (fn [n] (dotimes [_ n] @as))))
;; domain creation time, isolated timings
(def ts (mapv (fn [_] (let [t0 (System/nanoTime)] (eve-atom/atom-domain {}) (/ (- (System/nanoTime) t0) 1e6))) (range 10)))
(println "domain create ms each:" (mapv (fn [x] (format "%.1f" x)) ts))
(def t0 (System/nanoTime)) (eve-atom/lookup-or-create-mmap-atom! (eve-atom/atom-domain {}) "x" {:value 1})
(println (format "domain+atom create: %.1f ms" (/ (- (System/nanoTime) t0) 1e6)))
