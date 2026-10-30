;; The bench tables (`bb bench:report`), from the target/bench-<host>.edn files
;; the bench:* tasks write: every host's stock core against JVM Clojure's own,
;; and each host's own core against the stock core booted on it. A ratio is
;; the stock side's median call over the other side's; a summary is the
;; geometric mean over the cases both sides answered alike.
(require '[clojure.edn :as edn] '[clojure.string :as str] '[babashka.fs :as fs])

(def hosts
  [["clj" "JVM Clojure 1.12.0"] ["jolt" "jolt v0.8.23"] ["lpy" "basilisp 0.5.1"]
   ["cljs" "ClojureScript, Node"] ["chromium" "ClojureScript, Chromium"]])

(def runs
  (into {} (for [[k _] hosts
                 :let [f (str "target/bench-" k ".edn")]
                 :when (fs/exists? f)]
             [k (edn/read-string (slurp f))])))

(defn- by-id [run] (into {} (map (juxt :id identity) (:cases run))))

(defn- ms [x]
  (cond (nil? x) "-"
        (>= x 100) (format "%.0f" (double x))
        (>= x 10) (format "%.1f" (double x))
        :else (format "%.2f" (double x))))

(defn- ratio [a b] (when (and a b (pos? b)) (/ a b)))
(defn- rx [r] (if r (format "×%.1f" (double r)) "-"))
(defn- geomean [xs] (when (seq xs) (Math/exp (/ (reduce + (map #(Math/log %) xs)) (count xs)))))

(def jvm (by-id (get runs "clj")))
(def case-order (map :id (:cases (or (get runs "clj") (val (first runs))))))
(def labels (into {} (for [r (vals runs) c (:cases r)] [(:id c) (:label c)])))
(def present (filter (fn [[k _]] (contains? runs k)) hosts))

(defn- stock-ms [k id] (let [c (get (by-id (get runs k)) id)] (when (:same? c) (get-in c [:stock :median]))))
(defn- host-ms [k id] (let [c (get (by-id (get runs k)) id)] (when (:same? c) (get-in c [:host :median]))))
(def baseline (fn [id] (host-ms "clj" id)))

(println "## Against JVM Clojure: the stock core on each host\n")
(println "Median ms per call; × is the stock core's time over JVM Clojure's own.\n")
(println (str "| case | JVM Clojure | " (str/join " | " (map #(str "cohost on " (second %)) present)) " |"))
(println (str "|---|---:|" (str/join "" (repeat (count present) "---:|"))))
(doseq [id case-order]
  (println (str "| " (labels id) " | " (ms (baseline id)) " | "
                (str/join " | " (for [[k _] present]
                                  (let [s (stock-ms k id)] (str (ms s) " " (rx (ratio s (baseline id)))))))
                " |")))
(println (str "| **geometric mean** | | "
              (str/join " | " (for [[k _] present]
                                (rx (geomean (keep #(ratio (stock-ms k %) (baseline %)) case-order)))))
              " |"))

(println "\n## Each host's own core against the stock core booted on it\n")
(println "Median ms per call; × is the stock core's time over the host's own core's.\n")
(println (str "| case | " (str/join " | " (for [[_ n] present] (str n ": own / cohost"))) " |"))
(println (str "|---|" (str/join "" (repeat (count present) "---:|"))))
(doseq [id case-order]
  (println (str "| " (labels id) " | "
                (str/join " | " (for [[k _] present]
                                  (let [h (host-ms k id) s (stock-ms k id)]
                                    (str (ms h) " / " (ms s) " " (rx (ratio s h))))))
                " |")))
(println (str "| **geometric mean** | "
              (str/join " | " (for [[k _] present]
                                (rx (geomean (keep #(ratio (stock-ms k %) (host-ms k %)) case-order)))))
              " |"))
(println (str "| own core vs JVM Clojure (geo. mean) | "
              (str/join " | " (for [[k _] present]
                                (rx (geomean (keep #(ratio (host-ms k %) (baseline %)) case-order)))))
              " |"))

(println "\n## Boot\n")
(println "| host | core.clj boot (791 forms) | the twelve libraries |")
(println "|---|---:|---:|")
(doseq [[k n] present :let [r (get runs k)]]
  (println (str "| " n " | " (format "%.1f s" (/ (double (:boot-ms r)) 1000)) " | "
                (format "%.1f s" (/ (double (:libs-ms r)) 1000)) " |")))

(let [odd (for [[k n] present c (:cases (get runs k)) :when (not (:same? c))]
            (str "- " n ", " (:label c) ": "
                 (or (get-in c [:host :error]) (get-in c [:stock :error])
                     (str "the host's own core answers " (:host-value c) ", the stock core " (:stock-value c)))))]
  (when (seq odd)
    (println "\n## Cases not timed (an error, or the two sides differ)\n")
    (doseq [l odd] (println l))))
