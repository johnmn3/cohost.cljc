;; bb debug-seam:check <target> -- the debug-seam checklist
;; (doc/debug-seam-research.md section (c), checks #1-#14) run through
;; the cohost seam on one target (jvm | jank). One line per check:
;; #N PASS|FAIL|SKIP with the evidence, or the unit that owns it.
;; Hardened per lane R's findings F4-F7: #1 judges the streams
;; separately, #5 probes a value only *1 can produce, #7 judges pst's
;; own output shapes, #14 runs all three exit cases; every PASS line
;; quotes the exact text it matched.
;;
;; For jvm the seam is cohost.cli on the JVM host itself
;; (clojure -M -m cohost.cli <config>), not the real CLI; for jank it
;; is bin/cohost :jank in dev/cli-demo.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def repo (str (fs/canonicalize ".")))
(def demo (str repo "/dev/cli-demo"))
(def target (or (first *command-line-args*) "jvm"))
(def tmp (str (fs/create-temp-dir {:prefix "debug-seam-"})))
(def reports-dir (str tmp "/reports"))
(fs/create-dirs reports-dir)

(defn run-proc
  "-> {:out :err :exit} (:exit :timeout past the limit)."
  ([cmd dir stdin] (run-proc cmd dir stdin {}))
  ([cmd dir stdin extra-env]
   (let [proc (p/process {:cmd cmd :dir dir :in stdin
                          :out :string :err :string
                          :extra-env (merge {"CLJ_CONFIG" (or (System/getenv "CLJ_CONFIG") "")}
                                            extra-env)})
         done (deref proc 900000 :timeout)]
     (if (= done :timeout)
       (do (p/destroy proc) {:out "" :err "" :exit :timeout})
       {:out (:out done) :err (:err done) :exit (:exit done)}))))

(defn jvm-cli
  "cohost.cli on the JVM host with the given config map; stdin a string."
  [cfg stdin]
  (let [cfg-file (str tmp "/cfg-" (rand-int 100000000) ".edn")]
    (spit cfg-file (pr-str (merge {:stock-root (str repo "/resources/cohost/clojure-1.12.0")
                                   :roots [] :report "file" :report-dir reports-dir}
                                  cfg)))
    (run-proc ["clojure" "-Sdeps" "{:paths [\"src\"]}" "-M" "-m" "cohost.cli" cfg-file]
              repo stdin)))

(defn jank-cli
  "bin/cohost :jank with clojure-style args in the demo project."
  ([args stdin] (jank-cli args stdin {}))
  ([args stdin extra-env]
   (run-proc (into [(str repo "/bin/cohost") ":jank"] args) demo stdin extra-env)))

(defn run-main
  "A fixture namespace's -main through the seam (the load path:
   the namespace loads through the loader, then -main runs)."
  [ns-sym]
  (case target
    "jvm" (jvm-cli {:roots [(str demo "/src")]
                    :main {:type :main :ns ns-sym :args []}} "")
    "jank" (jank-cli ["-m" ns-sym] "")))

(defn run-e
  "One -e program (a single string of forms), uncaught errors and all."
  [program]
  (case target
    "jvm" (jvm-cli {:inits [[:e program]] :main nil} "")
    "jank" (jank-cli ["-e" program] "")))

(defn run-repl
  [stdin]
  (case target
    "jvm" (jvm-cli {:main {:type :repl}} stdin)
    "jank" (jank-cli [] stdin)))

(def results (atom []))
(defn record! [n status evidence]
  (swap! results conj [n status evidence])
  (println (str "#" n " " status " " evidence)))

(defn includes? [s & needles] (every? (fn [x] (str/includes? s x)) needles))

;; ---- the sessions ----------------------------------------------------

