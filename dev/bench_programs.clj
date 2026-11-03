;; jank-FAST Step 8. The program suite against JVM Clojure.
;;
;;   bb bench:programs <target> [program[:size],...]   measure, print, record
;;   bb bench:programs all [program[:size],...]        every target, in order
;;   bb bench:programs table [target ...]              doc/PERF.md table from the records
;;   bb bench:programs sentinels [table]               the sentinel rows: measure jank-aot and jank
;;                                                      (jvm only where it has no record), print their table;
;;                                                      `table` prints only. Honors JANK_BIN.
;;
;; Method (C-1, 2026-11-02). One process per program and target. Inside it:
;; - the first call of -run is COLD-MS (the first call in a fresh process);
;; - warm-up continues until the median of the last 5 calls moves under 2%
;;   from the median of the 5 before the newest call, or 20 calls, or 10 s
;;   of warm-up, whichever comes first (the cold call counts);
;; - then 10 timed calls: MEDIAN-MS (the mean of the middle two), MIN-MS, MAX-MS.
;; The clock is each core's own: System/nanoTime on the JVM; on a cohost
;; target the program's (System/nanoTime) is rewritten by cohost to the shim's
;; System-nanoTime, which is the adapter's :nano-time capability; other own
;; cores use their native clocks (see `clocks`). The harness's inner loop
;; binds no name of its outer loop (jank 0.1 fails C++ codegen on that:
;; `use of undeclared identifier 'sNN'`). It uses no volatile
;; and no atom: the input is a string argument, the result of every call is
;; folded into a sink carried by the loop and printed at the end, and every
;; call's result is checked against the first call's.
;; first-output is process wall time to the line `ok`: one cold process, one
;; discarded process, then 10 processes (median, min-max).
;; Records: target/bench-programs/results/<target>.edn (program -> record).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

