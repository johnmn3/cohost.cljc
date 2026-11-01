;; How many points of an n by n grid are in the Mandelbrot set (limit 50).
(defn inside? [cr ci]
  (loop [i 0 zr 0.0 zi 0.0]
    (let [zr2 (* zr zr)
          zi2 (* zi zi)]
      (if (or (= i 50) (> (+ zr2 zi2) 4.0))
        (< i 50)
        (recur (inc i) (+ (- zr2 zi2) cr) (+ (* 2.0 zr zi) ci))))))

(defn -run [s]
  (let [n (parse-long s)
        scale (/ 2.0 n)]
    (loop [y 0 acc 0]
      (if (= y n)
        acc
        (recur (inc y)
               (loop [x 0 acc acc]
                 (if (= x n)
                   acc
                   (recur (inc x)
                          (if (inside? (- (* x scale) 1.5) (- (* y scale) 1.0))
                            (inc acc)
                            acc)))))))))