;; one top-level form, so the -e value printing (run-cli! prints each
;; form's value) prints nothing for it: #1 can then hold stdout to the
;; research contract (the report on stderr, nothing on stdout)
(def boom-program "(do (defn boom [] (throw (ex-info \"kaboom\" {:x 5}))) (boom))")
(def uncaught (run-e boom-program))
(def report-path (some (fn [line] (let [t (str/trim line)] (when (str/ends-with? t ".edn") t)))
                       (str/split-lines (:err uncaught))))
(def report-text (when (and report-path (fs/exists? report-path)) (slurp report-path)))

(def repl-input
  (str "(+ 1 2)\n"
       "*1\n"
       "(prn :star1 *1)\n"
       "(defn inner [x] (throw (ex-info \"r\" {:x x})))\n"
       "(try (inner 1) (catch Throwable e (prn :t1 (first (.getStackTrace e)))))\n"
       "(require 'clojure.repl)\n"
       "(try (inner 2) (catch Throwable e (clojure.repl/pst e)))\n"
       "(inner 3)\n"
       "*e\n"))
(def repl-run (run-repl repl-input))
(def repl-text (str (:out repl-run) "\n" (:err repl-run)))

;; #14's other two cases: -X with a missing fn in a loadable namespace
;; (seam.ok: no deps -- demo.main's own deps are not the seam's roots),
;; and a script that exits 3
(def exec-missing
  (case target
    "jvm" (jvm-cli {:roots [(str demo "/src")]
                    :main {:type :exec :ns "seam.ok" :fn "nope" :args-edn "[]"}} "")
    "jank" (jank-cli ["-X" "seam.ok/nope"] "")))
(def exec-missing-text (str (:out exec-missing) "\n" (:err exec-missing)))
(def exit3-path (str tmp "/exit3.clj"))
(spit exit3-path "(System/exit 3)\n")
(def exit3-run
  (case target
    "jvm" (jvm-cli {:main {:type :script :path exit3-path :args []}} "")
    "jank" (jank-cli [exit3-path] "")))
(def reader-run (run-e "(defn broken [x"))
(def reader-text (str (:out reader-run) "\n" (:err reader-run)))

;; #2 source meta at read (the research checklist's probe, jank only):
;; read a probe file's forms through the adapter's read-forms and print
;; the defn form's meta -- its :jank/source must name the file and the
;; defn's file-absolute line (line 3 below)
(def pos-path (str tmp "/pos_probe.clj"))
(spit pos-path "(ns posprobe)\n;; a comment\n(defn inner [x] (throw (ex-info \"r\" {:x x})))\n")
(def pos-run
  (when (= target "jank")
    (run-e (str "(require 'cohost.adapter)"
                " (let [fs (atom [])]"
                "   (cohost.adapter/read-forms (slurp " (pr-str pos-path) ")"
                "     (fn [f] (swap! fs conj f)) " (pr-str pos-path) ")"
                "   (prn :pos2 (meta (second @fs)))"
                "   (prn :pos2sym (meta (second (second @fs)))))"))))
(def pos-text (str (:out pos-run) "\n" (:err pos-run)))
;; U3: a bad defn in a loaded namespace (the load path), and a native
;; C++ exception against a stock catch (the catch hole)
(def bad-load (run-main "seam.bad"))
(def bad-load-text (str (:out bad-load) "\n" (:err bad-load)))
(def native-catch
  (run-e "#?(:jank (try (throw (cpp/std.runtime_error \"boom\")) (catch Throwable e (prn :caught (ex-message e)))) :default (try (throw (ex-info \"boom\" {})) (catch Throwable e (prn :caught (ex-message e))))"))
(def native-catch-text (str (:out native-catch) "\n" (:err native-catch)))

;; U3: a macro that expands to an unresolved symbol, loaded as a namespace
(def bad-macro (run-main "seam.badmacro"))
(def bad-macro-text (str (:out bad-macro) "\n" (:err bad-macro)))
;; the macro diagnostic lives in the run's report file on the JVM host
;; (stderr carries only the one-line message), in stderr on jank
(def bad-macro-report-path (some (fn [line] (let [t (str/trim line)] (when (str/ends-with? t ".edn") t)))
                                 (str/split-lines (:err bad-macro))))
(def bad-macro-report (when (and bad-macro-report-path (fs/exists? bad-macro-report-path))
                        (slurp bad-macro-report-path)))
(def bad-macro-full (str bad-macro-text "\n" bad-macro-report))

;; #13's other half: a macro whose BODY throws (fixture seam.
;; throwmacro: use-it's defn expands (m 1) at load). The codemod
;; expands macros itself on the load path, so the attribution is the
;; expansion wrapper's job (boot/wrap-macro's expand-error): the
;; report must render the macroexpansion phase, naming the macro
(def macro-throw (run-main "seam.throwmacro"))
(def macro-throw-text (str (:out macro-throw) "\n" (:err macro-throw)))
(def macro-throw-report-path (some (fn [line] (let [t (str/trim line)] (when (str/ends-with? t ".edn") t)))
                                   (str/split-lines (:err macro-throw))))
