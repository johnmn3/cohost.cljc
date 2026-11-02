;; jank-FAST Step 8. bb bench:programs <target>|all
;; Each timed call reads its argument from a volatile filled from the process
;; arguments, then parses it inside -run, and adds the result into a volatile.
;; Cold: one call in a fresh process. Warm: one untimed call, then five timed
;; calls, median is the middle value.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

;; JANK_BIN=<path> runs the jank targets on that binary (default: jank on
;; PATH). Its AOT objects live under their own roots (suffix: sha256 of the
;; binary's real path, 12 hex), so the apt jank and a source build never load
;; each other's objects.
(def jank-bin
  (let [b (System/getenv "JANK_BIN")]
    (if (str/blank? b) "jank" b)))

(def jank-tag
  (if (= "jank" jank-bin)
    ""
    (str "-" (subs (-> (shell {:out :string :in (str (fs/real-path jank-bin))} "sha256sum")
                       :out str/trim)
                   0 12))))

(def programs
  [{:id :fib :file "bench/programs/fib.clj" :arg "30"}
   {:id :fib-hinted :file "bench/programs/fib_hinted.clj" :arg "30" :hinted? true}
   {:id :tak :file "bench/programs/tak.clj" :arg "18 12 6"}
   {:id :tak-hinted :file "bench/programs/tak_hinted.clj" :arg "18 12 6" :hinted? true}
   {:id :nbody :file "bench/programs/nbody.clj" :arg "200"}
   {:id :nbody-hinted :file "bench/programs/nbody_hinted.clj" :arg "200" :hinted? true}
   {:id :spectral-norm :file "bench/programs/spectral_norm.clj" :arg "40"}
   {:id :spectral-norm-hinted :file "bench/programs/spectral_norm_hinted.clj" :arg "40" :hinted? true}
   {:id :mandelbrot :file "bench/programs/mandelbrot.clj" :arg "32"}
   {:id :mandelbrot-hinted :file "bench/programs/mandelbrot_hinted.clj" :arg "32" :hinted? true}
   {:id :binary-trees :file "bench/programs/binary_trees.clj" :arg "8"}
   {:id :binary-trees-hinted :file "bench/programs/binary_trees_hinted.clj" :arg "8" :hinted? true}
   {:id :fannkuch :file "bench/programs/fannkuch.clj" :arg "7"}
   {:id :fannkuch-hinted :file "bench/programs/fannkuch_hinted.clj" :arg "7" :hinted? true}
   {:id :nsieve :file "bench/programs/nsieve.clj" :arg "12"}
   {:id :nsieve-hinted :file "bench/programs/nsieve_hinted.clj" :arg "12" :hinted? true}
   {:id :k-nucleotide :file "bench/programs/k_nucleotide.clj" :arg "2000"}
   {:id :pipeline :file "bench/programs/pipeline.clj" :arg "2000"}
   {:id :dispatch :file "bench/programs/dispatch.clj" :arg "20000"}
   {:id :json :file "bench/programs/json.clj" :arg "20"}
   {:id :first-output :file "bench/programs/first_output.clj" :wall true}])

(def targets
  [{:id "jvm" :exe "clojure"}
   {:id "jvm-cohost" :exe "clojure"}
   {:id "jank-jit" :exe jank-bin}
   {:id "jank-aot" :exe jank-bin}
   {:id "jank" :exe jank-bin}
   {:id "jolt" :exe "jolt"}
   {:id "jolt-cohost" :exe "jolt"}
   {:id "lpy" :exe "basilisp"}
   {:id "lpy-cohost" :exe "basilisp"}
   {:id "cljs" :exe "node"}
   {:id "cljs-cohost" :exe "node"}
   {:id "browser" :exe "node"}
   {:id "browser-cohost" :exe "node"}
   {:id "cljw" :exe "cljw"}
   {:id "cljw-cohost" :exe "cljw"}
   {:id "phel" :exe "phel"}
   {:id "phel-cohost" :exe "phel"}
   {:id "lg" :exe "lg"}
   {:id "lg-cohost" :exe "lg"}
   {:id "cljrs" :exe "cljrs"}
   {:id "cljrs-cohost" :exe "cljrs"}])

(def timed-ids #{"jvm" "jvm-cohost" "jank-jit" "jank-aot" "jank" "cljrs" "cljrs-cohost"
                 "jolt" "jolt-cohost" "lpy" "lpy-cohost" "cljw" "cljw-cohost"
                 "phel" "phel-cohost" "lg" "lg-cohost" "cljs" "cljs-cohost"
                 "browser" "browser-cohost"})

