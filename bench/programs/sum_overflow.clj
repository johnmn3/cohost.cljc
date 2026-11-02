;; A reduce whose result exceeds 2^31: n = 1000000 sums to 499999500000 on the JVM.
;; A host whose integer arithmetic wraps at 32 bits prints another number (DIFFER).
(defn -run [s]
  (let [n (parse-long s)]
    (reduce + (range n))))
