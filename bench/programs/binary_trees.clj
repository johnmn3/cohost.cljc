;; Allocate perfect binary trees and sum their node counts.
(defn build [d]
  (if (zero? d)
    nil
    (let [d (dec d)]
      [(build d) (build d)])))

(defn check [t]
  (if (nil? t)
    1
    (+ 1 (check (t 0)) (check (t 1)))))

(defn -run [s]
  (let [max-d (parse-long s)]
    (loop [d 4 acc (check (build (inc max-d)))]
      (if (> d max-d)
        acc
        (let [n (bit-shift-left 1 (+ (- max-d d) 2))]
          (recur (+ d 2)
                 (+ acc
                    (loop [i 0 s 0]
                      (if (= i n)
                        s
                        (recur (inc i) (+ s (check (build d)))))))))))))