(def macro-throw-report (when (and macro-throw-report-path (fs/exists? macro-throw-report-path))
                          (slurp macro-throw-report-path)))
(def macro-full (str bad-macro-full "\n" macro-throw-text "\n" macro-throw-report))

;; U5: *err* separation -- an in-program *err* write lands on stderr,
;; the *out* write on stdout, and the uncaught report stays on stderr
(def err-sep (run-e "(println \"to-out\") (binding [*out* *err*] (println \"to-err\")) (throw (ex-info \"kaboom\" {}))"))

;; U7: integer divide by zero, both spellings
(def div-run (run-e "(/ 5 0)"))
(def quot-run (run-e "(quot 5 0)"))
(def div-text (str (:out div-run) "\n" (:err div-run) "\n" (:out quot-run) "\n" (:err quot-run)))

;; U6: transform introspection through the launcher (jank only)
(def codegen (when (= target "jank")
               (jank-cli ["-e" "(+ 1 2)"] "" {"JANK_PRINT_CODEGEN" "1"})))
(def codegen-text (when codegen (str (:out codegen) "\n" (:err codegen))))

;; U6: a profile on request -- COHOST_JANK_PROFILE=1 must produce
;; jank's trace file in the project directory
(def profile-file-path (str demo "/jank.profile"))
(def profile-existed? (fs/exists? profile-file-path))
(def profile-run (when (= target "jank")
                   (jank-cli ["-e" "(+ 1 2)"] "" {"COHOST_JANK_PROFILE" "1"})))
(def profile-made? (when (= target "jank")
                     (and (fs/exists? profile-file-path)
                          (pos? (fs/size profile-file-path)))))
(when (and (= target "jank") profile-made? (not profile-existed?))
  (fs/delete profile-file-path))

;; ---- the checks ------------------------------------------------------

(defn first-real-line [s]
  (some (fn [line] (when-not (str/starts-with? line "WARNING") (str/trim line)))
        (str/split-lines (str/trim (or s "")))))

;; #1 uncaught throw: the clojure.main report contract -- the report
;; on stderr, nothing on stdout (F7: the streams are judged separately,
;; never joined)
(record! 1
         (if (and (= 1 (:exit uncaught))
                  (str/blank? (:out uncaught))
                  (includes? (:err uncaught) "Execution error (ExceptionInfo) at user/boom" "kaboom" "Full report at:")
                  report-text
                  (includes? report-text ":trace [["))
           "PASS"
           "FAIL")
         (str "exit " (:exit uncaught)
              "; stdout " (if (str/blank? (:out uncaught)) "empty" (pr-str (:out uncaught)))
              "; stderr "
              (if (includes? (:err uncaught) "Execution error (ExceptionInfo) at user/boom" "Full report at:")
                (str "matched " (pr-str "Execution error (ExceptionInfo) at user/boom")
                     " and " (pr-str "Full report at:"))
                (str "first line: " (first-real-line (:err uncaught))))
              "; report " (if report-text "written, matched \":trace [[\"" "missing/empty")))

(record! 2
         (if (= target "jank")
           (if (and (includes? pos-text (str ":file \"" pos-path "\""))
                    (includes? pos-text ":line 3"))
             "PASS" "FAIL")
           "SKIP")
         (if (= target "jank")
           (str "form meta read from " pos-path ": "
                (or (some (fn [line] (when (str/includes? line ":pos2 ") (str/trim line)))
                          (str/split-lines pos-text))
                    "nothing printed"))
           "jank read-end probe; the JVM read end is a stream reader (positions native)"))
