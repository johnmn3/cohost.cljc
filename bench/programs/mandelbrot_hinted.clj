;; Mandelbrot count, ^double coordinates and ^long indexes.
(defn inside? [^double cr ^double ci]
  (loop [i 0 zr 0.0 zi 0.0]
    (let [^double zr2 (* zr zr)
          ^double zi2 (* zi zi)]
      (if (or (= i 50) (> (+ zr2 zi2) 4.0))
        (< i 50)
        (recur (inc i) (+ (- zr2 zi2) cr) (+ (* 2.0 zr zi) ci))))))

(defn -run [s]
  (let [^long n (__long s)
        ^double scale (/ 2.0 n)]
    (loop [y 0 acc 0]
      (if (= y n)
        acc
        (recur (inc y)
               (loop [x 0 acc acc]
                 (if (= x n)
                   acc
                   (let [^double cr (- (* x scale) 1.5)
                         ^double ci (- (* y scale) 1.0)]
                     (recur (inc x) (if (inside? cr ci) (inc acc) acc))))))))))
