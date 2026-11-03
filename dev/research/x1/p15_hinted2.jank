;; X1-11: the ^double / other hinted twins. One variant per form that differs between
;; bench/programs/{mandelbrot,spectral_norm,binary_trees,nbody}.clj and their _hinted twins
;; (dumps/*.txt). Run like p14: cohost (run-cohost-jank.sh) and plain jank (run-jank.sh aot).
;; mandelbrot: inside? with plain params (cohost: an is_real -> to_real specialised arm) vs ^double params
;; (the coercion wrapper to a boxed double + inner fn*); a 120x120 grid.
(defn md-inside [cr ci]
  (loop [i 0 zr 0.0 zi 0.0]
    (let [zr2 (* zr zr) zi2 (* zi zi)]
      (if (or (= i 50) (> (+ zr2 zi2) 4.0)) (< i 50) (recur (inc i) (+ (- zr2 zi2) cr) (+ (* 2.0 zr zi) ci))))))
(defn md-inside-h [^double cr ^double ci]
  (loop [i 0 zr 0.0 zi 0.0]
    (let [zr2 (* zr zr) zi2 (* zi zi)]
      (if (or (= i 50) (> (+ zr2 zi2) 4.0)) (< i 50) (recur (inc i) (+ (- zr2 zi2) cr) (+ (* 2.0 zr zi) ci))))))
(defn md-grid [f n]
  (let [scale (/ 2.0 n)]
    (loop [y 0 acc 0]
      (if (= y n) acc
          (recur (inc y)
                 (loop [x 0 acc acc]
                   (if (= x n) acc
                       (recur (inc x) (if (f (- (* x scale) 1.5) (- (* y scale) 1.0)) (inc acc) acc)))))))))
;; spectral-norm: eval-a with plain params (cohost: an is_integer -> to_int arm) vs ^long params.
(defn ea [i j] (/ 1.0 (+ (* (+ i j) (inc (+ i j)) 0.5) (inc i))))
(defn ea-h [^long i ^long j] (/ 1.0 (+ (* (+ i j) (inc (+ i j)) 0.5) (inc i))))
(defn ea-sum [f n]
  (loop [i 0 s 0.0] (if (= i n) s (recur (inc i) (loop [j 0 s s] (if (= j n) s (recur (inc j) (+ s (f i j)))))))))
;; spectral-norm / nbody: a vector read used directly vs bound through (double ...).
(def dv (vec (map #(* 1.5 %) (range 100000))))
(defn sv-plain [] (loop [j 0 s 0.0] (if (= j 100000) s (recur (inc j) (+ s (* 0.5 (nth dv j)))))))
(defn sv-double [] (loop [j 0 s 0.0] (if (= j 100000) s (let [x (double (nth dv j))] (recur (inc j) (+ s (* 0.5 x)))))))
;; nbody: (* dx dx) on a difference, plain vs bound through (double ...).
(defn nb-plain [] (loop [j 1 s 0.0] (if (= j 100000) s (let [dx (- (nth dv j) (nth dv (dec j)))] (recur (inc j) (+ s (* dx dx)))))))
(defn nb-double [] (loop [j 1 s 0.0] (if (= j 100000) s (let [dx (double (- (nth dv j) (nth dv (dec j))))] (recur (inc j) (+ s (* dx dx)))))))
;; binary-trees: build with dec (plain), (- d 1), ^long d with (- d 1) (the twin), ^long d with dec.
(defn bt-dec [d] (if (zero? d) nil (let [d (dec d)] [(bt-dec d) (bt-dec d)])))
(defn bt-minus [d] (if (zero? d) nil (let [d (- d 1)] [(bt-minus d) (bt-minus d)])))
(defn bt-param [^long d] (if (zero? d) nil (let [d (- d 1)] [(bt-param d) (bt-param d)])))
(defn bt-param-dec [^long d] (if (zero? d) nil (let [d (dec d)] [(bt-param-dec d) (bt-param-dec d)])))
(defn bt-count [t] (if (nil? t) 1 (+ 1 (bt-count (t 0)) (bt-count (t 1)))))
(defn run []
  (bench "md-plain" #(md-grid md-inside 120))
  (bench "md-param" #(md-grid md-inside-h 120))
  (bench "ea-plain" #(ea-sum ea 300))
  (bench "ea-param" #(ea-sum ea-h 300))
  (bench "sv-plain" sv-plain)
  (bench "sv-double" sv-double)
  (bench "nb-plain" nb-plain)
  (bench "nb-double" nb-double)
  (bench "bt-dec" #(bt-count (bt-dec 14)))
  (bench "bt-minus" #(bt-count (bt-minus 14)))
  (bench "bt-param" #(bt-count (bt-param 14)))
  (bench "bt-param-dec" #(bt-count (bt-param-dec 14))))