;; #3 unresolved symbol: load refuses with the diagnostic, and a native
;; exception is caught by a stock catch Throwable with its message
(record! 3
         (let [load-ok? (and (not= 0 (:exit bad-load))
                             (includes? bad-load-text "this-var-does-not-exist"))
               native-ok? (includes? native-catch-text ":caught" "boom")]
           (cond (and load-ok? native-ok?) "PASS"
                 ;; on jank the catch hole is OPEN (U3b's doors were
                 ;; measured and removed; design banked in
                 ;; doc/debug-seam-probes/u3-diagnosis.md): the load
                 ;; half is this check's pass there until it closes
                 (and (= target "jank") load-ok?) "SKIP"
                 :else "FAIL"))
         (str "load: exit " (:exit bad-load)
              (if (includes? bad-load-text "this-var-does-not-exist")
                ", names the symbol" ", no diagnostic")
              "; native catch: " (first-real-line (:out native-catch))
              (when (and (= target "jank")
                         (not (includes? native-catch-text ":caught" "boom")))
                " (catch hole open on jank -- U3b design banked, doors removed)")))
;; #4 reader error: the probe runs; the :read-source phase is U4a's
;; read-end work, so until then this reports the measured state as SKIP
(record! 4
         (if (includes? reader-text "Syntax error reading source at (")
           "PASS" "SKIP")
         (if (includes? reader-text "Syntax error reading source at (")
           (str "matched " (pr-str "Syntax error reading source at (")
                "; first stderr line: " (first-real-line (:err reader-run)))
           (str "owned by U4a (read-end phase); current: " (first-real-line (:err reader-run)))))

;; #5 REPL *e / *1 -- *1 is probed by a value only *1 can produce
;; ((prn :star1 *1) after (+ 1 2)): with *1 nil this prints
;; ":star1 nil" and the check FAILs (F4)
(record! 5
         (if (and (includes? repl-text ":star1 3")
                  (includes? repl-text ":cause \"r\""))
           "PASS" "FAIL")
         (str "*1 readback "
              (if (includes? repl-text ":star1 3") (str "matched " (pr-str ":star1 3")) "not matched (:star1 3 absent)")
              "; *e #error print "
              (if (includes? repl-text ":cause \"r\"") (str "matched " (pr-str ":cause \"r\"")) "not matched")
              " in the REPL session (exit " (:exit repl-run) ")"))

;; #6 in-program trace access
(record! 6
         (if (includes? repl-text ":t1 [user$inner")
           "PASS" "FAIL")
         (str "first (.getStackTrace e) frame: "
              (or (some (fn [line] (when (str/includes? line ":t1") (str/trim line)))
                        (str/split-lines repl-text))
                  "none printed")))

