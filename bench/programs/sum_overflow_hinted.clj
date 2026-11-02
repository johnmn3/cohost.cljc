;; Same sum, as a ^long loop (the primitive arm a JVM programmer writes).
(defn sum-below ^long [^long n]
  (loop [i 0 acc 0]
    (if (= i n)
      acc
      (recur (+ i 1) (+ acc i)))))

(defn -run [s]
  (sum-below (long (parse-long s))))
