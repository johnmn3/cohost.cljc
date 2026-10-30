(ns cohost.bench
  "Throughput: each case is one program, compiled once in a namespace that
   sees only the booted stock core (cohost.user) and once in a namespace
   that sees the host's own core, then timed in the same process: a warm-up,
   then calls until a time budget is spent; the number is the median call.
   Both sides must return the same value, or the case is reported as
   differing rather than timed. On JVM Clojure the host's own core is JVM
   Clojure itself, the baseline the other hosts are read against."
  (:require [cohost.adapter :as host]
            [cohost.boot :as boot]))

(def cases
  "[id label setup body]: setup (a string, or nil) is evaluated once per
   side; body is the body of a thunk, timed per call. The sizes are the same
   on every host."
  [[:fn-call "a defn called 100k times" "(defn bench-inc [x] (+ x 1))"
    "(loop [i 0 acc 0] (if (< i 100000) (recur (inc i) (bench-inc acc)) acc))"]
   [:loop-arith "loop/recur arithmetic, 200k steps" nil
    "(loop [i 0 acc 0] (if (< i 200000) (recur (inc i) (+ acc (* i 2))) acc))"]
   [:reduce-range "reduce + over (range 200000)" nil
    "(reduce + (range 200000))"]
   [:lazy-pipeline "count, filter, map over a range" nil
    "(count (filter odd? (map inc (range 100000))))"]
   [:transduce "transduce (comp (map inc) (filter odd?))" nil
    "(transduce (comp (map inc) (filter odd?)) + (range 100000))"]
   [:into-vector "into [] with a map transducer" nil
    "(count (into [] (map inc) (range 100000)))"]
   [:conj-vector "reduce conj [] (persistent vector)" nil
    "(count (reduce conj [] (range 100000)))"]
   [:transient-vector "conj! into a transient vector" nil
    "(count (persistent! (reduce conj! (transient []) (range 100000))))"]
   [:assoc-map "assoc 50k keys into a map" nil
    "(count (reduce (fn [m i] (assoc m i (* i i))) {} (range 50000)))"]
   [:map-lookup "get from a 1,000-key map, 100k times" "(def bench-m (zipmap (range 1000) (range 1000)))"
    "(reduce (fn [acc i] (+ acc (get bench-m (mod i 1000)))) 0 (range 100000))"]
   [:keyword-lookup "keyword lookup on small maps, 100k" nil
    "(reduce (fn [acc m] (+ acc (:a m) (:b m))) 0 (repeat 100000 {:a 1 :b 2}))"]
   [:destructure "map destructuring in fn args, 100k" nil
    "(reduce (fn [acc {:keys [a b]}] (+ acc a b)) 0 (repeat 100000 {:a 1 :b 2}))"]
   [:frequencies "frequencies over 100k values" nil
    "(count (frequencies (map (fn [i] (mod i 97)) (range 100000))))"]
   [:group-by "group-by into 31 groups, 100k values" nil
    "(count (group-by (fn [i] (mod i 31)) (range 100000)))"]
   [:sort "sort 50k numbers" nil
    "(first (sort (map (fn [i] (mod (* i 7919) 10007)) (range 50000))))"]
   [:sort-by "sort-by a key, 20k maps" "(def bench-recs (mapv (fn [i] {:k (mod (* i 7919) 10007) :i i}) (range 20000)))"
    "(:i (first (sort-by :k bench-recs)))"]
   [:set-build "build a set from 100k values" nil
    "(count (into #{} (map (fn [i] (mod i 1000))) (range 100000)))"]
   [:distinct "distinct over mapcat, 100k values" nil
    "(count (distinct (mapcat (fn [i] [i (inc i)]) (range 50000))))"]
   [:partition "partition 3 over 100k" nil
    "(count (partition 3 (range 100000)))"]
   [:lazy-iterate "nth of a lazy iterate, 20k" nil
    "(first (nth (iterate (fn [[a b]] [b (mod (+ a b) 1000007)]) [0 1]) 20000))"]
   [:atom-swap "swap! an atom 100k times" nil
    "(let [a (atom 0)] (dotimes [i 100000] (swap! a + i)) @a)"]
   [:protocol "protocol call on a record, 50k" "(do (defprotocol BenchArea (bench-area [s])) (defrecord BenchSq [w] BenchArea (bench-area [_] (* w w))))"
    "(reduce (fn [acc i] (+ acc (bench-area (->BenchSq i)))) 0 (range 50000))"]
   [:multimethod "multimethod call, 50k" "(do (defmulti bench-mm (fn [x] (mod x 3))) (defmethod bench-mm 0 [x] x) (defmethod bench-mm 1 [x] (* 2 x)) (defmethod bench-mm :default [x] 0))"
    "(reduce (fn [acc i] (+ acc (bench-mm i))) 0 (range 50000))"]
   [:equality "= of two 100k vectors" "(do (def bench-v1 (vec (range 100000))) (def bench-v2 (vec (range 100000))))"
    "(= bench-v1 bench-v2)"]
   [:str-build "apply str over 20k numbers" nil
    "(count (apply str (map str (range 20000))))"]
   [:string-join "clojure.string/join, 20k" "(require '[clojure.string :as bench-string])"
    "(count (bench-string/join \",\" (range 20000)))"]
   [:regex "re-seq over a 5k-number string" "(def bench-s (apply str (interpose \" \" (range 5000))))"
    "(count (re-seq #\"\\d+\" bench-s))"]
   [:format "format, 5k calls" nil
    "(count (apply str (map (fn [i] (format \"%05d|%s\" i \"x\")) (range 5000))))"]
   [:pr-str "pr-str of 2k small maps" "(def bench-data (vec (for [i (range 2000)] {:id i :name (str \"n\" i) :tags [:a :b]})))"
    "(count (pr-str bench-data))"]])

