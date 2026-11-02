;; jank-FAST research item 1 (D-3): medians of the runs
;; dev/perf/jank_snapshot_o3.sh kept under target/jank-o3/.
;;   bb dev/perf/jank_snapshot_o3_report.clj
;; Prints, per side (snap/aot x none/O3): boot-ms, libs-ms and their sum
;; (each the median of 3 on its own); then per bench case and boot path the
;; stock side's median ms without and with -O3 and their ratio (with/without).
(require '[clojure.edn :as edn] '[babashka.fs :as fs])
(defn runs [side] (mapv #(edn/read-string (slurp (str %))) (sort (fs/glob "target/jank-o3" (str side "-*.edn")))))
(defn median [xs] (let [v (vec (sort xs))] (when (seq v) (nth v (quot (count v) 2)))))
(defn fmt [x] (if (number? x) (format "%.1f" (double x)) (str x)))
(doseq [side ["snap-none" "snap-O3" "aot-none" "aot-O3"]]
  (let [rs (runs side)]
    (println "SIDE" side "runs" (count rs)
             "boot-ms" (fmt (median (map :boot-ms rs)))
             "libs-ms" (fmt (median (map :libs-ms rs)))
             "sum" (fmt (median (map #(+ (:boot-ms %) (:libs-ms %)) rs))))))
(defn case-median [side id k]
  (median (keep (fn [r] (some #(when (= id (:id %)) (get-in % [k :median])) (:cases r))) (runs side))))
(defn same-all? [side]
  (every? (fn [r] (every? :same? (:cases r))) (runs side)))
(doseq [side ["snap-none" "snap-O3" "aot-none" "aot-O3"]]
  (println "SAME" side (same-all? side)))
(let [ids (map :id (:cases (first (runs "snap-none"))))]
  (doseq [path ["snap" "aot"] id ids]
    (let [a (case-median (str path "-none") id :stock)
          b (case-median (str path "-O3") id :stock)
          ha (case-median (str path "-none") id :host)
          hb (case-median (str path "-O3") id :host)]
      (println "CASE" path (name id)
               "stock" (fmt a) "->" (fmt b) (if (and a b (pos? a)) (str "x" (format "%.2f" (/ b a))) "")
               "host" (fmt ha) "->" (fmt hb)))))
