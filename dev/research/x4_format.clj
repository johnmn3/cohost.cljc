;; X4-1: the bench's :format case alone (bb bench:jank showed it 80.9 -> 330.1 ms
;; stock after X4-1's first change; noise or not). Run on both trees.
(defn fmt [] (count (apply str (map (fn [i] (format "%05d|%s" i "x")) (range 5000)))))
(defn run [] (bench "format-5e3" fmt) (bench "format-5e3-again" fmt))