(defn reason [t]
  (cond
    (:blocked t) (:blocked t)
    (not (fs/which (:exe t))) (str "no " (:exe t) " on PATH")
    :else nil))

(defn harness [src clock script?]
  (str src "\n"
       "(def __input (volatile! nil))\n"
       "(def __sink (volatile! 0))\n"
       "(defn __go []\n"
       "  (let [s @__input\n"
       "        t0 " clock "\n"
       "        r (-run s)\n"
       "        t1 " clock "]\n"
       "    (vreset! __sink (+ @__sink (if (number? r) r (count (str r)))))\n"
       "    (println \"PROGRAM-RESULT\" (pr-str r))\n"
       "    (println \"PROGRAM-MS\" (double (/ (- t1 t0) 1000000.0)))))\n"
       "(defn -main [& args]\n"
       "  (vreset! __input (first args))\n"
       "  (if (= (second args) \"cold\")\n"
       "    (__go)\n"
       "    (do (-run @__input) (dotimes [_ 5] (__go))))\n"
       "  (println \"PROGRAM-SINK\" @__sink))\n"
       (if script? "(apply -main *command-line-args*)\n" "")))

(defn run! [cmd]
  (apply shell {:out :string :err :string :continue true :timeout 300000} cmd))

(defn parse-run [proc]
  (let [out (or (:out proc) "")
        lines (str/split-lines out)
        results (vec (keep #(second (re-matches #"PROGRAM-RESULT (.*)" %)) lines))
        ms (vec (keep #(when-let [[_ n] (re-matches #"PROGRAM-MS (.*)" %)]
                         (Double/parseDouble (str/trim n))) lines))
        err (str/trim (or (:err proc) ""))]
    {:exit (:exit proc) :result (last results) :ms ms :err err :out out}))

(defn median [xs]
  (nth (vec (sort xs)) (quot (count xs) 2)))

(defn one-error [err]
  (let [lines (str/split-lines (or err ""))]
    (or (some (fn [line]
                (when-let [[_ m] (re-find #"=> ERR (.*)" line)]
                  (str/trim m)))
              lines)
        (some #(when (or (str/includes? % "error:")
                         (str/includes? % "Error")
                         (str/includes? % "Exception"))
                 (str/trim %))
              lines)
        (let [t (str/trim (or err ""))]
          (when (seq t) (subs t 0 (min 220 (count t))))))))

(defn run-jvm [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.clj"
        (harness src "(System/nanoTime)" true))
  (run! ["clojure" "-M" "target/bench-programs/wrap.clj" arg (if cold? "cold" "warm")]))

(defn run-jank-jit [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.jank"
        (harness src "(cpp/jank.runtime.current_time)" true))
  (run! [jank-bin "run" "target/bench-programs/wrap.jank" "--" arg (if cold? "cold" "warm")]))

(defn aot-dir [id src]
  (let [root (str "target/bench-programs/aot-" (name id) jank-tag)
        file (str root "/src/bench/prog.jank")
        stamp (str root "/stamp")
        body (harness src "(cpp/jank.runtime.current_time)" false)]
    (when-not (and (fs/exists? stamp) (= (slurp stamp) body) (seq (fs/glob root "**/bench/prog.o")))
      (fs/create-dirs (str root "/src/bench"))
      (spit file (str "(ns bench.prog)\n" body))
      (let [c (run! [jank-bin "--module-path" (str root "/src") "compile-module" "bench.prog"
                     "-O3" "--target-dir" root])]
        (when-not (and (zero? (:exit c)) (seq (fs/glob root "**/bench/prog.o")))
          (throw (ex-info "aot-compile" {:err (str (:err c) "\n" (:out c))}))))
      (spit stamp body))
    (let [cache (first (fs/glob root "_cache/*"))]
      {:cache (str cache) :src (str root "/src")})))

(defn run-jank-aot [id src arg cold?]
  (let [d (aot-dir id src)]
    (run! [jank-bin "--module-path" (str (:cache d) ":" (:src d))
           "run-main" "bench.prog" "--" arg (if cold? "cold" "warm")])))

(defn run-jank-cohost [src arg cold?]
  ;; cohost's jank boot has no load-file (it is a host var Clojure's RT
  ;; provides). --eval reads every form, so the program source is the argument.
  (let [dir (load-file "dev/perf/jank_aot_ensure.clj")
        mode (if cold? "cold" "warm")
        code (str (harness src "(cpp/jank.runtime.current_time)" false)
                  "(-main " (pr-str arg) " " (pr-str mode) ")")]
    (apply shell {:out :string :err :string :continue true :timeout 300000}
           [jank-bin "--module-path" (str dir ":src") "run-main" "cohost.run" "--"
            "--eval" code])))

