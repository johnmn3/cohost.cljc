;; jank-FAST Step 8. bb bench:programs <target>|all
;; A program file's last expression is the value. Cold is one fresh process.
;; Warm is one process: one untimed call, then five timed calls. The median
;; is the middle of those five. Output is checked against JVM Clojure before
;; a time is kept.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def programs
  [{:id :fib :file "bench/programs/fib.clj"}
   {:id :fib-hinted :file "bench/programs/fib_hinted.clj"}])

(def targets
  [{:id "jvm" :exe "clojure" :kind :own}
   {:id "jvm-cohost" :exe "clojure" :kind :cohost}
   {:id "jank-jit" :exe "jank" :kind :own}
   {:id "jank-aot" :exe "jank" :kind :own}
   {:id "jank" :exe "jank" :kind :cohost}
   {:id "jolt" :exe "jolt" :kind :own}
   {:id "jolt-cohost" :exe "jolt" :kind :cohost}
   {:id "lpy" :exe "basilisp" :kind :own}
   {:id "lpy-cohost" :exe "basilisp" :kind :cohost}
   {:id "cljs" :exe "node" :kind :own
    :blocked "ClojureScript is not in the local Maven repo, so the Node build cannot be compiled here"}
   {:id "cljs-cohost" :exe "node" :kind :cohost
    :blocked "ClojureScript is not in the local Maven repo, so the Node build cannot be compiled here"}
   {:id "browser" :exe "node" :kind :own
    :blocked "ClojureScript is not in the local Maven repo, so the browser build cannot be compiled here"}
   {:id "browser-cohost" :exe "node" :kind :cohost
    :blocked "ClojureScript is not in the local Maven repo, so the browser build cannot be compiled here"}
   {:id "cljw" :exe "cljw" :kind :own}
   {:id "cljw-cohost" :exe "cljw" :kind :cohost}
   {:id "phel" :exe "phel" :kind :own}
   {:id "phel-cohost" :exe "phel" :kind :cohost}
   {:id "lg" :exe "lg" :kind :own}
   {:id "lg-cohost" :exe "lg" :kind :cohost}
   {:id "cljrs" :exe "cljrs" :kind :own}
   {:id "cljrs-cohost" :exe "cljrs" :kind :cohost}])

(defn present? [t]
  (and (fs/which (:exe t)) (not (:blocked t))))

(defn reason [t]
  (cond
    (:blocked t) (:blocked t)
    (not (fs/which (:exe t))) (str "no " (:exe t) " on PATH")
    :else nil))

(defn wrap [src clock cold?]
  (str "(defn __work [] (do " src "\n))\n"
       "(defn __go [] (let [t0 " clock " r (__work) t1 " clock "]\n"
       "  (println \"PROGRAM-RESULT\" (pr-str r))\n"
       "  (println \"PROGRAM-MS\" (double (/ (- t1 t0) 1000000.0)))))\n"
       (if cold? "(__go)\n" "(do (__work) (dotimes [_ 5] (__go)))\n")))

(defn run-jvm [src cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.clj" (wrap src "(System/nanoTime)" cold?))
  (shell {:out :string :err :string :continue true}
         "clojure" "-M" "target/bench-programs/wrap.clj"))

(defn run-jank-jit [src cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.jank"
        (wrap src "(cpp/jank.runtime.current_time)" cold?))
  (shell {:out :string :err :string :continue true}
         "jank" "run" "target/bench-programs/wrap.jank"))

(defn parse-run [proc]
  (let [out (:out proc)
        lines (str/split-lines out)
        results (keep #(when (str/starts-with? % "PROGRAM-RESULT ") (subs % 15)) lines)
        ms (keep #(when (str/starts-with? % "PROGRAM-MS ") (Double/parseDouble (subs % 12))) lines)]
    {:exit (:exit proc)
     :result (last results)
     :ms (vec ms)
     :err (str/trim (or (:err proc) ""))}))

(defn median [xs]
  (let [s (vec (sort xs))]
    (nth s (quot (count s) 2))))

(defn time-target [t src]
  (let [runner (case (:id t)
                 "jvm" run-jvm
                 "jank-jit" run-jank-jit
                 nil)]
    (if-not runner
      {:status :unsupported}
      (let [cold (parse-run (runner src true))
            warm (parse-run (runner src false))]
        {:status :ran
         :cold cold
         :warm warm
         :median (when (= 5 (count (:ms warm))) (median (:ms warm)))}))))

(defn jvm-result [src]
  (:result (parse-run (run-jvm src true))))

(defn report-one [t p expected]
  (let [id (name (:id p))]
    (cond
      (not (fs/exists? (:file p)))
      (println (str "  PROGRAM " id " MISSING-FILE"))
      (reason t)
      (println (str "  PROGRAM " id " ABSENT " (reason t)))
      (not (#{ "jvm" "jank-jit"} (:id t)))
      (println (str "  PROGRAM " id " NOT-TIMED runner not written"))
      :else
      (let [r (time-target t (slurp (:file p)))]
        (if-not (= :ran (:status r))
          (println (str "  PROGRAM " id " NOT-TIMED"))
          (let [got (:result (:warm r))
                same? (= got expected)]
            (println (str "  PROGRAM " id
                          " COLD-MS " (first (:ms (:cold r)))
                          " WARM-MS " (pr-str (:ms (:warm r)))
                          " MEDIAN-MS " (:median r)
                          (if same? " SAME" (str " DIFFER expected " expected " got " got))
                          (when-not (zero? (:exit (:warm r))) (str " EXIT " (:exit (:warm r))))))))))))

(defn report-target [t]
  (println (str "TARGET " (:id t)
                (if (reason t) (str " ABSENT " (reason t)) " present")))
  (when-not (reason t)
    (doseq [p programs]
      (if (and (fs/exists? (:file p)) (#{"jvm" "jank-jit"} (:id t)))
        (let [expected (jvm-result (slurp (:file p)))]
          (println (str "  JVM-RESULT " (name (:id p)) " " expected))
          (report-one t p expected))
        (report-one t p nil)))))

(defn -main [& args]
  (let [arg (first args)]
    (cond
      (or (nil? arg) (= arg "help"))
      (do (println "usage: bb bench:programs <target>|all")
          (println (str "targets: " (str/join " " (map :id targets)) " all")))
      (= arg "all")
      (doseq [t targets] (report-target t))
      :else
      (if-let [t (first (filter #(= (:id %) arg) targets))]
        (report-target t)
        (do (println (str "UNKNOWN " arg))
            (System/exit 1))))))

(apply -main *command-line-args*)
