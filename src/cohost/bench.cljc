;; Throughput: each case is one program, compiled once in a namespace that
;; sees only the booted stock core (cohost.user) and once in a namespace
;; that sees the host's own core, then timed in the same process: a warm-up,
;; then calls until a time budget is spent; the number is the median call.
;; Both sides must return the same value, or the case is reported as
;; differing rather than timed. On JVM Clojure the host's own core is JVM
;; Clojure itself, the baseline the other hosts are read against.
(ns cohost.bench
  (:require [cohost.adapter :as host]
            [cohost.boot :as boot]))

(def cases
  "[id label n setup body]: setup (a string, or nil) is evaluated once per
   side; body is the body of a thunk, timed per call. %N in either is the
   case's size n (times the run's scale); the sizes are the same on every
   host that runs at the same scale."
  [[:fn-call "a defn called in a loop" 100000 "(defn bench-inc [x] (+ x 1))"
    "(loop [i 0 acc 0] (if (< i %N) (recur (inc i) (bench-inc acc)) acc))"]
   [:loop-arith "loop/recur arithmetic" 200000 nil
    "(loop [i 0 acc 0] (if (< i %N) (recur (inc i) (+ acc (* i 2))) acc))"]
   [:reduce-range "reduce + over a range" 200000 nil
    "(reduce + (range %N))"]
   [:lazy-pipeline "count, filter, map over a range" 100000 nil
    "(count (filter odd? (map inc (range %N))))"]
   [:transduce "transduce (comp (map inc) (filter odd?))" 100000 nil
    "(transduce (comp (map inc) (filter odd?)) + (range %N))"]
   [:into-vector "into [] with a map transducer" 100000 nil
    "(count (into [] (map inc) (range %N)))"]
   [:conj-vector "reduce conj [] (persistent vector)" 100000 nil
    "(count (reduce conj [] (range %N)))"]
   [:transient-vector "conj! into a transient vector" 100000 nil
    "(count (persistent! (reduce conj! (transient []) (range %N))))"]
   [:assoc-map "assoc into a map" 50000 nil
    "(count (reduce (fn [m i] (assoc m i (* i i))) {} (range %N)))"]
   [:map-lookup "get from a 1,000-key map" 100000 "(def bench-m (zipmap (range 1000) (range 1000)))"
    "(reduce (fn [acc i] (+ acc (get bench-m (mod i 1000)))) 0 (range %N))"]
   [:keyword-lookup "keyword lookup on small maps" 100000 nil
    "(reduce (fn [acc m] (+ acc (:a m) (:b m))) 0 (repeat %N {:a 1 :b 2}))"]
   [:destructure "map destructuring in fn args" 100000 nil
    "(reduce (fn [acc {:keys [a b]}] (+ acc a b)) 0 (repeat %N {:a 1 :b 2}))"]
   [:frequencies "frequencies" 100000 nil
    "(count (frequencies (map (fn [i] (mod i 97)) (range %N))))"]
   [:group-by "group-by into 31 groups" 100000 nil
    "(count (group-by (fn [i] (mod i 31)) (range %N)))"]
   [:sort "sort numbers" 50000 nil
    "(first (sort (map (fn [i] (mod (* i 7919) 10007)) (range %N))))"]
   [:sort-by "sort-by a key of maps" 20000 "(def bench-recs (mapv (fn [i] {:k (mod (* i 7919) 10007) :i i}) (range %N)))"
    "(:i (first (sort-by :k bench-recs)))"]
   [:set-build "build a set" 100000 nil
    "(count (into #{} (map (fn [i] (mod i 1000))) (range %N)))"]
   [:distinct "distinct over mapcat" 50000 nil
    "(count (distinct (mapcat (fn [i] [i (inc i)]) (range %N))))"]
   [:partition "partition 3" 100000 nil
    "(count (partition 3 (range %N)))"]
   [:lazy-iterate "nth of a lazy iterate" 20000 nil
    "(first (nth (iterate (fn [[a b]] [b (mod (+ a b) 1000007)]) [0 1]) %N))"]
   [:atom-swap "swap! an atom" 100000 nil
    "(let [a (atom 0)] (dotimes [i %N] (swap! a + i)) @a)"]
   [:protocol "protocol call on a record" 50000 "(do (defprotocol BenchArea (bench-area [s])) (defrecord BenchSq [w] BenchArea (bench-area [_] (* w w))))"
    "(reduce (fn [acc i] (+ acc (bench-area (->BenchSq i)))) 0 (range %N))"]
   [:multimethod "multimethod call" 50000 "(do (defmulti bench-mm (fn [x] (mod x 3))) (defmethod bench-mm 0 [x] x) (defmethod bench-mm 1 [x] (* 2 x)) (defmethod bench-mm :default [x] 0))"
    "(reduce (fn [acc i] (+ acc (bench-mm i))) 0 (range %N))"]
   [:equality "= of two equal vectors" 100000 "(do (def bench-v1 (vec (range %N))) (def bench-v2 (vec (range %N))))"
    "(= bench-v1 bench-v2)"]
   [:str-build "apply str over numbers" 20000 nil
    "(count (apply str (map str (range %N))))"]
   [:string-join "clojure.string/join" 20000 nil
    "(count (bench-string/join \",\" (range %N)))"]
   [:regex "re-seq over a string of numbers" 5000 "(def bench-s (apply str (interpose \" \" (range %N))))"
    "(count (re-seq #\"\\d+\" bench-s))"]
   [:format "format" 5000 nil
    "(count (apply str (map (fn [i] (format \"%05d|%s\" i \"x\")) (range %N))))"]
   [:pr-str "pr-str of small maps" 2000 "(def bench-data (vec (for [i (range %N)] {:id i :name (str \"n\" i) :tags [:a :b]})))"
    "(count (pr-str bench-data))"]])

