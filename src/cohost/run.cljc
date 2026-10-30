(ns cohost.run
  "Entry point: install the host's adapter, boot core.clj, print the report,
   run the behavior battery."
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]
            [cohost.battery :as battery]
            [cohost.lib-battery :as lib-battery]
            [cohost.bench :as bench]))

(defn -main
  "args: [root] [--verbose] [--no-libs] [--bench [--quick]] [--eval src ...]: with
   --eval, every following argument is read and evaluated in the stock user
   namespace (printed as `src => value`) instead of running the battery; with
   --bench, the twelve libraries are loaded and cohost.bench's cases timed
   instead (--quick: one call per side, to check the cases run)."
  [& args]
  (let [[args evals] (split-with #(not= "--eval" %) args)
        evals (rest evals)
        flags (set (filter #(and (string? %) (= "--" (subs % 0 (min 2 (count %))))) args))
        root (or (first (remove flags args)) "resources/cohost/clojure-1.12.0")
        t0 (host/nano-time)
        report (boot/boot! {:root root :verbose (contains? flags "--verbose")})
        boot-ms (/ (double (- (host/nano-time) t0)) 1000000.0)]
    (boot/print-report report)
    (boot/make-user-ns!)
    (cond
      (seq evals)
      (doseq [src evals]
        (println (str src "\n   => "
                      (try (pr-str (boot/eval-str src))
                           (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) e
                             (str "ERR " (host/error-message e)))))))
      (contains? flags "--bench")
      (bench/run-bench! (merge {:boot-ms boot-ms :libs-ms (bench/load-libs!)}
                               ;; --quick: one untimed warm call and one timed call per side
                               (when (contains? flags "--quick") {:warm-ms 0 :budget-ms 0 :min-runs 1})))
      :else
      (do (battery/run-battery! boot/eval-str)
          (when-not (contains? flags "--no-libs")
            (lib-battery/run-lib-battery! boot/eval-str))))
    ;; a future's thread pool keeps the JVM (and jolt) alive a minute
    #?(:jolt (shutdown-agents) :clj (shutdown-agents))
    nil))

#?(:cljs (set! *main-cli-fn* -main))
