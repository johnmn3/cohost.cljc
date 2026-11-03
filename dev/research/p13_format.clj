;; bb bench:jank's format case (cohost.bench :format, n 5000) on its own, under cohost's
;; stock core: bash dev/research/run-cohost-jank.sh dev/research/p13_format.clj
;; (JANK_O=3 for the -O3 the jank tasks pass). The harness warms 2 s, then 5 timed calls.
(defn run []
  (bench "format-5000" (fn [] (count (apply str (map (fn [i] (format "%05d|%s" i "x")) (range 5000)))))))
