;; P1's fib floor through cohost.run (U6-6): the cohost-path twin of dev/research/p1_fib_floor.jank.
;; Run: jank -O3 --module-path <aot-dir>:src run-main cohost.run -- --eval "$(cat dev/research/p1_fib_floor_cohost.clj)"
(defn fib [n] (if (< n 2) n (+ (fib (dec n)) (fib (- n 2)))))
(defn fib-hinted [^long n] (if (< n 2) n (+ (fib-hinted (dec n)) (fib-hinted (- n 2)))))
(def fib-local (let [self (volatile! nil)] (vreset! self (fn [n] (if (< n 2) n (+ (@self (dec n)) (@self (- n 2)))))) @self))
(defn bench [nm f] (f) (let [ts (vec (for [_ (range 5)] (let [t0 (System/nanoTime) r (f) t1 (System/nanoTime)] (- t1 t0))))] (println "P1" nm "median-ns" (nth (sort ts) 2) "result" (f))))
(bench "fib-var" #(fib 30))
(bench "fib-hinted-leaf" #(fib-hinted 30))
(bench "fib-local-volatile" #(fib-local 30))
