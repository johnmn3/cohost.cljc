;; Calm re-measurement driver for the cohost-jvm-speed lane.
;;
;; One side per process, so the two sides never share a JIT/GC window:
;;   clojure -Sdeps '{:paths ["src" "resources" "dev"]}' -M -m perf.jvm-speed stock <cases-csv> <out.edn>
;;   clojure -Sdeps '{:paths ["src" "resources" "dev"]}' -M -m perf.jvm-speed host  <cases-csv> <out.edn>
;;
;; Cases are cohost.bench/cases verbatim (same setup/body strings, same %N
;; substitution, scale 1). The stock side evaluates them through
;; boot/eval-str in cohost.user, exactly as cohost.bench's eval-stock does;
;; the host side evaluates them in a fresh namespace referring clojure.core,
;; as its eval-host does. Timing differs from cohost.bench on purpose:
;; a 1500 ms warm-up, then 9 reps of an adaptively sized batch (calibrated
;; to >= 200 ms per batch), reporting every rep's per-call ms so the
;; distribution -- not one median of a hot window -- is the evidence.
(ns perf.jvm-speed
  (:require [clojure.string :as str]))

(defn- now-ms [] (/ (double (System/nanoTime)) 1000000.0))

(defn- load1 []
  (try (Double/parseDouble (first (str/split (slurp "/proc/loadavg") #" ")))
       (catch Throwable _ -1.0)))

(defn- subst-n
  "bench.cljc's subst-n, copied: s with each %N replaced by n."
  [s n]
  (when s
    (loop [s s]
      (let [i (.indexOf ^String s "%N")]
        (if (neg? i) s (recur (str (subs s 0 i) n (subs s (+ i 2)))))))))

(defn- time-case
  "-> {:value :batch :per-call-ms [9 numbers]} or {:error msg}."
  [eval-fn setup body]
  (try
    (when setup (eval-fn setup))
    (let [f (eval-fn (str "(fn [] " body ")"))
          value (f)
          t0 (now-ms)]
      ;; warm-up: call until 1500 ms have passed
      (loop [] (f) (when (< (- (now-ms) t0) 1500.0) (recur)))
      ;; calibrate the batch so one batch takes >= 200 ms
      (let [b (loop [b 10]
                (let [t (now-ms) _ (dotimes [_ b] (f)) d (- (now-ms) t)]
                  (if (or (>= d 200.0) (>= b 200000)) b (recur (* b 4)))))]
        {:value (pr-str value)
         :batch b
         :per-call-ms (mapv (fn [_]
                              (let [t (now-ms)]
                                (dotimes [_ b] (f))
                                (/ (- (now-ms) t) b)))
                            (range 9))}))
    (catch Throwable e {:error (str (.getName (class e)) ": " (.getMessage e))})))

(defn- run-cases! [eval-fn cases out]
  (doseq [[id _label n0 setup body] cases]
    (let [n (max 1 (long n0))
          r (assoc (time-case eval-fn (subst-n setup n) (subst-n body n))
                   :case id :load1 (load1))]
      (spit out (str (pr-str r) "\n") :append true)
      (println (str "  " (name id) " -> " (if (:error r) (str "ERROR " (:error r)) "ok"))))))

(defn -main [& [side cases-csv out]]
  (let [wanted (set (map keyword (str/split cases-csv #",")))
        _ (require 'cohost.bench)
        cases (filter (fn [[id]] (contains? wanted id)) @(resolve 'cohost.bench/cases))]
    (spit out (str (pr-str {:side side :cases (mapv first cases) :load1 (load1)}) "\n"))
    (case side
      "stock"
      (do (require 'cohost.hosts.auto 'cohost.boot)
          (let [boot! (resolve 'cohost.boot/boot!)
                make-user-ns! (resolve 'cohost.boot/make-user-ns!)
                eval-str (resolve 'cohost.boot/eval-str)
                load-libs! (resolve 'cohost.bench/load-libs!)]
            (boot! {:root "resources/cohost/clojure-1.12.0"})
            (make-user-ns!)
            (load-libs!)
            ;; bench.cljc's stock side requires the cases' library alias
            ;; through the stock loader before timing
            (eval-str "(require [clojure.string :as bench-string])")
            (run-cases! (fn [src] (eval-str src {:session? false})) cases out)))
      "host"
      (let [n (create-ns 'perf.host-side)]
        (binding [*ns* n]
          (eval '(ns perf.host-side (:require [clojure.string :as bench-string]))))
        (run-cases! (fn [src] (binding [*ns* n] (eval (read-string src)))) cases out))
      (throw (ex-info "side must be stock|host" {:side side})))
    (spit out (str (pr-str {:done true :side side}) "\n") :append true)
    (shutdown-agents)
    (System/exit 0)))
