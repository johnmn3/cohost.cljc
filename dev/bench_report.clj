;; The bench tables (`bb bench:report [dir]`, `bb bench:report --compare
;; before-dir after-dir`), from the bench-<host>[-s<scale>].edn files the
;; bench:* tasks write into target/ (or dir).
;;
;; - every host's stock core against JVM Clojure's own core, at the same scale
;;   (a host too slow for full size, basilisp, runs every case at a fraction of
;;   its size, and is read against JVM Clojure run at that fraction);
;; - each host's own core against the stock core booted on it;
;; - boot times;
;; - with --compare: the stock core's time before and after, per host.
;;
;; A ratio is one median call over another; a summary is the geometric mean
;; over the cases with both numbers. A stock time counts against JVM Clojure
;; only when it printed JVM Clojure's own answer; a host's own core and the
;; stock core are compared only where both answered alike.
(require '[clojure.edn :as edn] '[clojure.string :as str] '[babashka.fs :as fs])

(def hosts
  [["clj" "JVM Clojure 1.12.0"] ["jolt" "jolt v0.8.23"] ["lpy" "basilisp 0.5.1"]
   ["cljs" "ClojureScript, Node"] ["chromium" "ClojureScript, Chromium"]
   ["cljw" "ClojureWasm v1.15.4"] ["phel" "Phel v0.54.0"] ["lg" "let-go v1.13.0"]
   ["cljrs" "clojurust 0.2.32"] ["jank" "jank"]])
(def host-name (into {} hosts))

(defn load-runs
  "{[label scale] run} from dir's bench-*.edn files."
  [dir]
  (into {} (for [f (fs/glob dir "bench-*.edn")
                 :let [r (edn/read-string (slurp (str f)))]]
             [[(:host r) (or (:scale r) 1)] r])))

(defn- by-id [run] (into {} (map (juxt :id identity) (:cases run))))
(defn- case-of [run id] (get (by-id run) id))

(defn- ms [x]
  (cond (nil? x) "-"
        (>= x 100) (format "%.0f" (double x))
        (>= x 10) (format "%.1f" (double x))
        :else (format "%.2f" (double x))))
(defn- ratio [a b] (when (and a b (pos? b)) (/ a b)))
(defn- rx [r] (cond (nil? r) "-" (< r 0.995) (format "×%.2f" (double r)) :else (format "×%.1f" (double r))))
(defn- geomean [xs] (let [xs (remove nil? xs)] (when (seq xs) (Math/exp (/ (reduce + (map #(Math/log %) xs)) (count xs))))))
(defn- scale-note [s] (if (= 1 s) "" (str " (at " (if (< s 1) (str "1/" (Math/round (/ 1.0 s))) s) " size)")))

(defn- primary
  "Each present host's run: the largest scale it ran at."
  [runs]
  (for [[k _] hosts
        :let [ss (sort > (for [[[l s] _] runs :when (= l k)] s))]
        :when (seq ss)]
    [k (first ss) (get runs [k (first ss)])]))

(defn- jvm-run [runs scale] (get runs ["clj" scale]))
(defn- jvm-value [runs scale id]
  (let [c (some-> (jvm-run runs scale) (case-of id))]
    (when (and c (not (get-in c [:host :error]))) (:host-value c))))
(defn- jvm-ms [runs scale id]
  (let [c (some-> (jvm-run runs scale) (case-of id))]
    (when (and c (not (get-in c [:host :error]))) (get-in c [:host :median]))))
(defn- stock-ms
  "The stock side's median, when it ran and printed JVM Clojure's answer."
  [runs run id]
  (let [c (case-of run id) s (or (:scale run) 1) v (jvm-value runs s id)]
    (when (and c (not (get-in c [:stock :error])) (some? v) (= (:stock-value c) v))
      (get-in c [:stock :median]))))
(defn- pair-ms [run id side] (let [c (case-of run id)] (when (:same? c) (get-in c [side :median]))))

(defn- case-order [runs]
  (let [r (or (get runs ["clj" 1]) (val (first runs)))] (map :id (:cases r))))
(defn- labels [runs] (into {} (for [r (vals runs) c (:cases r)] [(:id c) (:label c)])))

(defn report [dir]
  (let [runs (load-runs dir)
        ps (primary runs)
        ids (case-order runs)
        lab (labels runs)]
    (println "## Against JVM Clojure: the stock core on each host\n")
    (println "Median ms per call; × is the stock core's time over JVM Clojure's own at the same size.\n")
    (println (str "| case | JVM Clojure | "
                  (str/join " | " (for [[k s _] ps] (str "cohost on " (host-name k) (scale-note s)))) " |"))
    (println (str "|---|---:|" (str/join "" (repeat (count ps) "---:|"))))
    (doseq [id ids]
      (println (str "| " (lab id) " | " (ms (jvm-ms runs 1 id)) " | "
                    (str/join " | " (for [[_ s r] ps]
                                      (let [x (stock-ms runs r id)] (str (ms x) " " (rx (ratio x (jvm-ms runs s id)))))))
                    " |")))
    (println (str "| **geometric mean** | | "
                  (str/join " | " (for [[_ s r] ps] (rx (geomean (map #(ratio (stock-ms runs r %) (jvm-ms runs s %)) ids)))))
                  " |"))

    (println "\n## Each host's own core against the stock core booted on it\n")
    (println "Median ms per call: the host's own core / the stock core, and the stock core's time over the own core's.\n")
    (println (str "| case | " (str/join " | " (for [[k s _] ps] (str (host-name k) (scale-note s)))) " |"))
    (println (str "|---|" (str/join "" (repeat (count ps) "---:|"))))
    (doseq [id ids]
      (println (str "| " (lab id) " | "
                    (str/join " | " (for [[_ _ r] ps]
                                      (let [h (pair-ms r id :host) s (pair-ms r id :stock)]
                                        (str (ms h) " / " (ms s) " " (rx (ratio s h))))))
                    " |")))
    (println (str "| **geometric mean** | "
                  (str/join " | " (for [[_ _ r] ps] (rx (geomean (map #(ratio (pair-ms r % :stock) (pair-ms r % :host)) ids)))))
                  " |"))
    (println (str "| own core vs JVM Clojure (geo. mean) | "
                  (str/join " | " (for [[_ s r] ps] (rx (geomean (map #(ratio (pair-ms r % :host) (jvm-ms runs s %)) ids)))))
                  " |"))

    (println "\n## Boot\n")
    (println "| host | core.clj boot (791 forms) | the twelve libraries |")
    (println "|---|---:|---:|")
    (doseq [[k _ r] ps]
      (println (str "| " (host-name k) " | " (format "%.1f s" (/ (double (:boot-ms r)) 1000)) " | "
                    (format "%.1f s" (/ (double (:libs-ms r)) 1000)) " |")))

    (let [odd (for [[k s r] ps c (:cases r)
                    :let [h-err (get-in c [:host :error]) s-err (get-in c [:stock :error])
                          v (jvm-value runs s (:id c))
                          off-jvm (and (not s-err) (some? v) (not= (:stock-value c) v))]
                    :when (or h-err s-err off-jvm (and (not (:same? c)) (not h-err) (not s-err)))]
                (str "- " (host-name k) ", " (:label c) ": "
                     (cond s-err (str "the stock core throws: " s-err)
                           off-jvm (str "the stock core answers " (:stock-value c) ", JVM Clojure " v)
                           h-err (str "the host's own core throws: " h-err)
                           :else (str "the host's own core answers " (:host-value c) ", the stock core " (:stock-value c)))))]
      (when (seq odd)
        (println "\n## Cases with a side missing (an error, or a different answer)\n")
        (doseq [l odd] (println l))))))

(defn compare-runs [before-dir after-dir]
  (let [b (load-runs before-dir) a (load-runs after-dir)
        ps (filter (fn [[k s _]] (get b [k s])) (primary a))
        ids (case-order a)
        lab (labels a)]
    (println "## The stock core, before and after\n")
    (println "Median ms per call before → after on each host, and the speed-up (before over after).\n")
    (println (str "| case | " (str/join " | " (for [[k s _] ps] (str (host-name k) (scale-note s)))) " |"))
    (println (str "|---|" (str/join "" (repeat (count ps) "---:|"))))
    (doseq [id ids]
      (println (str "| " (lab id) " | "
                    (str/join " | " (for [[k s r] ps]
                                      (let [x0 (stock-ms b (get b [k s]) id) x1 (stock-ms a r id)]
                                        (str (ms x0) " → " (ms x1) " " (rx (ratio x0 x1))))))
                    " |")))
    (println (str "| **geometric mean speed-up** | "
                  (str/join " | " (for [[k s r] ps]
                                    (rx (geomean (map #(ratio (stock-ms b (get b [k s]) %) (stock-ms a r %)) ids)))))
                  " |"))
    (println (str "| boot | "
                  (str/join " | " (for [[k s r] ps]
                                    (format "%.1f s → %.1f s" (/ (double (:boot-ms (get b [k s]))) 1000) (/ (double (:boot-ms r)) 1000))))
                  " |"))))

(let [args *command-line-args*]
  (if (= "--compare" (first args))
    (compare-runs (nth args 1) (nth args 2))
    (report (or (first args) "target"))))