;; JANK_BIN=<path> runs the jank targets on that binary (default: jank on
;; PATH). Its AOT objects live under their own roots (suffix: sha256 of the
;; binary's real path, 12 hex), so the apt jank and a source build never load
;; each other's objects.
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/perf/sha256.clj"))

(def jank-bin
  (let [b (System/getenv "JANK_BIN")]
    (if (str/blank? b) "jank" b)))

(def jank-tag
  (if (= "jank" jank-bin)
    ""
    (str "-" (subs (cohost.dev.sha256/sha256-hex (str (fs/real-path jank-bin))) 0 12))))

;; Sizes: one JVM call takes 100-1000 ms after warm-up (measured 2026-11-02).
(def programs
  [{:id :fib :file "bench/programs/fib.clj" :arg "36"}
   {:id :fib-hinted :file "bench/programs/fib_hinted.clj" :arg "36" :hinted? true}
   {:id :tak :file "bench/programs/tak.clj" :arg "30 20 10"}
   {:id :tak-hinted :file "bench/programs/tak_hinted.clj" :arg "30 20 10" :hinted? true}
   {:id :nbody :file "bench/programs/nbody.clj" :arg "50000"}
   {:id :nbody-hinted :file "bench/programs/nbody_hinted.clj" :arg "50000" :hinted? true}
   {:id :spectral-norm :file "bench/programs/spectral_norm.clj" :arg "600"}
   {:id :spectral-norm-hinted :file "bench/programs/spectral_norm_hinted.clj" :arg "600" :hinted? true}
   {:id :mandelbrot :file "bench/programs/mandelbrot.clj" :arg "1500"}
   {:id :mandelbrot-hinted :file "bench/programs/mandelbrot_hinted.clj" :arg "1500" :hinted? true}
   {:id :binary-trees :file "bench/programs/binary_trees.clj" :arg "18"}
   {:id :binary-trees-hinted :file "bench/programs/binary_trees_hinted.clj" :arg "18" :hinted? true}
   ;; X1: depth 14 as a second size (GC visible); the i32-wrap sentinel; sort then a walk to the middle.
   {:id :binary-trees-14 :file "bench/programs/binary_trees.clj" :arg "14" :sentinel? true}
   {:id :binary-trees-14-hinted :file "bench/programs/binary_trees_hinted.clj" :arg "14" :hinted? true :sentinel? true}
   {:id :sum-overflow :file "bench/programs/sum_overflow.clj" :arg "1000000" :sentinel? true}
   {:id :sum-overflow-hinted :file "bench/programs/sum_overflow_hinted.clj" :arg "1000000" :hinted? true :sentinel? true}
   {:id :sort :file "bench/programs/sort.clj" :arg "100000" :sentinel? true}
   ;; X1-2: a lazy pipeline over a long range; get/assoc on a 16-key map of long keywords.
   {:id :lazy-pipeline :file "bench/programs/lazy_pipeline.clj" :arg "1000000" :sentinel? true}
   {:id :keyword-map :file "bench/programs/keyword_map.clj" :arg "1000000" :sentinel? true}
   {:id :fannkuch :file "bench/programs/fannkuch.clj" :arg "8"}
   {:id :fannkuch-hinted :file "bench/programs/fannkuch_hinted.clj" :arg "8" :hinted? true}
   {:id :nsieve :file "bench/programs/nsieve.clj" :arg "20"}
   {:id :nsieve-hinted :file "bench/programs/nsieve_hinted.clj" :arg "20" :hinted? true}
   {:id :k-nucleotide :file "bench/programs/k_nucleotide.clj" :arg "2000000"}
   {:id :pipeline :file "bench/programs/pipeline.clj" :arg "500000"}
   {:id :dispatch :file "bench/programs/dispatch.clj" :arg "5000000"}
   {:id :json :file "bench/programs/json.clj" :arg "30000"}
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

;; Each own core's clock, in nanoseconds. Every cohost target uses
;; (System/nanoTime), which cohost turns into the adapter's :nano-time.
(def clocks
  {"jvm" "(System/nanoTime)"
   "jank-jit" "(cpp/jank.runtime.current_time)"
   "jank-aot" "(cpp/jank.runtime.current_time)"
   "jolt" "(System/nanoTime)"
   "lpy" "(time/perf_counter_ns)"
   "cljs" "(* (js/performance.now) 1000000.0)"
   "browser" "(* (js/performance.now) 1000000.0)"
   "cljw" "(System/nanoTime)"
   "phel" "(php/hrtime true)"
   "lg" "(System/nanoTime)"
   "cljrs" "(System/nanoTime)"})

(defn clock [target-id]
  (or (clocks target-id) "(System/nanoTime)"))

(def timeout-ms 1800000)

(defn reason [t]
  (when-not (fs/which (:exe t)) (str "no " (:exe t) " on PATH")))

(defn harness
  "The program's source plus the timing loop. `main?`: also define -main
   (process arguments: the size string). Portable: loop/recur, vectors,
   sort, nth, count, str; no volatile, atom or host call but the clock."
  [src clock main?]
  (str src "\n"
       "(defn __now [] " clock ")\n"
       "(defn __ms [t0 t1] (/ (- t1 t0) 1000000.0))\n"
       "(defn __sinkv [acc r] (+ acc (if (number? r) r (count (str r)))))\n"
       "(defn __med5 [ws]\n"
       "  (let [n (count ws)]\n"
       "    (nth (sort [(nth ws (- n 1)) (nth ws (- n 2)) (nth ws (- n 3)) (nth ws (- n 4)) (nth ws (- n 5))]) 2)))\n"
       "(defn __bench [s]\n"
       "  (let [t-start (__now)\n"
       "        r0 (-run s)\n"
       "        t-cold (__now)\n"
       "        r0s (pr-str r0)]\n"
       "    (println \"PROGRAM-RESULT\" r0s)\n"
       "    (println \"PROGRAM-COLD\" (__ms t-start t-cold))\n"
       "    (loop [ws [(__ms t-start t-cold)] prev nil acc (__sinkv 0 r0) bad 0]\n"
       "      (let [n (count ws)\n"
       "            m (if (< n 5) nil (__med5 ws))\n"
       "            d (if (or (nil? m) (nil? prev) (zero? prev)) 1.0 (/ (- m prev) prev))\n"
       "            d (if (< d 0) (- 0 d) d)]\n"
       "        (if (or (>= n 20) (> (__ms t-start (__now)) 10000.0) (< d 0.02))\n"
       "          (do (println \"PROGRAM-WARMUP\" n)\n"
       "              (loop [i 0 sink acc wrong bad]\n"
       "                (if (= i 10)\n"
       "                  (do (println \"PROGRAM-BAD\" wrong)\n"
       "                      (println \"PROGRAM-SINK\" sink))\n"
       "                  (let [t0 (__now) r (-run s) t1 (__now)]\n"
       "                    (println \"PROGRAM-MS\" (__ms t0 t1))\n"
       "                    (recur (+ i 1) (__sinkv sink r) (if (= (pr-str r) r0s) wrong (+ wrong 1)))))))\n"
       "          (let [t0 (__now) r (-run s) t1 (__now)]\n"
       "            (recur (conj ws (__ms t0 t1)) m (__sinkv acc r)\n"
       "                   (if (= (pr-str r) r0s) bad (+ bad 1)))))))))\n"
       (if main? "(defn -main [& args] (__bench (first args)))\n" "")))

(defn cohost-source [src clock arg]
  (str (harness src clock false) "(__bench " (pr-str arg) ")\n"))

(defn run! [cmd & [opts]]
  (apply shell (merge {:out :string :err :string :continue true :timeout timeout-ms} opts) cmd))

(def pid (.pid (java.lang.ProcessHandle/current)))

(defn tmp
  "A wrapper file of this driver process: two drivers never share one."
  [f]
  (fs/create-dirs (str "target/bench-programs/" pid))
  (str "target/bench-programs/" pid "/" f))

;; ---- runners: (fn [program-id src arg]) -> process result

(defn run-jvm [id src arg]
  (let [f (tmp (str (name id) ".clj"))]
    (spit f (str (harness src (clock "jvm") true) "(apply -main *command-line-args*)\n"))
    (run! ["clojure" "-M" f arg])))

(defn run-jvm-cohost [_ src arg]
  (run! ["clojure" "-M" "-m" "cohost.run" "--" "--eval" (cohost-source src (clock "jvm-cohost") arg)]))

(defn run-jank-jit [id src arg]
  (let [f (tmp (str (name id) ".jank"))]
    (spit f (str (harness src (clock "jank-jit") true) "(apply -main *command-line-args*)\n"))
    (run! [jank-bin "-O3" "run" f "--" arg])))

(defn aot-dir [id src]
  (let [root (str "target/bench-programs/aot-" (name id) jank-tag)
        file (str root "/src/bench/prog.jank")
        stamp (str root "/stamp")
        body (str "(ns bench.prog)\n" (harness src (clock "jank-aot") true))]
    (when-not (and (fs/exists? stamp) (= (slurp stamp) body) (seq (fs/glob root "**/bench/prog.o")))
      (fs/delete-tree root)
      (fs/create-dirs (str root "/src/bench"))
      (spit file body)
      (let [c (run! [jank-bin "--module-path" (str root "/src") "compile-module" "bench.prog"
                     "-O3" "--target-dir" root])]
        (when-not (and (zero? (:exit c)) (seq (fs/glob root "**/bench/prog.o")))
          (throw (ex-info "aot-compile" {:err (str (:err c) "\n" (:out c))}))))
      (spit stamp body))
    {:cache (str (first (fs/glob root "_cache/*"))) :src (str root "/src")}))

(defn run-jank-aot [id src arg]
  (let [d (aot-dir id src)]
    (run! [jank-bin "--module-path" (str (:cache d) ":" (:src d)) "run-main" "bench.prog" "--" arg])))

(defn jank-cohost-dir []
  ;; cohost's own namespaces AOT (G-12), rebuilt when stale.
  (load-file "dev/perf/jank_aot_ensure.clj"))

(def jank-boot
  "Which boot the last cohost-on-jank command used: :snapshot or :aot."
  (atom nil))

(def jank-env
  "The environment the last cohost-on-jank command needs (D-4's GC setting)."
  (atom nil))

(defn jank-cohost-cmd
  "-> {:cmd [...] :env {...}}: cohost.run --eval code on jank. With lane D's
   snapshot entry in the tree (dev/perf/jank_snapshot_cli.clj, D-5): the
   snapshot when it is fresh, else the AOT objects, as that entry decides
   (and its GC env). Without it: cohost's AOT objects (G-12)."
  [code]
  (if (fs/exists? "dev/perf/jank_snapshot_cli.clj")
    (do (load-file "dev/perf/jank_snapshot_cli.clj")
        (let [repo (str (fs/canonicalize "."))
              {:keys [module-path snapshot? env]} ((resolve 'jank-cli-launch) repo)]
          (reset! jank-boot (if snapshot? :snapshot :aot))
          (reset! jank-env env)
          {:env env
           :cmd (if snapshot?
                  [jank-bin "-O3" "--module-path" module-path "run" (str repo "/dev/perf/jank_snapshot_run.jank")
                   "--" "--eval" code]
                  [jank-bin "-O3" "--module-path" module-path "run-main" "cohost.run" "--" "--eval" code])}))
    (do (reset! jank-boot :aot)
        (reset! jank-env nil)
        {:cmd [jank-bin "-O3" "--module-path" (str (jank-cohost-dir) ":src") "run-main" "cohost.run" "--" "--eval" code]})))

(defn run-jank-cohost [_ src arg]
  ;; cohost's jank boot has no load-file; --eval reads every form of its argument.
  (let [{:keys [cmd env]} (jank-cohost-cmd (cohost-source src (clock "jank") arg))]
    (run! cmd (when env {:extra-env env}))))

(defn run-cljrs [id src arg]
  (let [f (tmp (str (name id) ".clj"))]
    (spit f (harness src (clock "cljrs") true))
    (run! ["cljrs" "run" f "--" arg])))

(def cljrs-env {:extra-env {"CLJRS_IR_THRESHOLD" "1" "CLJRS_NO_JIT" "1"}})

(defn run-cljrs-cohost [_ src arg]
  (run! ["cljrs" "run" "--src-path" "src" "--src-path" "target/cljrs-ns"
         "src/cohost/run.cljc" "--" "--eval" (cohost-source src (clock "cljrs-cohost") arg)]
        cljrs-env))

(defn run-jolt [id src arg]
  (let [f (tmp (str (name id) ".clj"))]
    (spit f (str (harness src (clock "jolt") true) "(apply -main *command-line-args*)\n"))
    (run! ["jolt" "run" f arg])))

(defn run-jolt-cohost [_ src arg]
  (run! ["jolt" "-Scp" "src" "-m" "cohost.run" "--eval" (cohost-source src (clock "jolt-cohost") arg)]))

(defn run-lpy [id src arg]
  ;; basilisp has no System/nanoTime: Python's time.perf_counter_ns.
  (let [f (tmp (str (name id) ".clj"))]
    (spit f (str "(import time)\n" (harness src (clock "lpy") true) "(apply -main *command-line-args*)\n"))
    (run! ["basilisp" "run" f "--" arg])))

(defn run-lpy-cohost [id src arg]
  (let [f (tmp (str (name id) "-cohost.clj"))]
    (spit f (cohost-source src (clock "lpy-cohost") arg))
    (run! ["basilisp" "run" "-p" "src" "-c"
           (str "(require 'cohost.run) (cohost.run/-main \"--eval\" (slurp " (pr-str f) "))")])))

(defn run-cljw [id src arg]
  (let [f (tmp (str (name id) ".clj"))]
    (spit f (str (harness src (clock "cljw") true) "(apply -main *command-line-args*)\n"))
    (run! ["cljw" f arg])))

(defn run-cljw-cohost [_ src arg]
  (run! ["cljw" "-cp" "src" "-m" "cohost.run" "--eval" (cohost-source src (clock "cljw-cohost") arg)]))

(defn run-phel [id src arg]
  ;; Phel needs a leading ns and passes args as *argv*.
  (let [f (tmp (str (name id) ".phel"))]
    (spit f (str "(ns bench.prog)\n" (harness src (clock "phel") true) "(apply -main *argv*)\n"))
    (run! ["phel" "run" f arg])))

(defn run-phel-cohost [_ src arg]
  (run! ["phel" "run" "dev/phel_main.phel" "--" "--eval" (cohost-source src (clock "phel-cohost") arg)]))

(defn run-lg [id src arg]
  (let [f (tmp (str (name id) ".clj"))]
    (spit f (str (harness src (clock "lg") true) "(apply -main *command-line-args*)\n"))
    (run! ["lg" f arg])))

(defn run-lg-cohost [_ src arg]
  (run! ["lg" "-source-paths" "src" "dev/lg_main.lg" "--" "--eval" (cohost-source src (clock "lg-cohost") arg)]))

(defn ensure-cljs-node! []
  (when-not (fs/exists? "target/cljs-node/main.js")
    (let [c (run! ["clojure" "-M:cljs" "-m" "cljs.main" "-co" "cljs-node.edn" "-c"])]
      (when-not (zero? (:exit c))
        (throw (ex-info "cljs-node" {:err (str (:err c) "\n" (:out c))}))))))

(defn cljs-build
  "Compile body (namespace bench.prog) as a Node program: -t node -O simple,
   the way a cljs user ships one. Cached by body under root. -> main.js path."
  [root body & [target]]
  (let [stamp (str root "/stamp")
        js (str root "/main.js")]
    (when-not (and (fs/exists? stamp) (= (slurp stamp) body) (fs/exists? js))
      (fs/delete-tree root)
      (fs/create-dirs (str root "/src/bench"))
      (spit (str root "/src/bench/prog.cljs") body)
      (let [c (run! ["clojure" "-Sdeps" (pr-str {:paths [(str root "/src")]}) "-M:cljs"
                     "-m" "cljs.main" "-t" (name (or target :node)) "-O" "simple"
                     "-d" (str root "/out") "-o" js "-c" "bench.prog"])]
        (when-not (and (zero? (:exit c)) (fs/exists? js))
          (throw (ex-info "cljs-compile" {:err (str (:err c) "\n" (:out c))}))))
      (spit stamp body))
    js))

(defn run-cljs
  "ClojureScript's own core, compiled (cljs-build), run by node. (Script
   mode, cljs.main -re node file, evaluates form by form through the REPL
   and fails on defprotocol: `Can't change/establish root binding of:
   *unchecked-if* with set`.)"
  [id src arg]
  (run! ["node" (cljs-build (str "target/bench-programs/cljs-" (name id))
                            (str "(ns bench.prog)\n" (harness src (clock "cljs") true)
                                 "(set! *main-cli-fn* -main)\n"))
         arg]))

(defn run-cljs-cohost [_ src arg]
  (ensure-cljs-node!)
  (run! ["node" "target/cljs-node/main.js" "--eval" (cohost-source src (clock "cljs-cohost") arg)]))

(def browser-cohost-why
  "no runner: cohost.run-browser (src/cohost/run_browser.cljs) runs the battery or ?bench only; the page has no --eval")

(def chromium-js
  ;; Load page.html in headless Chromium (Playwright, pre-installed), echo the
  ;; page's console to stdout, stop at PROGRAM-SINK, a page error, or the timeout.
  "const {chromium} = require('playwright');
(async () => {
  const [page_url, timeout] = [process.argv[2], Number(process.argv[3])];
  const browser = await chromium.launch();
  const page = await browser.newPage();
  let done;
  const finished = new Promise(r => done = r);
  page.on('console', m => { console.log(m.text()); if (m.text().startsWith('PROGRAM-SINK') || m.text() === 'ok') done(0); });
  page.on('pageerror', e => { console.error('pageerror: ' + e.message); done(1); });
  const t = setTimeout(() => { console.error('timeout after ' + timeout + ' ms'); done(1); }, timeout);
  page.goto(page_url, {waitUntil: 'commit', timeout: 0}).catch(e => { console.error('goto: ' + e.message); done(1); });
  const code = await finished;
  clearTimeout(t);
  await browser.close();
  process.exit(code);
})();
")

(defn chromium-cmd
  "Build body (-t browser) and its page under root; -> the command that loads
   the page in headless Chromium. The page sets window.benchArg before main.js."
  [root body arg]
  (let [js (cljs-build root body :browser)]
    ;; after the build: a fresh build empties root
    (spit (str root "/chromium.js") chromium-js)
    (spit (str root "/page.html")
          (str "<!doctype html><meta charset=utf-8><script>window.benchArg = " (pr-str (or arg "")) ";</script>"
               "<script src=\"" (fs/file-name js) "\"></script>"))
    ["node" (str root "/chromium.js") (str (.toURI (fs/file (fs/absolutize (str root "/page.html")))))
     (str timeout-ms)]))

(defn run-browser
  "ClojureScript's own core in headless Chromium: the program compiled
   -t browser -O simple, console printing on, the size from window.benchArg."
  [id src arg]
  (run! (chromium-cmd (str "target/bench-programs/browser-" (name id))
                      (str "(ns bench.prog)\n(enable-console-print!)\n" (harness src (clock "browser") true)
                           "(-main (.-benchArg js/window))\n")
                      arg)))

(defn run-browser-cohost [_ _ _] (throw (ex-info "browser" {:err browser-cohost-why})))

(def runners
  {"jvm" run-jvm "jvm-cohost" run-jvm-cohost
   "jank-jit" run-jank-jit "jank-aot" run-jank-aot "jank" run-jank-cohost
   "cljrs" run-cljrs "cljrs-cohost" run-cljrs-cohost
   "jolt" run-jolt "jolt-cohost" run-jolt-cohost
   "lpy" run-lpy "lpy-cohost" run-lpy-cohost
   "cljw" run-cljw "cljw-cohost" run-cljw-cohost
   "phel" run-phel "phel-cohost" run-phel-cohost
   "lg" run-lg "lg-cohost" run-lg-cohost
   "cljs" run-cljs "cljs-cohost" run-cljs-cohost
   "browser" run-browser "browser-cohost" run-browser-cohost})

;; ---- first-output

(defn wall-aot-cmd []
  (let [root (str "target/bench-programs/aot-wall" jank-tag)
        body "(ns bench.wall)\n(defn -main [& _]\n  (println \"ok\"))\n"
        stamp (str root "/stamp")]
    (when-not (and (fs/exists? stamp) (= (slurp stamp) body) (seq (fs/glob root "**/bench/wall.o")))
      (fs/delete-tree root)
      (fs/create-dirs (str root "/src/bench"))
      (spit (str root "/src/bench/wall.jank") body)
      (let [c (run! [jank-bin "--module-path" (str root "/src") "compile-module" "bench.wall"
                     "-O3" "--target-dir" root])]
        (when-not (and (zero? (:exit c)) (seq (fs/glob root "**/bench/wall.o")))
          (throw (ex-info "aot-wall" {:err (str (:err c) "\n" (:out c))}))))
      (spit stamp body))
    [jank-bin "--module-path" (str (first (fs/glob root "_cache/*")) ":" root "/src") "run-main" "bench.wall"]))

(def ok-eval "(println \"ok\")")

(defn wall-cmd [id file]
  (case id
    "jvm" ["clojure" "-M" file]
    "jvm-cohost" ["clojure" "-M" "-m" "cohost.run" "--" "--eval" ok-eval]
    "jank-jit" [jank-bin "-O3" "run" file]
    "jank-aot" (wall-aot-cmd)
    "jank" (:cmd (jank-cohost-cmd ok-eval))
    "cljrs" ["cljrs" "run" file]
    "cljrs-cohost" ["cljrs" "run" "--src-path" "src" "--src-path" "target/cljrs-ns"
                    "src/cohost/run.cljc" "--" "--eval" ok-eval]
    "jolt" ["jolt" "run" file]
    "jolt-cohost" ["jolt" "-Scp" "src" "-m" "cohost.run" "--eval" ok-eval]
    "lpy" ["basilisp" "run" file]
    "lpy-cohost" ["basilisp" "run" "-p" "src" "-c"
                  (str "(require 'cohost.run) (cohost.run/-main \"--eval\" " (pr-str ok-eval) ")")]
    "cljw" ["cljw" file]
    "cljw-cohost" ["cljw" "-cp" "src" "-m" "cohost.run" "--eval" ok-eval]
    "phel" (let [f (tmp "wall.phel")]
             (spit f "(ns bench.wall)\n(println \"ok\")\n")
             ["phel" "run" f])
    "phel-cohost" ["phel" "run" "dev/phel_main.phel" "--" "--eval" ok-eval]
    "lg" ["lg" file]
    "lg-cohost" ["lg" "-source-paths" "src" "dev/lg_main.lg" "--" "--eval" ok-eval]
    "cljs" ["node" (cljs-build "target/bench-programs/cljs-wall" "(ns bench.prog)\n(println \"ok\")\n")]
    "cljs-cohost" (do (ensure-cljs-node!) ["node" "target/cljs-node/main.js" "--eval" ok-eval])
    "browser" (chromium-cmd "target/bench-programs/browser-wall"
                            "(ns bench.prog)\n(enable-console-print!)\n(println \"ok\")\n" nil)
    (throw (ex-info "browser" {:err browser-cohost-why}))))

(defn wall-run [id file]
  (let [cmd (wall-cmd id file)
        t0 (System/nanoTime)
        proc (run! cmd (cond (= id "cljrs-cohost") cljrs-env
                             ;; computed by wall-cmd, before the clock started
                             (= id "jank") (when @jank-env {:extra-env @jank-env})))]
    {:exit (:exit proc) :ms (/ (- (System/nanoTime) t0) 1e6)
     :out (or (:out proc) "") :err (or (:err proc) "")}))

;; ---- parsing, errors

(defn error-text
  "The host's error, as printed: the first line naming an error, in full
   (cohost's `=> ERR` line, a compiler `error:` line, an exception), else the
   first 400 characters of the output."
  [& outs]
  (let [lines (remove #(re-find #"^WARNING:|DeprecationWarning|trace-deprecation" %)
                      (mapcat #(str/split-lines (or % "")) outs))
        ;; the Clojure CLI prints `Execution error (X) at ...` and the message on the next line
        cli (some (fn [[a b]] (when (re-find #"^(Execution|Unexpected) error" a) (str (str/trim a) " " (str/trim (or b "")))))
                  (reverse (partition 2 1 [nil] lines)))
        err (some #(when-let [[_ m] (re-find #"=> ERR (.*)" %)] (str/trim m)) lines)
        ;; a C++ compiler diagnostic names the cause cohost's ERR line only summarizes
        diag (some #(when (re-find #":\d+:\d+: error: " %) (str/trim %)) lines)]
    (or (cond (and err diag) (str err " -- " diag) :else (or err diag cli))
        ;; node: the thrown JS error, not the minified source line it echoes first
        (some #(when (re-find #"^\s*[A-Za-z]*Error(: |$)" %) (str/trim %)) lines)
        (some #(when (re-find #"^pageerror: |^timeout after " %) (str/trim %)) lines)
        (some #(when (re-find #"(?i)error|exception|panic|could not|cannot|unable|undeclared|not found|traceback" %)
                 (str/trim %))
              lines)
        (let [t (str/trim (str/join "\n" (remove str/blank? outs)))]
          (when (seq t) (subs t 0 (min 400 (count t))))))))

(defn lines-of [out tag]
  (keep #(second (re-matches (re-pattern (str tag " (.*)")) %)) (str/split-lines (or out ""))))

(defn num [s] (Double/parseDouble (str/trim s)))

(defn median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (if (even? n) (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0) (v (quot n 2)))))

(defn summary [ms]
  {:median (median ms) :min (apply min ms) :max (apply max ms) :runs ms})

(defn measure [t p arg]
  (try
    (let [proc ((runners (:id t)) (:id p) (slurp (:file p)) arg)
          out (:out proc)
          ms (mapv num (lines-of out "PROGRAM-MS"))
          result (first (lines-of out "PROGRAM-RESULT"))
          bad (some-> (first (lines-of out "PROGRAM-BAD")) num long)]
      (cond
        (or (not= 10 (count ms)) (nil? result))
        {:fail (or (error-text (:err proc) out) (str "exit " (:exit proc)))}
        (pos? bad)
        {:fail (str bad " of 10 timed calls returned a result other than the first call's " result)}
        :else
        (merge (summary ms)
               {:result result
                :cold (num (first (lines-of out "PROGRAM-COLD")))
                :warmup (long (num (first (lines-of out "PROGRAM-WARMUP"))))})))
    (catch Exception e
      {:fail (or (some-> e ex-data :err error-text) (.getMessage e))})))

(defn measure-wall [t p]
  (try
    (let [cold (wall-run (:id t) (:file p))]
      (if-not (str/includes? (:out cold) "ok")
        {:fail (or (error-text (:err cold) (:out cold)) (str "exit " (:exit cold)))}
        (do (wall-run (:id t) (:file p))
            (merge (summary (vec (repeatedly 10 #(:ms (wall-run (:id t) (:file p))))))
                   {:result "ok" :cold (:ms cold) :warmup 1}))))
    (catch Exception e
      {:fail (or (some-> e ex-data :err error-text) (.getMessage e))})))

;; ---- records

;; A jank target's records carry the binary's tag (JANK_BIN), so a source build's
;; rows never overwrite the apt binary's.
(defn results-file [target-id]
  (str "target/bench-programs/results/" target-id
       (if (str/starts-with? target-id "jank") jank-tag "") ".edn"))

(defn load-results [target-id]
  (let [f (results-file target-id)]
    (if (fs/exists? f) (read-string (slurp f)) {})))

(defn save-result! [target-id pid rec]
  (fs/create-dirs "target/bench-programs/results")
  (spit (results-file target-id) (pr-str (assoc (load-results target-id) pid rec))))

(defn fmt [x] (if (number? x) (format "%.3f" (double x)) (str x)))

(defn report-program [t p arg]
  (let [n (name (:id p))
        m (if (:wall p) (measure-wall t p) (measure t p arg))
        jvm (when-not (or (:wall p) (= (:id t) "jvm")) (:result (get (load-results "jvm") (:id p))))
        m (cond-> (assoc m :arg arg)
            (= (:id t) "jank") (assoc :boot @jank-boot)
            (and (:result m) jvm (not= jvm (:result m)) (not= (:arg (get (load-results "jvm") (:id p))) arg))
            (assoc :note "JVM record is at another size")
            (and (:result m) jvm (not= jvm (:result m)) (= (:arg (get (load-results "jvm") (:id p))) arg))
            (assoc :differ jvm))]
    (save-result! (:id t) (:id p) m)
    (println
     (if (:fail m)
       (str "  PROGRAM " n " ABSENT " (:fail m))
       (str "  PROGRAM " n " SIZE " (or arg "-") " COLD-MS " (fmt (:cold m)) " WARMUP " (:warmup m)
            " MEDIAN-MS " (fmt (:median m)) " MIN-MS " (fmt (:min m)) " MAX-MS " (fmt (:max m))
            (if (:differ m) (str " DIFFER jvm " (:differ m) " got " (:result m)) (str " RESULT " (:result m))))))
    (flush)))

(defn select
  "\"fib,tak:12 8 4\" -> [[program arg] ...]; nil -> every program."
  [filt]
  (if (str/blank? filt)
    (map (fn [p] [p (:arg p)]) programs)
    (for [item (str/split filt #",")
          :let [[pn size] (str/split item #":" 2)
                p (first (filter #(= (name (:id %)) pn) programs))]]
      (if p [p (or size (:arg p))] (throw (ex-info (str "unknown program " pn) {}))))))

(defn report-target [t filt]
  (if-let [why (reason t)]
    (println (str "TARGET " (:id t) " ABSENT " why))
    (do (println (str "TARGET " (:id t) " present"))
        (doseq [[p arg] (select filt)]
          (report-program t p arg)))))

;; ---- the doc/PERF.md table

(defn geomean [xs] (when (seq xs) (Math/exp (/ (reduce + (map #(Math/log %) xs)) (count xs)))))

(defn table [ids & [ps]]
  (let [ps (or ps programs)
        ids (if (seq ids) ids (filter #(fs/exists? (results-file %)) (map :id targets)))
        others (remove #{"jvm"} ids)
        recs (into {} (map (fn [i] [i (load-results i)]) ids))
        jvm (load-results "jvm")
        ratio (fn [i pid] (let [a (get-in recs [i pid :median]) b (get-in jvm [pid :median])]
                            (when (and a b (nil? (get-in recs [i pid :differ]))) (/ a b))))
        row (fn [cells] (println (str "| " (str/join " | " cells) " |")))]
    (println "Median ms of 10 timed calls (xJVM: median / JVM median).")
    (println)
    (row (concat ["program" "size" "jvm"] others))
    (println (str "|---|---:|" (str/join "" (repeat (inc (count others)) "---:|"))))
    (doseq [p ps :let [pid (:id p)]]
      (row (concat [(name pid) (or (get-in jvm [pid :arg]) "-") (some-> (get-in jvm [pid :median]) fmt)]
                   (for [i others :let [r (get-in recs [i pid])]]
                     (cond (nil? r) "not run"
                           (:fail r) "ABSENT"
                           (:differ r) (str (fmt (:median r)) " DIFFER")
                           :else (str (fmt (:median r)) " (x" (format "%.2f" (ratio i pid)) ")"))))))
    (println)
    (println "min-max of the 10 timed calls / the cold call (the first call in a fresh process), ms.")
    (println)
    (row (concat ["program"] ids))
    (println (str "|---|" (str/join "" (repeat (count ids) "---:|"))))
    (doseq [p ps :let [pid (:id p)]]
      (row (cons (name pid)
                 (for [i ids :let [r (get-in recs [i pid])]]
                   (if (:median r) (str (fmt (:min r)) "-" (fmt (:max r)) " / " (fmt (:cold r))) "")))))
    (println)
    (doseq [i ids :when (not= i "jvm")
            :let [all (keep #(ratio i (:id %)) ps)
                  un (keep #(when-not (:hinted? %) (ratio i (:id %))) ps)
                  hi (keep #(when (:hinted? %) (ratio i (:id %))) ps)]]
      (println (str "- " i ": geomean xJVM " (some-> (geomean all) (#(format "%.3f" %))) " over " (count all)
                    " programs (unhinted " (some-> (geomean un) (#(format "%.3f" %))) " over " (count un)
                    ", hinted " (some-> (geomean hi) (#(format "%.3f" %))) " over " (count hi) ")")))
    (doseq [i ids
            :let [pairs (for [p ps :when (:hinted? p)
                              :let [base (keyword (str/replace (name (:id p)) "-hinted" ""))
                                    h (get-in recs [i (:id p) :median]) u (get-in recs [i base :median])]
                              :when (and h u)]
                          (str (name base) " " (format "%.3f" (/ h u))))]
            :when (seq pairs)]
      (println (str "- hinted/unhinted median on " i ": " (str/join ", " pairs))))
    (println)
    (println "ABSENT and DIFFER, as printed:")
    (println)
    (doseq [i ids p ps :let [r (get-in recs [i (:id p)])]
            :when (or (:fail r) (:differ r))]
      (println (str "- " i " " (name (:id p)) ": "
                    (if (:fail r)
                      (str "`" (str/replace (:fail r) "`" "'") "`")
                      (str "DIFFER: " (:result r) ", JVM " (:differ r))))))))

;; ---- the sentinel table (X1-2): the rows each upstream patch should move.

(def sentinels (filter :sentinel? programs))

(defn sentinel-table []
  (println (str "Sentinels on JANK_BIN=" jank-bin
                (when-not (str/blank? jank-tag) (str " (jank records tag " jank-tag ")"))))
  (println)
  (table ["jvm" "jank-aot" "jank"] sentinels))

(defn run-sentinels!
  "jvm where a sentinel has no JVM record; jank-aot and jank always (on JANK_BIN); then the table."
  []
  (let [filt (str/join "," (map (comp name :id) sentinels))
        jvm (load-results "jvm")
        missing (remove #(get-in jvm [(:id %) :median]) sentinels)
        target (fn [id] (first (filter #(= (:id %) id) targets)))]
    (when (seq missing)
      (report-target (target "jvm") (str/join "," (map (comp name :id) missing))))
    (doseq [id ["jank-aot" "jank"]]
      (report-target (target id) filt))
    (println)
    (sentinel-table)))

(defn -main [& args]
  (let [[arg filt] args]
    (cond
      (or (nil? arg) (= arg "help"))
      (do (println "usage: bb bench:programs <target>|all [program[:size],...] | table [target ...] | sentinels [table]")
          (println (str "targets: " (str/join " " (map :id targets)))))
      (= arg "table") (table (rest args))
      (= arg "sentinels") (if (= filt "table") (sentinel-table) (run-sentinels!))
      (= arg "all") (doseq [t targets] (report-target t filt))
      :else
      (if-let [t (first (filter #(= (:id %) arg) targets))]
        (report-target t filt)
        (do (println (str "UNKNOWN " arg)) (System/exit 1))))))

(apply -main *command-line-args*)
