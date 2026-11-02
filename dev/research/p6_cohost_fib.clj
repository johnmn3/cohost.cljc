;; P6 (session 3, the cohost layer): where cohost's fib time goes on jank.
;; Runs under cohost's stock core on jank (dev/research/run-cohost-jank.sh)
;; and, for the same source, on raw jank and the JVM (the twins).
;; fib-stock: the suite's fib: stock < + dec - (core.clj's :inline forms,
;;   through the shim's Numbers ops) and the stock defn.
;; fib-host-ops: the same fn with jank's own clojure.core/< + dec -, so only
;;   the fn definition and call shape are cohost's.
;; fib-fn-def: (def fib (fn ...)) instead of defn, with the stock ops.
;; fib-host-ops-fn-def: both substitutions.
;; loop-stock / loop-host-ops: 5e6 boxed iterations of inc and <, no call:
;;   the arithmetic shim's cost alone.
(defn fib-stock [n]
  (if (< n 2) n (+ (fib-stock (dec n)) (fib-stock (- n 2)))))
(defn fib-host-ops [n]
  (if (clojure.core/< n 2) n (clojure.core/+ (fib-host-ops (clojure.core/dec n)) (fib-host-ops (clojure.core/- n 2)))))
(def fib-fn-def
  (fn fib-fn-def [n] (if (< n 2) n (+ (fib-fn-def (dec n)) (fib-fn-def (- n 2))))))
(def fib-host-ops-fn-def
  (fn fib-host-ops-fn-def [n] (if (clojure.core/< n 2) n (clojure.core/+ (fib-host-ops-fn-def (clojure.core/dec n)) (fib-host-ops-fn-def (clojure.core/- n 2))))))
(defn loop-stock [] (loop [i 0] (if (< i 5000000) (recur (inc i)) i)))
(defn loop-host-ops [] (loop [i 0] (if (clojure.core/< i 5000000) (recur (clojure.core/inc i)) i)))
(defn run []
  (bench "fib-stock" #(fib-stock 30))
  (bench "fib-host-ops" #(fib-host-ops 30))
  (bench "fib-fn-def" #(fib-fn-def 30))
  (bench "fib-host-ops-fn-def" #(fib-host-ops-fn-def 30))
  (bench "loop-stock-5e6" loop-stock)
  (bench "loop-host-ops-5e6" loop-host-ops))
