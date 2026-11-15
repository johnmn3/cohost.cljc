;; Command-line runner: the host-side half of `bin/cohost` (doc/CLI.md).
;;
;; The launcher resolves the project's deps.edn with tools.deps (through the
;; Clojure CLI), turns every classpath entry into a source root -- directories
;; as they are, jars extracted under ~/.cache/cohost -- and writes one EDN
;; config, which -main reads:
;;
;;   {:stock-root "/abs/resources/cohost/clojure-1.12.0"
;;    :roots      ["/abs/project/src" "/abs/cache/jar-dir" ...]  ; tools.deps order
;;    :cwd        "/abs/project"
;;    :snapshot?  true      ; jank: boot from the fresh snapshot on the module path
;;    :report     "file"    ; clojure.main's uncaught-error report target: "file" | "stderr" | "none"
;;    :project-snapshot "cohost.project"  ; jank: the project's snapshot entry, when fresh
;;    :module-paths ["<dir>" ...]          ; jank's launcher: the project snapshot's directories, added at run time
;;    :args       ["-i" "init.clj" "-e" "(src)" "-m" "demo.main" "a"]}  ; clojure.main's own arguments
;;
;; -main is PREP only. It boots the stock core against :stock-root, registers
;; :roots with boot/add-source-root! (a dependency's namespace loads through
;; the ordinary loader, rewritten like everything else), sets :report as
;; stock's clojure.main.report property, makes the user namespace, and hands
;; :args, unparsed, to the booted stock clojure.main/main. Stock main parses
;; its own grammar (-i -e -m -r - script --report), binds
;; *command-line-args*, runs its own REPL and uncaught-error report, and
;; exits 1 itself on an error; a run main returns from ends with exit code 0.
;; -X arrives already presented as main arguments (bin/cohost's
;; exec-invoke-args).
(ns cohost.cli
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]
            #?@(:cljs [[cohost.hosts.cljs-eve :as cljs-eve]
                       [cohost.hosts.cljs-realm :as cljs-realm]])
            #?@(:jolt [[cohost.clj-champaca :as clj-champaca]])))

(defn- exit!
  "System/exit: the adapter's :exit (each host's own process exit)."
  [code]
  (host/exit code))

(defn- stock-main!
  "The booted stock clojure.main/main over args (strings), unparsed; -> 0
   when it returns (its error paths exit the process themselves)."
  [args]
  (boot/eval-str (str "(require 'clojure.main) (apply clojure.main/main (quote "
                      (pr-str (vec (map str args))) "))")
                 {:session? false})
  0)

(defn run-app-main!
  "The AOT entry: build:jank's generated cohost.app-main calls this
   with the app's namespace and the process arguments, after
   boot-from-snapshot!. An executable carries no launcher config: the
   run is stock main's -m, so an uncaught error from the app's -main
   gets clojure.main's contract exactly (its report, exit 1). Ends the
   process."
  [main-ns args]
  (exit! (stock-main! (into ["-m" (str main-ns)] args))))

(defn -main
  "args: [config-edn-path]. See the file header for the config shape."
  [& args]
  ;; unit 8: a worker realm runs this same image; its life is its entry
  (when-not #?(:cljs (cljs-eve/run-thread!) :default false)
  (let [cfg (host/read-string* (host/slurp-file (first args)))]
    ;; jolt hosting, champaca owning :clj: route :clj arms into this
    ;; process before user code is read. A sibling owner (basilisp or
    ;; jank hosting, jolt's champaca recorded as the leg) does not start
    ;; another process here.
    #?(:jolt (when (= :champaca (:clj-provider cfg))
               (clj-champaca/install! cfg)))
    ;; ClojureScript's and Phel's launches start in the repo (the cljs build
    ;; finds its files from the process's directory, Phel its config): the
    ;; project directory travels in the config
    #?(:cljs (when (:cwd cfg) (js/process.chdir (:cwd cfg)))
       :phel (when (:cwd cfg) (php/chdir (:cwd cfg)))
       :default nil)
    ;; :snapshot? -- the launcher found a fresh snapshot of the booted core on
    ;; the module path (jank: bin/cohost through dev/perf/jank_snapshot_cli.clj)
    (let [report (if (:snapshot? cfg)
                   (boot/boot-from-snapshot! {:root (:stock-root cfg)})
                   (boot/boot! (merge {:root (:stock-root cfg) :verbose (boolean (:verbose cfg))}
                                      ;; unit 8: the Node seat's heap is Eve's
                                      #?(:cljs (cljs-realm/core-boot-opts {:root (:stock-root cfg)
                                                                           :roots (vec (:roots cfg))})))))]
      (when (:verbose cfg) (boot/print-report report)))
    ;; require as a function loads through the loader on every seat: stock
    ;; main's -m is (doto (symbol main-ns) require), and a host's own
    ;; require looks on the host's classpath, where the roots are not
    (boot/install-runtime-loader!)
    (doseq [r (:roots cfg)] (boot/add-source-root! r))
    ;; :project-snapshot -- the module of a fresh per-project snapshot (the
    ;; project's namespaces compiled ahead, bin/cohost's jank launch): its
    ;; libraries load from their modules
    ;; :module-paths -- its directories, added for a host whose require has no
    ;; module path (jank's compiled launcher)
    (doseq [p (:module-paths cfg)] (host/add-module-path! p))
    ;; jank: a root carrying Eve's jank completion has it loaded FIRST, by
    ;; jank itself (a .jank file is the host's, cohost.scan/host-native-keys):
    ;; Eve's jank modules see the names it interns into jank's clojure.core
    ;; only when they are made after it (Eve's jank runner loads it first for
    ;; the same reason) -- the order bb boot:lpy keeps on basilisp
    ;; (lpy-eve-load). Measured: without it, cohost.eve-bridge's Eve requires
    ;; died "The symbol `satisfies?` could not be resolved".
    #?(:jank (when (some (fn [r] (host/try-all (fn [] (some? (host/slurp-file (str r "/eve/host_completion.jank"))))
                                               (fn [_] false)))
                         (:roots cfg))
               (host/require-host! 'eve.host-completion)))
    (when-let [m (:project-snapshot cfg)] (host/require-host! (symbol m)))
    ;; clojure.main's namespace: user, seeing the stock core (a session
    ;; starts in cohost.user, and moves there)
    (boot/make-user-ns!)
    (boot/make-user-ns! 'user)
    (boot/eval-str "(in-ns 'user)")
    ;; clojure.main's report target is its own property (report-error's
    ;; (System/getProperty "clojure.main.report" "file"))
    (when-let [r (:report cfg)]
      (boot/eval-str (str "(System/setProperty \"clojure.main.report\" " (pr-str (str r)) ")")
                     {:session? false}))
    (exit! (stock-main! (:args cfg))))))

#?(:squint nil :cljs (set! *main-cli-fn* -main))
