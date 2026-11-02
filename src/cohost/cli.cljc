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

(defn- exit! [code]
  #?(:cljs (.exit js/process code)
     :lpy (python/exit code)
     :phel (php/exit code)
     :lg (os/exit code)
     :jank (do (flush) (cpp/std.exit code))
     :default (do (flush) (System/exit code))))

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

(defn- repl!
  "A REPL over standard input read whole: each form's value after its
   namespace's prompt, as `clojure` prints them for input it is piped."
  []
  (println "Clojure 1.12.0")
  (let [last-ns (atom 'user)]
    (boot/eval-str (read-stdin)
                   {:on-value (fn [v ns] (print (str ns "=> ")) (stock-prn v) (reset! last-ns ns))})
    (println (str @last-ns "=> "))))

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
    (let [report (boot/boot! {:root (:stock-root cfg) :verbose (boolean (:verbose cfg))})]
      (when (:verbose cfg) (boot/print-report report)))
    (doseq [r (:roots cfg)] (boot/add-source-root! r))
    ;; clojure.main's namespace: user, seeing the stock core (a session
    ;; starts in cohost.user, and moves there)
    (boot/make-user-ns!)
    (boot/make-user-ns! 'user)
    (boot/eval-str "(in-ns 'user)")
    (exit! (run-cli! cfg))))

#?(:cljs (set! *main-cli-fn* -main))
