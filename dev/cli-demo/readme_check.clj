;; bb readme:check [target ...] -- README.md's command blocks, run as written.
;;
;; For each target (default: jvm), in a fresh temporary directory: every ```bash
;; block of README.md, in order, with bin/cohost first on PATH. `cd dir` moves
;; the next commands; the README's `cohost -Tnew create ...` gets `:target <t>`
;; appended, so the project it makes is that target's. A command's expected
;; output is its `# => line` comments (inline, then the comment lines below it):
;; the command's non-blank stdout lines must equal them (trailing spaces
;; ignored); a command with none must exit 0. A block whose first line is
;; `# not checked: ...` is skipped; `# targets: a b` runs it for those targets
;; only. One line per command: PASS or FAIL with what differed; exit 1 on any FAIL.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def repo (str (fs/canonicalize ".")))

(defn blocks [md]
  (map second (re-seq #"(?s)```bash\n(.*?)```" md)))

(defn commands
  "A block -> [{:cmd s :expect [lines]}]."
  [block]
  (reduce (fn [acc line]
            (let [t (str/trim line)]
              (cond
                (str/blank? t) acc
                (str/starts-with? t "# =>") (if (seq acc)
                                              (update-in acc [(dec (count acc)) :expect] conj
                                                         (str/trimr (str/replace-first t #"^# =>\s?" "")))
                                              acc)
                (str/starts-with? t "#") acc
                :else (let [[cmd inline] (str/split line #"\s+# =>\s?" 2)]
                        (conj acc {:cmd (str/trim cmd) :expect (if inline [(str/trimr inline)] [])})))))
          [] (str/split-lines block)))

(defn run-block? [block target]
  (let [first-line (str/trim (first (str/split-lines block)))]
    (cond (str/starts-with? first-line "# not checked") false
          (str/starts-with? first-line "# targets:")
          (contains? (set (str/split (str/trim (subs first-line 10)) #"\s+")) target)
          :else true)))

(defn check-target [target]
  (let [dir (str (fs/create-temp-dir {:prefix "cohost-readme-"}))
        env {"PATH" (str repo "/bin:" (System/getenv "PATH"))}
        fails (atom 0)]
    (println (str "== target " target " in " dir))
    (loop [[{:keys [cmd expect]} & more] (mapcat commands (filter #(run-block? % target) (blocks (slurp "README.md"))))
           cwd dir]
      (when cmd
        (if-let [[_ to] (re-matches #"cd\s+(\S+)" cmd)]
          (recur more (str (fs/path cwd to)))
          (let [cmd' (if (re-find #"-Tnew create " cmd) (str cmd " :target " target) cmd)
                proc (p/process ["bash" "-c" cmd'] {:dir cwd :extra-env env :out :string :err :string :in ""})
                ;; a command that hangs is a FAIL, not a stalled check
                r (let [x (deref proc 600000 ::timeout)]
                    (if (= x ::timeout)
                      (do (p/destroy-tree proc) {:exit -1 :out "" :err "killed: no exit after 600 s"})
                      x))
                got (vec (remove str/blank? (map str/trimr (str/split-lines (:out r)))))
                ok? (if (seq expect) (= got expect) (zero? (:exit r)))]
            (when-not ok? (swap! fails inc))
            (println (str (if ok? "PASS " "FAIL ") cmd'
                          (when-not ok?
                            (str "\n     expected " (pr-str expect) "\n     got      " (pr-str got)
                                 " exit " (:exit r)
                                 (when-not (str/blank? (:err r))
                                   (str "\n     stderr   " (let [e (str/trim (:err r))] (subs e 0 (min 400 (count e))))))))))
            (flush)
            (recur more cwd)))))
    @fails))

(let [targets (or (seq *command-line-args*) ["jvm"])
      fails (reduce + (map check-target targets))]
  (println (str "readme:check: " fails " failing command(s) over " (count targets) " target(s)"))
  (System/exit (if (zero? fails) 0 1)))
