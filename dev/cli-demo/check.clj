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
     ["-X:run" ["-X:run" ":user" (pr-str user)]]
     ["-e" ["-e" "(require 'greeter.core)" "-e" (str "(greeter.core/name-of " (pr-str user) ")")]]
     ["script" ["script.clj" user "jvm-x"]]
     ["-Spath" ["-Spath"]]]))

(defn run [cmd]
  (let [r (deref (p/process cmd {:dir demo :out :string :err :string :in ""}))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn cp-entries [s] (remove str/blank? (str/split (str/trim s) #":")))

(defn spath-same? [ref got]
  (let [want (keep (fn [e]
                     (let [abs (str (fs/absolutize (fs/path demo e)))]
                       (cond
                         (re-find #"/org/clojure/clojure/[^/]+/clojure-[^/]+\.jar$" abs) nil
                         (str/ends-with? abs ".jar")
                         (re-pattern (str "\\Q" (fs/home) "/.cache/cohost/deps/"
                                          (str/replace (fs/file-name abs) #"\.jar$" "") "\\E-[0-9a-f]{8}"))
                         (fs/exists? abs) abs
                         :else nil)))
                   (cp-entries ref))
        have (cp-entries got)]
    (and (= (count want) (count have))
         (every? true? (map (fn [w h] (if (string? w) (= w h) (boolean (re-matches w h)))) want have)))))

(defn one-line [s] (str/replace (str/trim (or s "")) #"\s*\n\s*" " | "))

(defn check [[t exe]]
  (if-not (fs/which exe)
    (println (format "%-6s %-8s ABSENT no %s on PATH" t "*" exe))
    (doseq [[label args] (cases t)]
      (let [ref (run (into ["clojure"] args))
            got (run (into [cohost (str ":" t)] args))
            same? (and (= (:exit ref) (:exit got))
                       ;; :jvm runs the real CLI: its classpath is the classpath
                       (if (and (= label "-Spath") (not= t "jvm"))
                         (spath-same? (:out ref) (:out got))
                         (= (:out ref) (:out got))))]
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