(defn run-cljrs [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.clj"
        (harness src "(System/nanoTime)" false))
  (run! ["cljrs" "run" "target/bench-programs/wrap.clj" "--" arg (if cold? "cold" "warm")]))

(defn run-jvm-cohost [src arg cold?]
  (let [mode (if cold? "cold" "warm")
        code (str (harness src "(System/nanoTime)" false)
                  "(-main " (pr-str arg) " " (pr-str mode) ")")]
    (apply shell {:out :string :err :string :continue true :timeout 300000}
           ["clojure" "-M" "-m" "cohost.run" "--" "--eval" code])))

(defn run-cljrs-cohost [src arg cold?]
  (let [mode (if cold? "cold" "warm")
        code (str (harness src "(System/nanoTime)" false)
                  "(-main " (pr-str arg) " " (pr-str mode) ")")]
    (apply shell {:out :string :err :string :continue true :timeout 300000
                  :extra-env {"CLJRS_IR_THRESHOLD" "1" "CLJRS_NO_JIT" "1"}}
           ["cljrs" "run" "--src-path" "src" "--src-path" "target/cljrs-ns"
            "src/cohost/run.cljc" "--" "--eval" code])))

(defn cohost-source [src clock arg cold?]
  (str (harness src clock false)
       "(-main " (pr-str arg) " " (pr-str (if cold? "cold" "warm")) ")"))

