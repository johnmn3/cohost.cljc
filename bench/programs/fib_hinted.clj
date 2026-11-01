;; fib 30 with ^long, the hints a JVM programmer would write.
(defn fib [^long n]
  (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(fib 30)
