;; reduce / assoc / update-in over maps. Returns the nested sum.
(defn -run [s]
  (let [n (__long s)
        m (reduce (fn [m i]
                    (-> m
                        (assoc :n i)
                        (update-in [:a :b] (fnil + 0) i)
                        (assoc-in [:xs (mod i 32)] {:v (* i i)})))
                  {}
                  (range n))]
    (+ (get-in m [:a :b]) (get-in m [:xs (mod (dec n) 32) :v]))))
