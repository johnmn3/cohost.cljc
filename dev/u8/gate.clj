;; Unit 8 gate runner (bb): bb dev/u8/gate.clj
;;   1. the probe row: dev/eve_probe.clj's program (byte-identical, read from
;;      dev/eve_probe_program.cljc) on :jvm and :cljs -- every PROBE line equal;
;;   2. the worker-crossing program (dev/u8/realm_crossing_program.cljc) on
;;      :jvm and :cljs -- every PROBE line equal -- with the cljs run's realm
;;      trace (COHOST_REALM_TRACE=1) showing the futures ran in a worker realm.
(require '[babashka.process :as p]
         '[clojure.string :as str])

(defn run-host [host program env]
  (let [t0 (System/currentTimeMillis)
        r (apply p/shell {:out :string :err :string :continue true :timeout 900000
                          :extra-env env}
                 ["bin/cohost" (str ":" host) "-e" program])]
    {:lines (filterv #(str/starts-with? % "PROBE|") (str/split-lines (str (:out r))))
     :err (:err r) :exit (:exit r) :ms (- (System/currentTimeMillis) t0)}))

(defn compare-rows [label jvm cljs]
  (let [jm (into (sorted-map) (map (fn [l] (let [[_ n r] (str/split l #"\|" 3)] [n r]))) (:lines jvm))
        cm (into (sorted-map) (map (fn [l] (let [[_ n r] (str/split l #"\|" 3)] [n r]))) (:lines cljs))
        names (distinct (concat (keys jm) (keys cm)))
        same (count (filter #(= (get jm %) (get cm %)) names))]
    (doseq [n names]
      (println (str "ROW|" label "|" n "|" (if (= (get jm n) (get cm n)) "SAME" "DIFF")
                    "|jvm=" (get jm n) "|cljs=" (get cm n))))
    (println (str "SUMMARY|" label "|same=" same "/" (count names)
                  " jvm-ms=" (:ms jvm) " cljs-ms=" (:ms cljs)))
    (= same (count names))))

(let [probe (slurp "dev/eve_probe_program.cljc")
      cross (slurp "dev/u8/realm_crossing_program.cljc")
      env {"COHOST_REALM_TRACE" "1"}
      pj (run-host "jvm" probe {})
      pc (run-host "cljs" probe env)
      ok1 (compare-rows "probe" pj pc)
      cj (run-host "jvm" cross {})
      cc (run-host "cljs" cross env)
      ok2 (compare-rows "crossing" cj cc)]
  (doseq [[label r] [["probe" pc] ["crossing" cc]]]
    (let [tr (filter #(str/starts-with? % "cohost-realm[") (str/split-lines (str (:err r))))]
      (println (str "TRACE|" label "|lines=" (count tr)
                    " handed-over=" (count (filter #(str/includes? % "handed over") tr))
                    " completed-in-worker=" (count (filter #(str/includes? % "completed in realm") tr))
                    " closures=" (count (filter #(str/includes? % "stays a closure") tr))))
      (doseq [l tr] (println (str "TRACE|" label "|" l)))))
  (println (str "GATE|" (if (and ok1 ok2) "GREEN" "RED"))))
