;; Command-line runner: the host-side half of `bin/cohost` (doc/CLI.md).
;;
;; The launcher resolves the project's deps.edn with tools.deps (through
;; the Clojure CLI), turns every classpath entry into a source root —
;; directories as they are, jars extracted under ~/.cache/cohost — and
;; writes a small EDN config. This namespace is the config's reader:
;;
;;   {:stock-root "/abs/path/resources/cohost/clojure-1.12.0"
;;    :roots      ["/abs/project/src" "/abs/cache/jar-dir" ...]  ; tools.deps order
;;    :action     {:type :eval|:main|:exec|:stdin
;;                 :exprs ["(+ 1 2)" ...]        ; :eval
;;                 :inits ["init.clj" ...]       ; :eval/:main/:exec (clj -i)
;;                 :ns "demo.main" :prog-args ["a"]            ; :main
;;                 :fn "demo.core/run" :exec-args-edn "{:x 1}"} ; :exec
;;    :verbose true?}
;;
;; It boots the stock core against :stock-root, registers :roots with
;; boot/add-source-root! (so `require` in booted code finds dependency
;; namespaces through the ordinary loader, rewritten like everything
;; else), and performs the one action. -e results are printed (a spike
;; deviation from clojure.main, which stays silent; see doc/CLI.md).
(ns cohost.cli
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]))

(def ^:private failures (atom 0))

(defn- fail! [msg]
  (println (str "cohost: " msg))
  (swap! failures inc))

(defn- attempt
  "Run f; on error record a failure and print it. -> f's value or nil."
  [f]
  (host/try-all f (fn [e] (fail! (str "ERR " (host/error-message e))) nil)))

(defn- eval-print! [src]
  (let [v (attempt (fn [] (boot/eval-str src)))]
    (when (some? v) (println (pr-str v)))))

(defn- run-action! [{:keys [type exprs inits ns exec-args-edn prog-args] fn-name :fn}]
  (doseq [f inits]
    (attempt (fn [] (boot/eval-str (host/slurp-file f)))))
  (case type
    :eval (doseq [src exprs] (eval-print! src))
    :stdin (let [src (attempt (fn [] (host/slurp-file "/dev/stdin")))]
             (when (some? src) (eval-print! src)))
    :main (attempt
           (fn []
             (let [target (boot/load-lib! (symbol ns))
                   v (host/interned-var target '-main)]
               (if v
                 (apply (host/var-root v) prog-args)
                 (fail! (str "no -main var in " ns))))))
    :exec (attempt
           (fn []
             (let [target (boot/load-lib! (symbol ns))
                   v (host/interned-var target (symbol fn-name))]
               (if v
                 (let [r ((host/var-root v) (host/read-string* exec-args-edn))]
                   (when (some? r) (println (pr-str r))))
                 (fail! (str "no var " fn-name " in " ns))))))
    (fail! (str "unknown action " type))))

(defn -main
  "args: [config-edn-path]. See the file header for the config shape."
  [& args]
  (let [cfg (host/read-string* (host/slurp-file (first args)))]
    ;; the cljs build's bootstrap resolves its own files against the
    ;; process cwd, so the launcher starts node in the repo and the
    ;; project directory travels in the config; adopt it before any
    ;; relative path in user code is resolved.
    #?(:cljs (when (:cwd cfg) (host/try-all (fn [] (js/process.chdir (:cwd cfg)))))
       :default nil)
    (let [report (boot/boot! {:root (:stock-root cfg) :verbose (boolean (:verbose cfg))})]
    (when (:verbose cfg) (boot/print-report report))
    (doseq [r (:roots cfg)] (boot/add-source-root! r))
    (boot/make-user-ns!)
    (run-action! (:action cfg))
    ;; a future's thread pool keeps the JVM (and jolt) alive a minute;
    ;; same shutdown as cohost.run
    #?(:jolt (shutdown-agents) :cljw (shutdown-agents) :clj (shutdown-agents) :default nil)
    (when (pos? @failures)
      #?(:cljs (.exit js/process 1)
         :cljw (System/exit 1)
         :jolt (System/exit 1)
         :clj (System/exit 1)
         :default nil))
    nil)))

#?(:cljs (set! *main-cli-fn* -main))
