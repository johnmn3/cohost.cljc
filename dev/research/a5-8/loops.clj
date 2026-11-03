;; A5-8: which literal-started loop locals the codemod types native ((cpp/long 0) in the dump)
;;   bash dev/research/u6-10/dump.sh dev/research/a5-8/loops.clj dbl kw ok iloop dloop mixed
(defn dbl [] (loop [i 0 x 0] (if (< i 3) (recur (inc i) (+ x 0.5)) x)))
(defn kw [] (loop [i 0 x 0] (if (< i 2) (recur (inc i) (if (= i 0) 1 :k)) x)))
(defn ok [] (loop [i 0 s 0] (if (< i 5) (recur (inc i) (if (odd? i) (+ s i) s)) s)))
(defn iloop [] (loop [i 0 acc 0] (if (< i 1000000) (recur (inc i) (+ acc (* i 3))) acc)))
(defn dloop [] (loop [i 0 x 0.5 acc 0.0] (if (< i 1000000) (recur (inc i) (+ x 0.25) (+ acc x)) acc)))
(defn mixed [] (loop [i 0 j 0] (if (< i 3) (recur (inc i) (+ j (count [i]))) j)))
