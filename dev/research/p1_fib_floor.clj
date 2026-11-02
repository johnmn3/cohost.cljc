;; P1, the JVM twin of p1_fib_floor.jank. fib-hinted-leaf is the JVM's
;; primitive recursion (^long param and return: IFn$LL, no boxing).
(defn fib [n]
  (if (< n 2) n (+ (fib (dec n)) (fib (- n 2)))))
(defn fib-hinted ^long [^long n]
  (if (< n 2) n (+ (fib-hinted (dec n)) (fib-hinted (- n 2)))))
(def fib-local
  (let [self (volatile! nil)]
    (vreset! self (fn [n] (if (< n 2) n (+ (@self (dec n)) (@self (- n 2))))))
    @self))
(defn run []
  (bench "fib-var" #(fib 30))
  (bench "fib-hinted-leaf" #(fib-hinted 30))
  (bench "fib-local-volatile" #(fib-local 30)))
