;; A7-1: binary-trees' depth-14 tree under cohost, split: build only, check only, both
;;   JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/a7/bt_split.clj
(defn build [d] (if (zero? d) nil (let [d (dec d)] [(build d) (build d)])))
(defn check [t] (if (nil? t) 1 (+ 1 (check (t 0)) (check (t 1)))))
(defn check-raw [t] (if (identical? t nil) 1 (+ 1 (check-raw (nth t 0)) (check-raw (nth t 1)))))
(def t14 (build 14))
(defn run []
  (println "checks" (check t14) (check-raw t14))
  (bench "build-14" #(count (build 14)))
  (bench "check-14" #(check t14))
  (bench "both-14" #(check (build 14))))