(def ^:private host-ns 'cohost.bench-host)

(defn- ensure-host-ns! []
  (host/with-ns (host/core-ns-sym) (fn [] (host/eval-form (list 'ns host-ns)))))

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
  (try
    (when setup (eval-fn setup))
    (measure (eval-fn (str "(fn [] " body ")")) opts)
    (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) e
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

(def default-opts {:warm-ms 500 :budget-ms 1000 :min-runs 3 :max-runs 2000})

(defn run-bench!
  "Time every case on both sides; print a table; write the results as EDN to
   target/bench-<label>.edn (in a browser, print them on a BENCH-EDN line).
   opts: :label (the host's name in the results), :boot-ms and :libs-ms (the
   boot's and the twelve libraries' load times, recorded with the results),
   and default-opts' keys."
  [{:keys [label boot-ms libs-ms] :as opts}]
  (let [opts (merge default-opts opts)
        label (or label (name (host/host-name)))]
    (ensure-host-ns!)
    (println (str "bench on " label ": median ms per call, the host's own core vs the stock core"))
    (let [results
          (doall
           (for [[id title setup body] cases]
             (let [h (side eval-host setup body opts)
                   s (side eval-stock setup body opts)
                   same? (and (not (:error h)) (not (:error s)) (= (:value h) (:value s)))
                   r {:id id :label title
                      :host (dissoc h :value) :stock (dissoc s :value)
                      :same? same?
                      :host-value (pr-str (:value h)) :stock-value (pr-str (:value s))}]
               (println (str "  " (name id) "  host " (fmt-ms (:median h)) "  stock " (fmt-ms (:median s))
                             (when (and (:median h) (:median s) (pos? (:median h)))
                               (str "  x" (fmt-ms (/ (:median s) (:median h)))))
                             (when-not same?
                               (str "  [" (or (:error h) (:error s) (str "differ: " (:value h) " vs " (:value s))) "]"))))
               r)))
          out {:host label :boot-ms boot-ms :libs-ms libs-ms
               :opts (dissoc opts :label :boot-ms :libs-ms) :cases (vec results)}]
      (write-results! (str "target/bench-" label ".edn")
                      (binding [*print-length* nil *print-level* nil] (pr-str out)))
      out)))
