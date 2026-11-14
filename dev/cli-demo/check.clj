;; bb cli:check [target ...] -- the cohost CLI against the Clojure CLI, case by case.
;;
;; Each case runs in dev/cli-demo twice: `clojure <args>` (the reference) and
;; `bin/cohost :<target> <args>`. A case is SAME when stdout and the exit code
;; match; -Spath is SAME when cohost's roots are clojure's classpath with each
;; jar replaced by its extraction under ~/.cache/cohost/deps and the
;; org.clojure/clojure jar left out (doc/CLI.md, "Resolution"). One line per
;; target x case: SAME, ABSENT (the host is not on PATH), or DIFF with both sides.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def repo (str (fs/canonicalize ".")))
(def demo (str repo "/dev/cli-demo"))
(def cohost (str repo "/bin/cohost"))

(def targets
  [["jvm" "clojure"] ["jolt" "jolt"] ["lpy" "basilisp"] ["cljs" "node"] ["cljw" "cljw"]
   ["phel" "phel"] ["lg" "lg"] ["jank" "jank"] ["cljrs" "cljrs"]])

(defn cases [t]
  (let [user (str t "-user")]
    [["-M -m" ["-M" "-m" "demo.main" user]]
     ["-M:main" ["-M:main" "-g" "hi" "-n" "2" user]]
     ["-M flag" ["-M" "-m" "demo.main" "-l" user]]
     ["-X:run" ["-X:run" ":user" (pr-str user)]]
     ["-e" ["-e" "(require 'greeter.core)" "-e" (str "(greeter.core/name-of " (pr-str user) ")")]]
     ["script" ["script.clj" user "jvm-x"]]
     ["-Spath" ["-Spath"]]
     ["-Stree" ["-Stree"]]
     ["-Strace -Spath" ["-Strace" "-Spath"]]
     ["-Sthreads -Spath" ["-Sthreads" "2" "-Spath"]]
     ["-Sforce -Spath" ["-Sforce" "-Spath"]]
     ["-Sdeps file -Spath" ["-Sdeps" "extra-deps.edn" "-Spath" "-A:x"]]]))

(def spath-labels #{"-Spath" "-Strace -Spath" "-Sthreads -Spath" "-Sforce -Spath"
                    "-Sdeps file -Spath"})

(defn run [cmd]
  (let [r (deref (p/process cmd {:dir demo :out :string :err :string :in ""}))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn cp-entries [s] (remove str/blank? (str/split (str/trim s) #":")))

(defn- abs-entry
  "A classpath entry as an absolute path string. Entries arrive relative
   (to the demo dir) or absolute, depending on the CLI that printed them;
   fs/path refuses an absolute child, so absolute entries pass through
   and relative ones resolve against the demo dir."
  [e] (if (fs/absolute? e) e (str (fs/absolutize (fs/path demo e)))))

(defn spath-same? [ref got]
  (let [want (keep (fn [e]
                     (let [abs (abs-entry e)]
                       (cond
                         (re-find #"/org/clojure/clojure/[^/]+/clojure-[^/]+\.jar$" abs) nil
                         (str/ends-with? abs ".jar")
                         (re-pattern (str "\\Q" (fs/home) "/.cache/cohost/deps/"
                                          (str/replace (fs/file-name abs) #"\.jar$" "") "\\E-[0-9a-f]{8}"))
                         (fs/exists? abs) abs
                         :else nil)))
                   (cp-entries ref))
        have (map abs-entry (cp-entries got))]
    (and (= (count want) (count have))
         (every? true? (map (fn [w h] (if (string? w) (= w h) (boolean (re-matches w h)))) want have)))))

(defn tree-same?
  "cohost's -Stree is rendered from the basis: the roots and their order
   and every node line must be clojure's; sibling order under a parent is
   the basis's, not the POM declaration order stock prints, so the child
   lines compare as a multiset."
  [ref got]
  (let [lines (fn [s] (remove str/blank? (str/split-lines (or s ""))))
        rl (lines ref) gl (lines got)
        roots (fn [ls] (filter #(not (str/starts-with? % " ")) ls))]
    (and (= (roots rl) (roots gl))
         (= (frequencies rl) (frequencies gl)))))

(defn one-line [s] (str/replace (str/trim (or s "")) #"\s*\n\s*" " | "))

(defn check [[t exe]]
  (if-not (fs/which exe)
    (println (format "%-6s %-8s ABSENT no %s on PATH" t "*" exe))
    (doseq [[label args] (cases t)]
      (let [ref (run (into ["clojure"] args))
            got (run (into [cohost (str ":" t)] args))
            same? (and (= (:exit ref) (:exit got))
                       (cond
                         ;; :jvm runs the real CLI: its classpath is the classpath
                         (and (spath-labels label) (not= t "jvm"))
                         (spath-same? (:out ref) (:out got))
                         (= label "-Stree") (tree-same? (:out ref) (:out got))
                         :else (= (:out ref) (:out got))))]
        (println
         (if same?
           (format "%-6s %-8s SAME" t label)
           (let [e (one-line (:err got))]
             (format "%-6s %-8s DIFF clojure: exit %s, %s -- cohost: exit %s, %s%s" t label
                     (:exit ref) (pr-str (one-line (:out ref)))
                     (:exit got) (pr-str (one-line (:out got)))
                     (if (str/blank? e) "" (str ", stderr " (pr-str (subs e 0 (min 300 (count e))))))))))
        (flush)))))

(let [want (set *command-line-args*)]
  (doseq [t targets :when (or (empty? want) (want (first t)))]
    (check t)))
