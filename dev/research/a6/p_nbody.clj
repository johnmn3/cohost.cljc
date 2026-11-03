;; A6-1 (u3): nbody's operations alone, 1e6 each, the same source under cohost (p_nbody.clj) and
;; in plain jank (p_nbody.jank). P-numbers are the A6-1 block's candidate numbers.
(def v7 [4.84 -1.16 -0.10 0.60 2.81 -0.02 0.037])
(def w7 [8.34 4.12 -0.40 -1.01 1.82 0.008 0.011])
(def bodies5 [v7 w7 v7 w7 v7])
(def N 1000000)
(defn run []
  (bench "P0 loop-only" #(loop [i 0 s 0] (if (< i N) (recur (inc i) (inc s)) s)))
  (bench "P1 (v i) read" #(loop [i 0 s nil] (if (< i N) (recur (inc i) (v7 3)) s)))
  (bench "P2 (nth v i)" #(loop [i 0 s nil] (if (< i N) (recur (inc i) (nth v7 3)) s)))
  (bench "P3 (get v i)" #(loop [i 0 s nil] (if (< i N) (recur (inc i) (get v7 3)) s)))
  (bench "P4 (- (v 0) (w 0))" #(loop [i 0 s 0.0] (if (< i N) (recur (inc i) (- (v7 0) (w7 0))) s)))
  (bench "P5 dx*dx+dy*dy+dz*dz" #(loop [i 0 s 0.0] (if (< i N) (recur (inc i) (let [dx (- (v7 0) (w7 0)) dy (- (v7 1) (w7 1)) dz (- (v7 2) (w7 2))] (+ (* dx dx) (* dy dy) (* dz dz)))) s)))
  (bench "P6 (/ a b) doubles" #(loop [i 0 s 0.0] (if (< i N) (recur (inc i) (/ (v7 6) (w7 6))) s)))
  (bench "P7 assoc 3 keys" #(loop [i 0 s nil] (if (< i N) (recur (inc i) (assoc v7 3 1.0 4 2.0 5 3.0)) s)))
  (bench "P8 count" #(loop [i 0 s 0] (if (< i N) (recur (inc i) (count bodies5)) s)))
  (bench "P9 = on longs" #(loop [i 0 s 0] (if (= i N) s (recur (inc i) (inc s)))))
  (bench "P10 destructure 3" #(loop [i 0 s 0.0] (if (< i N) (recur (inc i) (let [[a b c] v7] c)) s))))
(def __core-run run)
(defn run []
  (__core-run)
  (bench "P11 Math/sqrt" #(loop [i 0 s 0.0] (if (< i N) (recur (inc i) (Math/sqrt (v7 4))) s))))
