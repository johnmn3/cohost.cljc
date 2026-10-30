(ns cohost.run
  "Entry point: install the host's adapter, boot core.clj, print the report,
   run the behavior battery."
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]
            [cohost.battery :as battery]
            [cohost.lib-battery :as lib-battery]))

(defn -main
  "args: [root] [--verbose] [--no-libs] [--eval src ...]: with --eval, every following
   argument is read and evaluated in the stock user namespace (printed as
   `src => value`) instead of running the battery."
  [& args]
  (let [[args evals] (split-with #(not= "--eval" %) args)
        evals (rest evals)
        flags (set (filter #(and (string? %) (= "--" (subs % 0 (min 2 (count %))))) args))
        root (or (first (remove flags args)) "resources/cohost/clojure-1.12.0")
        report (boot/boot! {:root root :verbose (contains? flags "--verbose")})]
    (boot/print-report report)
    (boot/make-user-ns!)
    (if (seq evals)
      (doseq [src evals]
        (println (str src "\n   => "
                      (try (pr-str (boot/eval-str src))
                           (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) e
                             (str "ERR " (host/error-message e)))))))
      (do (battery/run-battery! boot/eval-str)
          (when-not (contains? flags "--no-libs")
            (lib-battery/run-lib-battery! boot/eval-str))))
    ;; a future's thread pool keeps the JVM (and jolt) alive a minute
    #?(:jolt (shutdown-agents) :clj (shutdown-agents))
    nil))

#?(:cljs (set! *main-cli-fn* -main))
