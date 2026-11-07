;; Entry point: install the host's adapter, boot core.clj, print the report,
;; run the behavior battery.
(ns cohost.run
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]
            [cohost.battery :as battery]
            [cohost.lib-battery :as lib-battery]
            [cohost.bench :as bench]
            #?@(:cljs [[cohost.hosts.cljs-eve :as cljs-eve]
                       [cohost.hosts.cljs-realm :as cljs-realm]])))

(defn- install-eve-atoms!
  "--eve-atoms (Eve-in-cohost unit 6 step 3): this seat's Eve atom hosting
   (cohost.atom-eve: the JVM's .clj, jank's .jank) installed before the boot,
   which then turns the atom layer on for the booted program (boot :eve-atoms)."
  []
  (host/eval-form '(require (quote cohost.atom-eve)))
  ((host/eval-form 'cohost.atom-eve/install!)))

(defn -main
  "args: [root] [--verbose] [--no-libs] [--eve-atoms] [--eve-collections | --no-eve-collections]
   [--bench [--scale=x] [--quick]] [--eval src ...]:
   --eve-atoms puts the booted program's atoms on Eve (unit 5); --eve-collections
   forces the Eve collection fill on, --no-eve-collections off (unit 4; absent,
   the seat's default);
   with --eval, every following argument is read and evaluated in the stock
   user namespace (printed as `src => value`) instead of running the battery;
   with --bench, the twelve libraries are loaded and cohost.bench's cases
   timed instead (--scale=x: each case at x times its size; --quick: one call
   per side, to check the cases run)."
  [& args]
  ;; unit 8: a worker realm runs this same image; its life is its entry
  (when-not #?(:cljs (cljs-eve/run-thread!) :default false)
  (let [[args evals] (split-with #(not= "--eval" %) args)
        evals (rest evals)
        flags (set (filter #(and (string? %) (= "--" (subs % 0 (min 2 (count %))))) args))
        root (or (first (remove flags args)) "resources/cohost/clojure-1.12.0")
        eve-atoms? (contains? flags "--eve-atoms")
        _ (when eve-atoms? (install-eve-atoms!))
        t0 (host/nano-time)
        report (boot/boot! (merge {:root root :verbose (contains? flags "--verbose")
                                   :eve-atoms eve-atoms?
                                   :eve-collections (cond (contains? flags "--eve-collections") true
                                                          (contains? flags "--no-eve-collections") false)}
                                  ;; unit 8: the Node seat's heap is Eve's
                                  #?(:cljs (cljs-realm/core-boot-opts {:root root :roots []}))))
        boot-ms (/ (double (- (host/nano-time) t0)) 1000000.0)]
    (boot/print-report report)
    #?(:cljs (boot/install-runtime-loader!))
    (boot/make-user-ns!)
    (cond
      (seq evals)
      (doseq [src evals]
        (println (str src "\n   => "
                      (host/try-all (fn [] (pr-str (boot/eval-str src)))
                                    (fn [e]
                                      (str "ERR " (host/error-message e)))))))
      (contains? flags "--bench")
      (bench/run-bench! (merge {:boot-ms boot-ms :libs-ms (bench/load-libs!)}
                               ;; --scale=x: every case at x times its size
                               (when-let [f (first (filter #(= "--scale=" (subs % 0 (min 8 (count %)))) flags))]
                                 {:scale (host/read-string* (subs f 8))})
                               ;; --quick: one untimed warm call and one timed call per side
                               (when (contains? flags "--quick") {:warm-ms 0 :budget-ms 0 :min-runs 1 :settle-ms 0})
                               ;; --git=rev, --note=text: recorded with the results
                               (when-let [f (first (filter #(= "--git=" (subs % 0 (min 6 (count %)))) flags))]
                                 {:git (subs f 6)})
                               (when-let [f (first (filter #(= "--note=" (subs % 0 (min 7 (count %)))) flags))]
                                 {:note (subs f 7)})))
      :else
      (do (battery/run-battery! boot/eval-str)
          (when-not (contains? flags "--no-libs")
            (lib-battery/run-lib-battery! boot/eval-str))))
    ;; a future's thread pool keeps the JVM (and jolt) alive a minute
    #?(:jolt (shutdown-agents) :cljw (shutdown-agents) :clj (shutdown-agents)
       :cljs (cljs-eve/shutdown!))
    nil)))

#?(:cljs (set! *main-cli-fn* -main))