(defn run-jolt [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.clj" (harness src "(System/nanoTime)" true))
  (run! ["jolt" "run" "target/bench-programs/wrap.clj" arg (if cold? "cold" "warm")]))

(defn run-jolt-cohost [src arg cold?]
  (run! ["jolt" "-Scp" "src" "-m" "cohost.run" "--eval"
         (cohost-source src "(System/nanoTime)" arg cold?)]))

(defn run-lpy [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  ;; Basilisp has no System/nanoTime. time/time_ns is Python's time.time_ns.
  (spit "target/bench-programs/wrap.clj"
        (str "(import time)\n" (harness src "(time/time_ns)" true)))
  (run! ["basilisp" "run" "target/bench-programs/wrap.clj" "--" arg (if cold? "cold" "warm")]))

(defn run-lpy-cohost [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/cohost.clj" (cohost-source src "(System/nanoTime)" arg cold?))
  (run! ["basilisp" "run" "-p" "src" "-c"
         "(require 'cohost.run) (cohost.run/-main \"--eval\" (slurp \"target/bench-programs/cohost.clj\"))"]))

(defn run-cljw [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.clj" (harness src "(System/nanoTime)" true))
  (run! ["cljw" "target/bench-programs/wrap.clj" arg (if cold? "cold" "warm")]))

(defn run-cljw-cohost [src arg cold?]
  (run! ["cljw" "-cp" "src" "-m" "cohost.run" "--eval"
         (cohost-source src "(System/nanoTime)" arg cold?)]))

(defn run-phel [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  ;; Phel needs a leading ns, passes args as *argv*, and has no System class.
  ;; php/microtime is seconds; scale it so the harness's / 1e6 yields milliseconds.
  (spit "target/bench-programs/wrap.phel"
        (str "(ns bench.prog)\n"
             (harness src "(* (php/microtime true) 1000000000.0)" false)
             "(apply -main *argv*)\n"))
  (run! ["phel" "run" "target/bench-programs/wrap.phel" arg (if cold? "cold" "warm")]))

(defn run-phel-cohost [src arg cold?]
  (run! ["phel" "run" "dev/phel_main.phel" "--" "--eval"
         (cohost-source src "(System/nanoTime)" arg cold?)]))

(defn run-lg [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.clj" (harness src "(System/nanoTime)" true))
  (run! ["lg" "target/bench-programs/wrap.clj" arg (if cold? "cold" "warm")]))

(defn run-lg-cohost [src arg cold?]
  (run! ["lg" "-source-paths" "src" "dev/lg_main.lg" "--" "--eval"
         (cohost-source src "(System/nanoTime)" arg cold?)]))

(defn ensure-cljs-node! []
  (when-not (fs/exists? "target/cljs-node/main.js")
    (let [c (run! ["clojure" "-M:cljs" "-m" "cljs.main" "-co" "cljs-node.edn" "-c"])]
      (when-not (zero? (:exit c))
        (throw (ex-info "cljs-node" {:err (str (:err c) "\n" (:out c))}))))))

(defn run-cljs [src arg cold?]
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wrap.cljs"
        (harness src "(* (js/Date.now) 1000000.0)" true))
  (run! ["clojure" "-M:cljs" "-m" "cljs.main" "-re" "node"
         "target/bench-programs/wrap.cljs" arg (if cold? "cold" "warm")]))

(defn run-cljs-cohost [src arg cold?]
  (ensure-cljs-node!)
  (run! ["node" "target/cljs-node/main.js" "--eval"
         (cohost-source src "(System/nanoTime)" arg cold?)]))

(defn run-browser [_src _arg _cold?]
  (throw (ex-info "browser"
                  {:err "cohost.run-browser only runs the battery or ?bench; the page has no --eval, and there is no own-core browser entry"})))

(defn runner-for [id]
  (case id
    "jvm" (fn [id src arg cold?] (run-jvm src arg cold?))
    "jvm-cohost" (fn [id src arg cold?] (run-jvm-cohost src arg cold?))
    "jank-jit" (fn [id src arg cold?] (run-jank-jit src arg cold?))
    "jank-aot" run-jank-aot
    "jank" (fn [id src arg cold?] (run-jank-cohost src arg cold?))
    "cljrs" (fn [id src arg cold?] (run-cljrs src arg cold?))
    "cljrs-cohost" (fn [id src arg cold?] (run-cljrs-cohost src arg cold?))
    "jolt" (fn [id src arg cold?] (run-jolt src arg cold?))
    "jolt-cohost" (fn [id src arg cold?] (run-jolt-cohost src arg cold?))
    "lpy" (fn [id src arg cold?] (run-lpy src arg cold?))
    "lpy-cohost" (fn [id src arg cold?] (run-lpy-cohost src arg cold?))
    "cljw" (fn [id src arg cold?] (run-cljw src arg cold?))
    "cljw-cohost" (fn [id src arg cold?] (run-cljw-cohost src arg cold?))
    "phel" (fn [id src arg cold?] (run-phel src arg cold?))
    "phel-cohost" (fn [id src arg cold?] (run-phel-cohost src arg cold?))
    "lg" (fn [id src arg cold?] (run-lg src arg cold?))
    "lg-cohost" (fn [id src arg cold?] (run-lg-cohost src arg cold?))
    "cljs" (fn [id src arg cold?] (run-cljs src arg cold?))
    "cljs-cohost" (fn [id src arg cold?] (run-cljs-cohost src arg cold?))
    "browser" (fn [id src arg cold?] (run-browser src arg cold?))
    "browser-cohost" (fn [id src arg cold?] (run-browser src arg cold?))
    nil))

(defn wall-aot-cmd []
  (let [root (str "target/bench-programs/aot-wall" jank-tag)
        file (str root "/src/bench/wall.jank")
        body "(ns bench.wall)\n(defn -main [& _]\n  (println \"ok\"))\n"
        stamp (str root "/stamp")]
    (when-not (and (fs/exists? stamp) (= (slurp stamp) body)
                   (seq (fs/glob root "**/bench/wall.o")))
      (fs/create-dirs (str root "/src/bench"))
      (spit file body)
      (let [c (run! [jank-bin "--module-path" (str root "/src") "compile-module" "bench.wall"
                     "-O3" "--target-dir" root])]
        (when-not (and (zero? (:exit c)) (seq (fs/glob root "**/bench/wall.o")))
          (throw (ex-info "aot-wall" {:err (str (:err c) "\n" (:out c))}))))
      (spit stamp body))
    (let [cache (str (first (fs/glob root "_cache/*")))]
      [jank-bin "--module-path" (str cache ":" root "/src") "run-main" "bench.wall"])))

(defn wall-cljrs-cmd []
  (fs/create-dirs "target/bench-programs")
  (spit "target/bench-programs/wall.clj" "(defn -main [& _]\n  (println \"ok\"))\n")
  ["cljrs" "run" "target/bench-programs/wall.clj"])

(defn wall-cmd [id file]
  (case id
    "jvm" ["clojure" "-M" file]
    "jank-jit" [jank-bin "run" file]
    "jank-aot" (wall-aot-cmd)
    "jank" (let [dir (load-file "dev/perf/jank_aot_ensure.clj")]
             [jank-bin "--module-path" (str dir ":src") "run-main" "cohost.run" "--"
              "--eval" "(println \"ok\")"])
    "cljrs" (wall-cljrs-cmd)
    "jvm-cohost" ["clojure" "-M" "-m" "cohost.run" "--" "--eval" "(println \"ok\")"]
    "cljrs-cohost" ["cljrs" "run" "--src-path" "src" "--src-path" "target/cljrs-ns"
                    "src/cohost/run.cljc" "--" "--eval" "(println \"ok\")"]
    "jolt" ["jolt" "run" file]
    "jolt-cohost" ["jolt" "-Scp" "src" "-m" "cohost.run" "--eval" "(println \"ok\")"]
    "lpy" ["basilisp" "run" file]
    "lpy-cohost" ["basilisp" "run" "-p" "src" "-c"
                  "(require 'cohost.run) (cohost.run/-main \"--eval\" \"(println \\\"ok\\\")\")"]
    "cljw" ["cljw" file]
    "cljw-cohost" ["cljw" "-cp" "src" "-m" "cohost.run" "--eval" "(println \"ok\")"]
    "phel" ["phel" "run" file]
    "phel-cohost" ["phel" "run" "dev/phel_main.phel" "--" "--eval" "(println \"ok\")"]
    "lg" ["lg" file]
    "lg-cohost" ["lg" "-source-paths" "src" "dev/lg_main.lg" "--" "--eval" "(println \"ok\")"]
    "cljs" ["clojure" "-M:cljs" "-m" "cljs.main" "-re" "node" file]
    "cljs-cohost" (do (ensure-cljs-node!)
                      ["node" "target/cljs-node/main.js" "--eval" "(println \"ok\")"])
    "browser" (throw (ex-info "browser" {:err "cohost.run-browser only runs the battery or ?bench; the page has no --eval, and there is no own-core browser entry"}))
    "browser-cohost" (throw (ex-info "browser" {:err "cohost.run-browser only runs the battery or ?bench; the page has no --eval"}))
    nil))

(defn wall-run [id file]
  (try
    (if-let [cmd (wall-cmd id file)]
      (let [t0 (System/nanoTime)
            proc (apply shell (merge {:out :string :err :string :continue true :timeout 300000}
                                      (when (= id "cljrs-cohost")
                                        {:extra-env {"CLJRS_IR_THRESHOLD" "1" "CLJRS_NO_JIT" "1"}}))
                        cmd)
            ms (double (/ (- (System/nanoTime) t0) 1000000.0))]
        {:exit (:exit proc) :ms ms :out (or (:out proc) "") :err (or (:err proc) "")})
      nil)
    (catch Exception e
      {:exit 1 :ms nil :out "" :err (or (some-> e ex-data :err) (.getMessage e))})))

(defn report-wall [t p]
  (let [id (:id t)
        runner-ok (#{"jvm" "jvm-cohost" "jank-jit" "jank-aot" "jank" "cljrs" "cljrs-cohost"
                     "jolt" "jolt-cohost" "lpy" "lpy-cohost" "cljw" "cljw-cohost"
                     "phel" "phel-cohost" "lg" "lg-cohost" "cljs" "cljs-cohost"
                     "browser" "browser-cohost"} id)]
    (if-not runner-ok
      (println (str "  PROGRAM first-output NOT-TIMED runner not written"))
      (let [cold (wall-run id (:file p))
            _ (wall-run id (:file p))
            warms (vec (repeatedly 5 #(wall-run id (:file p))))]
        (if (nil? cold)
          (println "  PROGRAM first-output NOT-TIMED runner not written")
          (if-not (str/includes? (:out cold) "ok")
          (println (str "  PROGRAM first-output ABSENT " (one-error (:err cold))))
          (println (str "  PROGRAM first-output COLD-MS " (:ms cold)
                        " WARM-MS " (pr-str (mapv :ms warms))
                        " MEDIAN-MS " (median (map :ms warms))
                        " SAME"))))))))

(def jvm-results-file "target/bench-programs/jvm-results.edn")

(defn load-jvm-results []
  (when (fs/exists? jvm-results-file)
    (read-string (slurp jvm-results-file))))

(defn save-jvm-result! [id result]
  (fs/create-dirs "target/bench-programs")
  (spit jvm-results-file (pr-str (assoc (or (load-jvm-results) {}) id result))))

(defn measure [t p]
  (try
    (let [src (slurp (:file p))
          arg (:arg p)
          run (runner-for (:id t))
          cold (parse-run (run (:id p) src arg true))]
      (if (or (not (zero? (:exit cold))) (nil? (:result cold)))
        {:fail (or (one-error (:err cold))
                   (one-error (:out cold))
                   (str "exit " (:exit cold)))}
        (let [warm (parse-run (run (:id p) src arg false))]
          {:result (:result cold)
           :cold (first (:ms cold))
           :warm (:ms warm)
           :median (when (= 5 (count (:ms warm))) (median (:ms warm)))
           :warm-result (:result warm)
           :fail (when-not (zero? (:exit warm))
                   (or (one-error (:err warm)) (one-error (:out warm))))})))
    (catch Exception e
      {:fail (or (some-> e ex-data :err one-error) (.getMessage e))})))

(defn report-program [t p jvm-results]
  (let [name (name (:id p))]
    (cond
      (not (fs/exists? (:file p)))
      (println (str "  PROGRAM " name " MISSING-FILE"))
      (:wall p)
      (report-wall t p)
      (not (timed-ids (:id t)))
      (println (str "  PROGRAM " name " NOT-TIMED runner not written"))
      :else
      (let [m (measure t p)]
        (if (:fail m)
          (println (str "  PROGRAM " name " ABSENT " (:fail m)))
          (let [expected (get jvm-results (:id p))
                same? (or (= (:id t) "jvm") (= (:result m) expected))]
            (when (= (:id t) "jvm")
              (save-jvm-result! (:id p) (:result m)))
            (println (str "  PROGRAM " name
                          " COLD-MS " (:cold m)
                          " WARM-MS " (pr-str (:warm m))
                          " MEDIAN-MS " (:median m)
                          (if same?
                            (str " SAME " (:result m))
                            (str " DIFFER expected " expected " got " (:result m)))))))))))

(defn report-target [t]
  (if-let [why (reason t)]
    (println (str "TARGET " (:id t) " ABSENT " why))
    (do (println (str "TARGET " (:id t) " present"))
        (let [base (when (not= (:id t) "jvm") (or (load-jvm-results) {}))]
          (doseq [p programs]
            (report-program t p base))))))

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
