;; fib 30. -run parses its argument on every call so the size is not a constant.
(defn fib [n]
  (if (< n 2)
    n
    (+ (fib (dec n)) (fib (- n 2)))))
(defn -run [s]
  (fib (parse-long s)))
