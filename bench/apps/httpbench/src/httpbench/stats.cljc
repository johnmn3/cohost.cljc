(ns httpbench.stats
  "The pure-Clojure side of the numbers: percentile math over a sorted
   vector, and the report shapes both output modes print. The hot bench
   keeps its histogram in C++ (httpbench.native); these are the same
   definitions over plain data, so they can be tested on every host.")

(defn percentile
  "The q-th percentile (q in [0,1]) of a sorted vector of numbers: the
   smallest value at or above the q fraction of the samples (nearest-rank).
   nil for an empty vector."
  [sorted q]
  (let [n (count sorted)]
    (when (pos? n)
      (let [x (* q n)
            rank (if (== x (long x)) (long x) (inc (long x)))]
        (nth sorted (min (dec n) (max 0 (dec rank))))))))

(defn requests-per-second
  "Completed requests over wall nanoseconds, as a double."
  [n wall-ns]
  (when (and (pos? n) (pos? wall-ns))
    (/ (* n 1000000000.0) wall-ns)))

(defn summarize
  "A bench result map (keys :count :wall-ns :errors :bytes :p50 :p90 :p99
   :p99.9 :max) -> the same map with :req-per-sec added."
  [m]
  (assoc m :req-per-sec (requests-per-second (:count m) (:wall-ns m))))