(def ^:private host-ns 'cohost.bench-host)

(def ^:private requires
  "Libraries the cases name by alias, required once per side: in the host
   namespace's ns form (self-hosted ClojureScript cannot re-declare a
   namespace for a later require), and through cohost's loader on the
   stock side."
  '[[clojure.string :as bench-string]])

(defn- ensure-host-ns! []
  ;; self-hosted ClojureScript: declared from cljs.user, as the adapter declares
  ;; its own namespaces, with the $macros companion its eval aliases each new
  ;; def into
  #?(:cljs (host/with-ns 'cljs.user (fn [] (host/eval-form (list 'ns (symbol (str host-ns "$macros"))))))
     :default nil)
  (host/with-ns #?(:cljs 'cljs.user :default (host/core-ns-sym))
    (fn [] (host/eval-form (list 'ns host-ns (cons :require requires))))))

(defn- eval-host [src]
  (host/with-ns host-ns (fn [] (host/eval-form (host/read-string* src)))))

(defn- eval-stock [src] (boot/eval-str src {:session? false}))

(defn- now-ms [] (/ (double (host/nano-time)) 1000000.0))

(defn- median [xs]
  (let [s (vec (sort xs)) n (count s)]
    (if (odd? n) (nth s (quot n 2)) (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0))))

(defn- measure
  "Call f once (the cold call), then until warm-ms have passed, then time
   calls until at least min-runs are taken and budget-ms are spent (at most
   max-runs). -> {:value :first :median :min :runs}"
  [f {:keys [warm-ms budget-ms min-runs max-runs]}]
  (let [t0 (now-ms)
        v (f)
        first-ms (- (now-ms) t0)]
    (loop [] (when (< (- (now-ms) t0) warm-ms) (f) (recur)))
    (loop [samples [] spent 0.0]
      (if (or (>= (count samples) max-runs)
              (and (>= (count samples) min-runs) (>= spent budget-ms)))
        {:value v :first first-ms :median (median samples) :min (apply min samples) :runs (count samples)}
        (let [a (now-ms) _ (f) d (- (now-ms) a)]
          (recur (conj samples d) (+ spent d)))))))

(defn- side
  "Set up and time one case on one side; -> the measurement, or {:error msg}."
  [eval-fn setup body opts]
  (host/try-all
   (fn []
     (when setup (eval-fn setup))
     (measure (eval-fn (str "(fn [] " body ")")) opts))
   (fn [e]
     {:error (host/error-message e)})))

(defn- fmt-ms [x]
  (if (number? x) (str (/ (int (* 100.0 x)) 100.0)) "-"))

(defn- write-results! [path s]
  #?(:cljs (if (and (exists? js/process) (exists? js/require))
             (.writeFileSync (js/require "fs") path s)
             (println (str "BENCH-EDN " s)))
     :default (host/spit-file path s)))

(def libs
  '[clojure.walk clojure.set clojure.string clojure.zip clojure.data clojure.edn
    clojure.template clojure.test clojure.math clojure.stacktrace clojure.repl
    clojure.spec.alpha])

(defn load-libs!
  "Require the twelve clojure.* libraries through cohost's loader, from their
   sources; -> the milliseconds it took."
  []
  (let [t0 (now-ms)]
    (eval-stock (str "(require " (apply str (interpose " " (map #(str "'" %) libs))) ")"))
    (- (now-ms) t0)))

(def default-opts {:warm-ms 500 :budget-ms 1000 :min-runs 3 :max-runs 2000 :settle-ms 250})

(declare subst-n)

(defn- settle!
  "Run every case on both sides, untimed, for settle-ms each, before any case
   is timed: a host with a JIT (the JVM, V8) is still compiling the boot's code
   for seconds after it, and the first cases timed would pay for that."
  [cases scale {:keys [settle-ms]}]
  (when (pos? settle-ms)
    (doseq [[_ _ n0 setup body] cases
            ev [eval-host eval-stock]]
      (let [n (max 1 (long (* n0 scale)))]
        (host/try-all (fn [] (when setup (ev (subst-n setup n)))
                             (let [f (ev (str "(fn [] " (subst-n body n) ")")) t0 (now-ms)]
                               (loop [] (f) (when (< (- (now-ms) t0) settle-ms) (recur))))))))))

(defn- subst-n
  "s with each %N replaced by n."
  [s n]
  (when s
    (loop [s s]
      (let [i (host/index-of s "%N" 0)]
        (if (neg? i) s (recur (str (subs s 0 i) n (subs s (+ i 2)))))))))

(defn run-bench!
  "Time every case on both sides; print a table; write the results as EDN to
   target/bench-<label>.edn, or target/bench-<label>-s<scale>.edn when the
   scale is not 1 (in a browser, print them on a BENCH-EDN line). opts:
   :label (the host's name in the results), :scale (each case's size n times
   this; default 1), :boot-ms and :libs-ms (the boot's and the twelve
   libraries' load times, recorded with the results), and default-opts' keys."
  [{:keys [label boot-ms libs-ms scale] :or {scale 1} :as opts}]
  (let [opts (merge default-opts opts)
        label (or label (name (host/host-name)))]
    (ensure-host-ns!)
    (eval-stock (str "(require " (apply str (interpose " " (map #(str "'" (pr-str %)) requires))) ")"))
    (settle! cases scale opts)
    (println (str "bench on " label (when (not= 1 scale) (str " at scale " scale))
                  ": median ms per call, the host's own core vs the stock core"))
    (let [results
          (doall
           (map (fn [[id title n0 setup body]]
             (let [n (max 1 (long (* n0 scale)))
                   setup (subst-n setup n)
                   body (subst-n body n)
                   h (side eval-host setup body opts)
                   s (side eval-stock setup body opts)
                   same? (and (not (:error h)) (not (:error s)) (= (:value h) (:value s)))
                   r {:id id :label title :n n
                      :host (dissoc h :value) :stock (dissoc s :value)
                      :same? same?
                      :host-value (pr-str (:value h)) :stock-value (pr-str (:value s))}]
               (println (str "  " (name id) "  host " (fmt-ms (:median h)) "  stock " (fmt-ms (:median s))
                             (when (and (:median h) (:median s) (pos? (:median h)))
                               (str "  x" (fmt-ms (/ (:median s) (:median h)))))
                             (when-not same?
                               (str "  [" (or (:error h) (:error s) (str "differ: " (:value h) " vs " (:value s))) "]"))))
               r))
                cases))
          out {:host label :scale scale :boot-ms boot-ms :libs-ms libs-ms
               :opts (dissoc opts :label :boot-ms :libs-ms :scale) :cases (vec results)}]
      (write-results! (str "target/bench-" label (when (not= 1 scale) (str "-s" scale)) ".edn")
                      (binding [*print-length* nil *print-level* nil] (pr-str out)))
      out)))
