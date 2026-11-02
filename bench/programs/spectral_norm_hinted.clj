;; Spectral norm, ^long indexes and ^double accumulators.
(defn eval-a [^long i ^long j]
  (/ 1.0 (+ (* (+ i j) (inc (+ i j)) 0.5) (inc i))))

(defn mul-av [v]
  (let [n (count v)]
    (mapv (fn [^long i]
            (loop [j 0 s 0.0]
              (if (= j n)
                s
                (let [^double vij (nth v j)]
                  (recur (inc j) (+ s (* (eval-a i j) vij)))))))
          (range n))))

(defn mul-atv [v]
  (let [n (count v)]
    (mapv (fn [^long i]
            (loop [j 0 s 0.0]
              (if (= j n)
                s
                (let [^double vij (nth v j)]
                  (recur (inc j) (+ s (* (eval-a j i) vij)))))))
          (range n))))

(defn mul-ata [v]
  (mul-atv (mul-av v)))

(defn -run [s]
  (let [^long n (parse-long s)
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
                     (let [^double vi (nth v i)]
                       (recur (inc i) (+ s (* vi vi))))))]
          (Math/round (* (Math/sqrt (/ vBv vv)) 1000000.0)))
        (recur (inc k) (mul-ata (mul-ata u)))))))