;; #7 pst through the seam
;; #7 judged on pst's OWN output (F5): its header ("ExceptionInfo r
;; {:x 2}" -- class, message, data) and its tab-indented demunged
;; frame lines ("\tuser/inner (NO_SOURCE_FILE:0)"). U1's REPL report
;; line ("Execution error (ExceptionInfo) at user/inner (REPL:0).")
;; matches neither shape, so the check can no longer pass on it.
;; The frame-line needle accepts the host's own demunged name: on
;; jank the compiled fn is user$inner_11_1, demunged user/inner-11-1 --
;; the -N-N suffix is jank's real naming, not a seam defect (ruling,
;; John Newman via Toby, 2026-11-05: the needle absorbs the host
;; shape; the demunger does not falsify names to match the JVM's).
(def pst-frame-re #"\tuser/inner(-\d+-\d+)? \(")
(record! 7
         (if (and (includes? repl-text "ExceptionInfo r {:x 2}")
                  (re-find pst-frame-re repl-text))
           "PASS" "FAIL")
         (str "pst output matched " (pr-str "ExceptionInfo r {:x 2}")
              " and a frame line "
              (pr-str (or (some (fn [line] (when (re-find pst-frame-re line) (str/trim line)))
                                (str/split-lines repl-text))
                          "\tuser/inner ( -- none printed"))))

;; #8 *err* separation (U5's probe): exit 1; stdout carries the *out*
;; write and neither the *err* write nor the report; stderr carries
;; both the *err* write and the report
(record! 8
         (if (and (= 1 (:exit err-sep))
                  (includes? (:out err-sep) "to-out")
                  (not (includes? (:out err-sep) "to-err"))
                  (not (includes? (:out err-sep) "Execution error"))
                  (includes? (:err err-sep) "to-err" "Execution error"))
           "PASS" "FAIL")
         (str "exit " (:exit err-sep)
              "; stdout " (if (includes? (:out err-sep) "to-out") (str "matched " (pr-str "to-out")) "missing \"to-out\"")
              (if (includes? (:out err-sep) "to-err") ", LEAKED \"to-err\"" ", no \"to-err\"")
              (if (includes? (:out err-sep) "Execution error") ", LEAKED the report" ", no report")
              "; stderr " (if (includes? (:err err-sep) "to-err" "Execution error")
                            (str "matched " (pr-str "to-err") " and " (pr-str "Execution error"))
                            (str "first line: " (first-real-line (:err err-sep))))))
(record! 9 "SKIP" "owned by U4c (AOT report)")
;; #10 integer divide by zero: a catchable ArithmeticException (exit 1),
;; never a signal (136)
(record! 10
         (if (and (= 1 (:exit div-run)) (= 1 (:exit quot-run))
                  (includes? div-text "Divide by zero"))
           "PASS"
           "FAIL")
         (str "/ exit " (:exit div-run) ", quot exit " (:exit quot-run)
              "; matched " (pr-str "Divide by zero")
              " in " (pr-str (first-real-line (:err div-run)))))
;; #11 transform introspection: the launcher passes JANK_PRINT_CODEGEN
;; through and the rewritten forms' codegen prints, and a profile is
;; produced on request (jank only)
(record! 11
         (if (= target "jvm")
           "SKIP"
           (if (and (= 0 (:exit codegen))
                    (includes? codegen-text "cohost")
                    (= 0 (:exit profile-run))
                    profile-made?)
             "PASS"
             "FAIL"))
         (if (= target "jvm")
           "jank-only (no transform to introspect on the JVM host)"
           (str "codegen exit " (:exit codegen) ", matched line: "
                (pr-str (or (some (fn [line] (when (str/includes? line "cohost") (str/trim line)))
                                  (str/split-lines codegen-text))
                            "none"))
                "; profile exit " (:exit profile-run)
                ", jank.profile written: " (boolean profile-made?))))
(record! 12 "SKIP" "owned by U4c (gdb break-on-throw)")
;; #13 macro-expansion error: the bad load fails, the body-throwing
;; macro fails, and the reports identify the macro call (process
;; output + the runs' report files)
(record! 13
         (if (and (not= 0 (:exit bad-macro))
                  (not= 0 (:exit macro-throw))
                  (or (includes? macro-full "Expanded from this macro")
                      (includes? macro-full "macroexpanding")))
           "PASS"
           "FAIL")
         (str "load exit " (:exit bad-macro) ", throw exit " (:exit macro-throw) "; "
              (cond (includes? macro-full "macroexpanding") "report names the macroexpansion"
                    (includes? macro-full "Expanded from this macro") "diagnostic carries the macro provenance"
                    :else (first-real-line (:err macro-throw)))))
;; #14 exit codes, the research checklist's three cases (F6): an
;; uncaught -e throw exits 1; -X with a missing fn prints the message
;; and exits nonzero; a script calling (System/exit 3) exits 3
(record! 14
         (if (and (= 1 (:exit uncaught))
                  (not= 0 (:exit exec-missing))
                  (includes? exec-missing-text "function not found: nope")
                  (= 3 (:exit exit3-run)))
           "PASS" "FAIL")
         (str "-e throw exit " (:exit uncaught)
              "; -X missing fn exit " (:exit exec-missing)
              (if (includes? exec-missing-text "function not found: nope")
                (str ", matched " (pr-str "function not found: nope"))
                (str ", no missing-fn message: " (first-real-line (:err exec-missing))))
              "; script (System/exit 3) exit " (:exit exit3-run)))

(let [fails (count (filter (fn [[_ s _]] (= s "FAIL")) @results))
      passes (count (filter (fn [[_ s _]] (= s "PASS")) @results))]
  (println (str "debug-seam:check " target ": " passes "/14 PASS, " fails " FAIL"))
  (System/exit (if (zero? fails) 0 1)))
