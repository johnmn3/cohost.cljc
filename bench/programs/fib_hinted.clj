;; fib 30 with ^long on the parameter and the recursive results.
(defn fib [^long n]
  (if (< n 2)
    n
    (+ (fib (- n 1)) (fib (- n 2)))))
(defn -run [s]
  (fib (Long/parseLong s)))
