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
;;    :report     "file"    ; uncaught-error report target: "file" | "stderr" | "none"
;;    :report-dir "/tmp"    ; the directory report files are written to
;;    :project-snapshot "cohost.project"  ; jank: the project's snapshot entry, when fresh
;;    :module-paths ["<dir>" ...]          ; jank's launcher: the project snapshot's directories, added at run time
;;    :inits      [[:e "(src)"] [:i "/abs/init.clj"] ...]       ; in the order given
;;    :main       {:type :main :ns "demo.main" :args ["a"]}     ; -m
;;              | {:type :script :path "/abs/s.clj" :args [...]}
;;              | {:type :stdin :args [...]}                    ; -
;;              | {:type :repl}                                 ; none, or -r
;;              | {:type :exec :ns "demo.main" :fn "run" :args-edn "{:n 2}"}  ; -X
;;              | nil}                                          ; -e / -i alone
;;
;; It boots the stock core against :stock-root, registers :roots with
;; boot/add-source-root! (a dependency's namespace loads through the ordinary
;; loader, rewritten like everything else), and runs as clojure.main does --
;; clojure.run.exec for -X -- in a namespace named user: each -e form's value
;; is printed when it is not nil, a script's and -m's are not, and an error is
;; printed to stderr and ends the run with exit code 1.
(ns cohost.cli
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]
            [cohost.shim :as shim]))

(defn- err-println [s]
  #?(:cljs (binding [*print-fn* *print-err-fn*] (println s))
     :phel (php/fwrite php/STDERR (str s "\n"))
     :default (binding [*out* *err*] (println s))))

(defn- exit!
  "System/exit: the adapter's :exit (each host's own process exit)."
  [code]
  (host/exit code))

(defn- read-stdin
  "Standard input, read whole (Phel's slurp takes files alone)."
  []
  #?(:phel (php/stream_get_contents php/STDIN)
     :default (host/slurp-file "/dev/stdin")))

