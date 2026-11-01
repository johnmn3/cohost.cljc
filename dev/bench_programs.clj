;; jank-FAST Step 8. bb bench:programs <target>|all
;; Times are filled in as each target is run. A missing target prints a reason.
(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def programs
  [{:id :fib :file "bench/programs/fib.clj" :hinted? false}
   {:id :fib-hinted :file "bench/programs/fib_hinted.clj" :hinted? true}
   {:id :tak :file "bench/programs/tak.clj" :hinted? false}
   {:id :tak-hinted :file "bench/programs/tak_hinted.clj" :hinted? true}
   {:id :nbody :file "bench/programs/nbody.clj" :hinted? false}
   {:id :nbody-hinted :file "bench/programs/nbody_hinted.clj" :hinted? true}
   {:id :spectral-norm :file "bench/programs/spectral_norm.clj" :hinted? false}
   {:id :spectral-norm-hinted :file "bench/programs/spectral_norm_hinted.clj" :hinted? true}
   {:id :mandelbrot :file "bench/programs/mandelbrot.clj" :hinted? false}
   {:id :mandelbrot-hinted :file "bench/programs/mandelbrot_hinted.clj" :hinted? true}
   {:id :binary-trees :file "bench/programs/binary_trees.clj" :hinted? false}
   {:id :fannkuch :file "bench/programs/fannkuch.clj" :hinted? false}
   {:id :fannkuch-hinted :file "bench/programs/fannkuch_hinted.clj" :hinted? true}
   {:id :nsieve :file "bench/programs/nsieve.clj" :hinted? false}
   {:id :nsieve-hinted :file "bench/programs/nsieve_hinted.clj" :hinted? true}
   {:id :k-nucleotide :file "bench/programs/k_nucleotide.clj" :hinted? false}
   {:id :pipeline :file "bench/programs/pipeline.clj" :hinted? false}
   {:id :dispatch :file "bench/programs/dispatch.clj" :hinted? false}
   {:id :json :file "bench/programs/json.clj" :hinted? false}
   {:id :first-output :file "bench/programs/first_output.clj" :hinted? false}])

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
   {:id "cljs" :exe "node" :kind :own}
   {:id "cljs-cohost" :exe "node" :kind :cohost}
   {:id "browser" :exe "node" :kind :own}
   {:id "browser-cohost" :exe "node" :kind :cohost}
   {:id "cljw" :exe "cljw" :kind :own}
   {:id "cljw-cohost" :exe "cljw" :kind :cohost}
   {:id "phel" :exe "phel" :kind :own}
   {:id "phel-cohost" :exe "phel" :kind :cohost}
   {:id "lg" :exe "lg" :kind :own}
   {:id "lg-cohost" :exe "lg" :kind :cohost}
   {:id "cljrs" :exe "cljrs" :kind :own}
   {:id "cljrs-cohost" :exe "cljrs" :kind :cohost}])

(defn present? [exe]
  (boolean (fs/which exe)))

(defn report-target [t]
  (let [here (present? (:exe t))]
    (println (str "TARGET " (:id t)
                  (if here " present" (str " ABSENT no " (:exe t) " on PATH"))))
    (when here
      (doseq [p programs]
        (println (str "  PROGRAM " (name (:id p))
                      (if (fs/exists? (:file p)) " file" " MISSING-FILE")
                      " NOT-TIMED"))))))

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
