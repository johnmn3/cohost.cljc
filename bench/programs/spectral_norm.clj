;; Spectral norm of the CLBG matrix, 10 iterations. Scaled by 1e6.
(defn eval-a [i j]
  (/ 1.0 (+ (* (+ i j) (inc (+ i j)) 0.5) (inc i))))

(defn mul-av [v]
  (let [n (count v)]
    (mapv (fn [i]
            (loop [j 0 s 0.0]
              (if (= j n)
                s
                (recur (inc j) (+ s (* (eval-a i j) (nth v j)))))))
          (range n))))

(defn mul-atv [v]
  (let [n (count v)]
    (mapv (fn [i]
            (loop [j 0 s 0.0]
              (if (= j n)
                s
                (recur (inc j) (+ s (* (eval-a j i) (nth v j)))))))
          (range n))))

(defn mul-ata [v]
  (mul-atv (mul-av v)))

(defn -run [s]
  (let [n (parse-long s)
        u0 (vec (repeat n 1.0))]
    (loop [k 0 u u0]
      (if (= k 10)
        (let [v (mul-ata u)
              vBv (loop [i 0 s 0.0]
                    (if (= i n)
                      s
                      (recur (inc i) (+ s (* (nth u i) (nth v i))))))
              vv (loop [i 0 s 0.0]
                   (if (= i n)
                     s
                     (recur (inc i) (+ s (* (nth v i) (nth v i))))))]
          (Math/round (* (Math/sqrt (/ vBv vv)) 1000000.0)))
        (recur (inc k) (mul-ata (mul-ata u)))))))
