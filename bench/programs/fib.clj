;; fib 30. Portable Clojure. The value is the last expression.
(defn fib [n]
  (if (< n 2) n (+ (fib (dec n)) (fib (- n 2)))))
(fib 30)
