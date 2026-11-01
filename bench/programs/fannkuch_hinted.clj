;; Same lexicographic fannkuch checksum, with ^long indexes.
(defn flips [p]
  (loop [p (vec p) f 0]
    (let [^long k (p 0)]
      (if (zero? k)
        f
        (recur (loop [p p i 0 j k]
                 (if (< i j)
                   (recur (assoc p i (p j) j (p i)) (inc i) (dec j))
                   p))
               (inc f))))))

(defn perms [xs]
  (if (empty? xs)
    [[]]
    (mapcat (fn [x]
              (map (fn [ys] (cons x ys))
                   (perms (remove (fn [y] (= y x)) xs))))
            xs)))

(defn -run [s]
  (let [^long n (__long s)]
    (loop [ps (perms (range n)) sign 1 acc 0]
      (if (nil? ps)
        acc
        (recur (next ps) (- sign) (+ acc (* sign (flips (first ps)))))))))
