;; A lazy pipeline over a long range: count of (filter odd? (map inc (range n))).
;; Every element crosses range, map and filter as a lazy seq (jank-15/16/17's measure).
(defn -run [s]
  (let [n (parse-long s)]
    (count (filter odd? (map inc (range n))))))
