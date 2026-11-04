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

(defn- report-error!
  "clojure.main's report of an uncaught error: the class and message of its
   innermost cause (the JVM's also names where it was thrown, and writes a
   full report to a file). A host's own wrapper of an error is a cause away."
  [e]
  (let [root (loop [x e n 0] (if-let [c (and (< n 8) (ex-cause x))] (recur c (inc n)) x))
        c (str (host/try-all (fn [] (shim/class-of root)) (fn [_] "java.lang.Exception")))
        i (host/last-index-of* c ".")]
    (err-println (str "Execution error (" (subs c (inc i)) ")."))
    (err-println (str (host/error-message root)))))

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

(defn- repl-var!
  "Set a REPL state var (*1 *2 *3 *e) in the booted core, as clojure.main's
   REPL maintains them. The root is set: the driver's own var-set! would
   land in whatever binding frame the current call happens to run in, and
   the session's later forms read the root. Best effort per host -- a host
   that cannot set them keeps the old REPL behavior."
  [sym v]
  (host/try-all
   (fn [] (when-let [h (host/interned-var boot/core-ns sym)] (host/set-var-root! h (fn [_] v))))
   (fn [_] nil)))

(defn- repl-shift!
  "clojure.main's REPL bookkeeping after a form evaluates to v: *1 is the
   last value, *2 and *3 the ones before it."
  [v]
  (let [get (fn [sym] (host/try-all
                       (fn [] (when-let [h (host/interned-var boot/core-ns sym)] (host/var-root h)))
                       (fn [_] nil)))]
    (repl-var! '*3 (get '*2))
    (repl-var! '*2 (get '*1))
    (repl-var! '*1 v)))

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
               (fn [e] (repl-var! '*e e) (report-error! e)))
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