(defn- stock-prn
  "prn of the booted core: values print as on the JVM."
  [v]
  ((host/var-root (host/interned-var boot/core-ns 'prn)) v))

(def ^:private cli-config
  ;; the config -main read (its :report / :report-dir drive report-error!)
  (atom {}))

(defn- booted-ex-data
  "ex-data of the booted core, or nil."
  [e]
  (when-let [v (host/interned-var boot/core-ns 'ex-data)]
    (host/try-all (fn [] ((host/var-root v) e)) (fn [_] nil))))

(defn- display-frame
  "A trace frame as reports show it: its class under the display name
   (U8: a booted stock library's frames read clojure.*, not cohost.*)."
  [frame]
  (assoc frame 0 (shim/display-class-name (first frame))))

(defn- display-symbol
  [sym]
  (if-let [ns (namespace sym)]
    (symbol (str (shim/display-class-name (symbol ns))) (name sym))
    sym))

(defn- throwable->map
  "clojure.main's datafied throwable, built over adapter data: :via walks
   the cause chain outermost first (at most 8), each entry's :type from
   shim/class-of, :message and :at from the adapter (:at is the first of
   the entry's own :ex-trace frames); :trace is the outermost exception's
   frames; :phase from the top ex-data's :clojure.error/phase."
  [e]
  (let [chain (loop [acc [] cur e]
                (if (and cur (< (count acc) 8))
                  (recur (conj acc cur)
                         (host/try-all (fn [] (host/exception-cause cur)) (fn [_] nil)))
                  acc))
        via (mapv (fn [x]
                    (let [d (booted-ex-data x)]
                      (cond-> {:type (host/try-all (fn [] (shim/class-of x))
                                                   (fn [_] 'java.lang.Exception))
                               :message (host/exception-message x)
                               :at (when-let [f (first (host/ex-trace x))] (display-frame f))}
                        (some? d) (assoc :data d))))
                  chain)
        data (:data (first via))]
    {:via via
     :trace (mapv display-frame (host/ex-trace e))
     :cause (peek via)
     :data data
     :phase (get data :clojure.error/phase :execution)}))

(defn- split-on-$
  [s]
  (loop [acc [] from 0]
    (let [i (host/index-of s "$" from)]
      (if (neg? i) (conj acc (subs s from)) (recur (conj acc (subs s from i)) (inc i))))))

(defn- degen
  "clojure.main's: a munged name's captured-var suffix (--123) cut away."
  [s]
  (let [i (host/index-of s "--" 0)] (if (neg? i) s (subs s 0 i))))

(defn- java-loc->source
  "clojure.main's: a class + method back to the source symbol
   (cohost.core$map invoke -> cohost.core/map; a Java class + method
   stays class/method)."
  [clazz method]
  (if (contains? #{'invoke 'invokeStatic} method)
    (let [parts (map (fn [p] (degen (shim/Compiler-demunge p))) (split-on-$ (str clazz)))]
      (symbol (first parts) (apply str (interpose "$" (rest parts)))))
    (symbol (name clazz) (name method))))

(defn- file-name
  [full-path]
  (when full-path
    (let [i (host/last-index-of* full-path "/")]
      (if (neg? i) full-path (subs full-path (inc i))))))

(def ^:private core-prefixes ["clojure." "java." "jdk." "sun." "cohost." "jank."])

(defn- core-frame?
  "A frame of the runtime's own classes: clojure.main's ex-triage skips
   them to find the user's frame; the port skips by class-name prefix."
  [frame]
  (let [c (str (first frame))]
    (boolean (some (fn [p] (zero? (host/index-of c p 0))) core-prefixes))))

(defn- ex-triage
  "clojure.main's ex-triage, ported over the datafied throwable above."
  [datafied]
  (let [{:keys [via trace phase] :or {phase :execution}} datafied
        {:keys [type message data]} (peek via)
        problems (:clojure.spec.alpha/problems data)
        sfn (:clojure.spec.alpha/fn data)
        caller (:clojure.spec.test.alpha/caller data)
        top-data (:data (first via))
        source (:clojure.error/source top-data)
        no-source? (contains? #{"NO_SOURCE_FILE" "NO_SOURCE_PATH"} source)]
    (assoc
     (case phase
       :read-source
       (cond-> (merge (or (:data (second via)) {}) top-data)
         source (assoc :clojure.error/source (file-name source) :clojure.error/path source)
         no-source? (dissoc :clojure.error/source :clojure.error/path)
         message (assoc :clojure.error/cause message))

       (:compile-syntax-check :compilation :macro-syntax-check :macroexpansion)
       (cond-> top-data
         source (assoc :clojure.error/source (file-name source) :clojure.error/path source)
         no-source? (dissoc :clojure.error/source :clojure.error/path)
         type (assoc :clojure.error/class type)
         message (assoc :clojure.error/cause message)
         problems (assoc :clojure.error/spec data))

       (:read-eval-result :print-eval-result)
       (let [[fsource fmethod file line] (first trace)]
         (cond-> top-data
           line (assoc :clojure.error/line line)
           file (assoc :clojure.error/source file)
           (and fsource fmethod) (assoc :clojure.error/symbol (display-symbol (java-loc->source fsource fmethod)))
           type (assoc :clojure.error/class type)
           message (assoc :clojure.error/cause message)))

       ;; :execution
       (let [[fsource fmethod file line] (first (drop-while core-frame? trace))
             file (first (remove (fn [f] (or (nil? f) (contains? #{"NO_SOURCE_FILE" "NO_SOURCE_PATH"} f)))
                                 [(:file caller) file]))
             err-line (or (:line caller) line)]
         (cond-> {:clojure.error/class type}
           err-line (assoc :clojure.error/line err-line)
           message (assoc :clojure.error/cause message)
           (or sfn (and fsource fmethod)) (assoc :clojure.error/symbol (or sfn (display-symbol (java-loc->source fsource fmethod))))
           file (assoc :clojure.error/source file)
           problems (assoc :clojure.error/spec data))))
     :clojure.error/phase phase)))

(defn- ex-str
  "clojure.main's ex-str, its wording exactly, built with str. (A spec
   error's explain text is the one piece not ported: the cause prints,
   and the explain-data rides the report file.)"
  [triage]
  (let [{:clojure.error/keys [phase source path line column symbol class cause]} triage
        loc (str (or path source "REPL") ":" (or line 1) (when column (str ":" column)))
        class-name (name (or class ""))
        simple-class (let [i (host/last-index-of* class-name ".")]
                       (if (neg? i) class-name (subs class-name (inc i))))
        cause-type (if (contains? #{"Exception" "RuntimeException"} simple-class)
                     "" (str " (" simple-class ")"))
        sym (if symbol (str symbol " ") "")]
    (case phase
      :read-source (str "Syntax error reading source at (" loc ").\n" cause "\n")
      :macro-syntax-check (str "Syntax error macroexpanding " sym "at (" loc ").\n" cause "\n")
      :macroexpansion (str "Unexpected error" cause-type " macroexpanding " sym "at (" loc ").\n" cause "\n")
      :compile-syntax-check (str "Syntax error" cause-type " compiling " sym "at (" loc ").\n" cause "\n")
      :compilation (str "Unexpected error" cause-type " compiling " sym "at (" loc ").\n" cause "\n")
      :read-eval-result (str "Error reading eval result" cause-type " at " symbol " (" loc ").\n" cause "\n")
      :print-eval-result (str "Error printing return value" cause-type " at " symbol " (" loc ").\n" cause "\n")
      (str "Execution error" cause-type " at " sym "(" loc ").\n" cause "\n"))))

(def ^:private report-count (atom 0))

(defn- repl-report!
  "The REPL prints the message only, as clojure.main's does."
  [e]
  (err-println (ex-str (ex-triage (throwable->map e)))))

(defn- report-error!
  "clojure.main's uncaught-error report. The config's :report names the
   target: \"file\" (the default) writes {:clojure.main/message
   :clojure.main/triage :clojure.main/trace} as EDN to
   <:report-dir>/cohost-<millis>-<n>.edn and prints the message and the
   path to stderr; \"stderr\" prints the report and the message; \"none\"
   prints nothing. No :report-dir, or a failed write, falls back to
   stderr."
  [e]
  (host/try-all
   (fn []
     (let [target (get @cli-config :report "file")]
       (when-not (= target "none")
         (let [datafied (throwable->map e)
               triage (ex-triage datafied)
               message (ex-str triage)
               report {:clojure.main/message message
                       :clojure.main/triage triage
                       :clojure.main/trace datafied}
               report-str (binding [*print-namespace-maps* false] (pr-str report))
               path (when (= target "file")
                      (when-let [dir (:report-dir @cli-config)]
                        (host/try-all
                         (fn [] (let [p (str dir "/cohost-" (host/current-time-millis)
                                             "-" (swap! report-count inc) ".edn")]
                                  (host/spit-file p (str report-str "\n"))
                                  p))
                         (fn [_] nil))))]
           (if path
             (err-println (str message "\nFull report at:\n" path))
             (err-println (str report-str "\n" message)))))))
   (fn [_] nil)))

(defn- set-command-line-args!
  "The root, and the binding where the host's main runs inside one (jolt's
   -m, as clojure.main does)."
  [args]
  (let [q (str "'" (pr-str (seq args)))]
    (boot/eval-str (str "(alter-var-root #'*command-line-args* (constantly " q "))"
                        "(try (set! *command-line-args* " q ") (catch Throwable _ nil))")
                   {:session? false})))

#?(:jank (cpp/raw "#include <iostream>
#include <string>
namespace cohost_io {
  using namespace jank::runtime;
  inline object_ref read_line() {
    std::string s;
    if(!std::getline(std::cin, s)) { return jank_nil; }
    return make_box<obj::persistent_string>(jtl::immutable_string(s));
  }
}"))

(defn- read-line*
  "One line of standard input without its newline, or nil at its end."
  []
  #?(:cljs (let [fs (js/require "fs")
                 buf (js/Buffer.alloc 1)
                 text (fn [acc] (.toString (.from js/Buffer (into-array acc)) "utf8"))]
             (loop [acc []]
               (let [n (try (.readSync fs 0 buf 0 1 nil)
                            (catch :default e (if (= "EAGAIN" (.-code e)) -1 0)))]
                 (cond (neg? n) (recur acc)
                       (zero? n) (when (seq acc) (text acc))
                       (= 10 (aget buf 0)) (text acc)
                       :else (recur (conj acc (aget buf 0)))))))
     :phel (let [l (php/fgets php/STDIN)] (if (= l false) nil (php/rtrim l "\r\n")))
     ;; basilisp's read-line does not answer nil at the end of input
     :lpy (try (python/input) (catch python/EOFError _ nil))
     ;; jank 0.1's read-line throws "TODO: port read-line"
     :jank (cpp/cohost_io.read_line)
     :default (read-line)))

(defn- prompt!
  "The prompt, with no newline after it."
  [s]
  #?(:cljs (.write (.-stdout js/process) s)
     ;; jank 0.1's flush throws "TODO: port flesh"
     :jank (print s)
     :phel (print s)
     ;; let-go has no flush
     :lg (print s)
     :default (do (print s) (flush))))

(defn- complete?
  "Do the lines read so far end outside every string and bracket, with a
   form in them? (A comment runs to the line's end; a backslash starts a character.)"
  [s]
  (let [n (count s)]
    (loop [i 0 depth 0 in-str? false form? false]
      (if (>= i n)
        (and form? (not in-str?) (<= depth 0))
        (let [c (subs s i (inc i))]
          (cond
            in-str? (cond (= c "\\") (recur (+ i 2) depth true form?)
                          (= c "\"") (recur (inc i) depth false form?)
                          :else (recur (inc i) depth true form?))
            (= c "\"") (recur (inc i) depth true true)
            (= c "\\") (recur (+ i 2) depth false true)
            (= c ";") (recur (loop [j i] (if (or (>= j n) (= "\n" (subs s j (inc j)))) j (recur (inc j))))
                             depth false form?)
            (contains? #{"(" "[" "{"} c) (recur (inc i) (inc depth) false true)
            (contains? #{")" "]" "}"} c) (recur (inc i) (dec depth) false true)
            (contains? #{" " "\n" "\t" "\r" ","} c) (recur (inc i) depth false form?)
            :else (recur (inc i) depth false true)))))))

(def ^:private repl-vars
  ;; The booted core's *1 *2 *3 *e var handles, fetched once (lazily, on the
  ;; first REPL): the values of one eval in the session's user namespace.
  (atom nil))

(defn- repl-var-handles! []
  (or @repl-vars
      (reset! repl-vars
              (host/try-all
               (fn [] (boot/eval-str "[#'*1 #'*2 #'*3 #'*e]" {:session? false}))
               (fn [_] nil)))))

(defn- repl-set!
  "Set one REPL state var the way clojure.main does: the thread binding
   when this host thread-binds it (the JVM host does, under clojure.main),
   else its root. Hosts with neither are a no-op (try-all)."
  [h v]
  (when h
    (host/try-all
     (fn [] (if (host/var-thread-bound? h)
              (host/var-set! h v)
              (host/set-var-root! h (fn [_] v))))
     (fn [_] nil))))

(defn- repl-shift!
  "clojure.main's REPL bookkeeping after a form evaluates to v: *3 takes
   *2's value, *2 takes *1's, *1 takes v."
  [v]
  (when-let [[v1 v2 v3 _] (repl-var-handles!)]
    (repl-set! v3 (host/try-all (fn [] (host/var-root v2)) (fn [_] nil)))
    (repl-set! v2 (host/try-all (fn [] (host/var-root v1)) (fn [_] nil)))
    (repl-set! v1 v)))

(defn- repl-error!
  "After a form throws in the REPL: *e takes the exception; no shift."
  [e]
  (when-let [[_ _ _ ve] (repl-var-handles!)]
    (repl-set! ve e)))

(defn- repl!
  "clojure.main's REPL, a line at a time: a prompt, lines read until they
   hold whole forms, each form's value printed (the forms of one line under
   one prompt, as `clojure` prints them); an error is reported and the REPL
   goes on; the end of input ends it."
  []
  (println "Clojure 1.12.0")
  (loop [buf "" prompt? true ns 'user]
    (when prompt? (prompt! (str ns "=> ")))
    (let [line (read-line*)]
      (if (nil? line)
        (println)
        (let [buf (str buf line "\n")]
          (if (complete? buf)
            (let [last-ns (atom ns)]
              (host/try-all
               (fn [] (boot/eval-str buf {:on-value (fn [v n] (stock-prn v) (repl-shift! v) (reset! last-ns n))}))
               (fn [e] (repl-error! e) (repl-report! e)))
              (recur "" true @last-ns))
            (recur buf false ns)))))))

(defn- run-main!
  "-> the exit code."
  [{:keys [type ns args path] fn-name :fn :as main}]
  (case type
    :main (let [v (host/interned-var (boot/load-lib! (symbol ns)) '-main)]
            (when-not v (throw (host/ex* 'java.lang.IllegalArgumentException (str "No -main in namespace " ns))))
            (apply (host/var-root v) args)
            0)
    :script (do (boot/eval-str (host/slurp-file path)) 0)
    :stdin (do (boot/eval-str (read-stdin)) 0)
    :repl (do (repl!) 0)
    ;; clojure.run.exec: the fn's value is not printed
    :exec (let [v (host/interned-var (boot/load-lib! (symbol ns)) (symbol fn-name))]
            (if v
              (do ((host/var-root v) (host/read-string* (:args-edn main))) 0)
              (do (err-println (str "Namespace " ns " loaded but function not found: " fn-name)) 1)))))

(defn- run-cli!
  "-> the exit code."
  [{:keys [inits main]}]
  (host/try-all
   (fn []
     ;; clojure.main sets them before the init options run
     (when (contains? main :args)
       (host/try-all (fn [] (set-command-line-args! (:args main))) (fn [_] nil)))
     (doseq [[k x] inits]
       (case k
         :e (boot/eval-str x {:on-value (fn [v _] (when (some? v) (stock-prn v)))})
         :i (boot/eval-str (host/slurp-file x))))
     (if main (run-main! main) 0))
   (fn [e] (report-error! e) 1)))

(defn -main
  "args: [config-edn-path]. See the file header for the config shape."
  [& args]
  (let [cfg (host/read-string* (host/slurp-file (first args)))]
    (reset! cli-config cfg)
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
                   (boot/boot! {:root (:stock-root cfg) :verbose (boolean (:verbose cfg))}))]
      (when (:verbose cfg) (boot/print-report report)))
    (doseq [r (:roots cfg)] (boot/add-source-root! r))
    ;; :project-snapshot -- the module of a fresh per-project snapshot (the
    ;; project's namespaces compiled ahead, bin/cohost's jank launch): its
    ;; libraries load from their modules
    ;; :module-paths -- its directories, added for a host whose require has no
    ;; module path (jank's compiled launcher)
    (doseq [p (:module-paths cfg)] (host/add-module-path! p))
    (when-let [m (:project-snapshot cfg)] (host/require-host! (symbol m)))
    ;; clojure.main's namespace: user, seeing the stock core (a session
    ;; starts in cohost.user, and moves there)
    (boot/make-user-ns!)
    (boot/make-user-ns! 'user)
    (boot/eval-str "(in-ns 'user)")
    (exit! (run-cli! cfg))))

#?(:cljs (set! *main-cli-fn* -main))
