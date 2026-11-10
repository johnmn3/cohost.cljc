(ns ds-harness.perf
  "people-1k runtime perf harness (datascript.cljc repo, harness/).

   Deterministic people-1k workload (no RNG): person i in 1..1000 is
   {:db/id i :name \"person-<i>\" :age (mod (+ (* i 37) 11) 90)
    :friend [f1 f2]} with f1 = 1+(mod i 1000), f2 = 1+(mod (+ i 7) 1000).

   Build keys: the ground truth is computed in-process from the
   generator (an in-memory db-with build), then the SAME keys are read
   off the Eve-atom-backed build and asserted equal — keys exact or the
   run fails before any timing is printed.

   Timings (wall ms, one warm process, journal engaged on the disk
   domain): build (10 swap!s of 100), find-datom (1000 :avet seeks),
   pull (1000 pulls), datoms-sequence (full :eavt walk), transact
   (100 sequential single-entity swap! commits)."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [datascript.core :as d]))

(def ^:private atom-name "ds-harness/perf-people-1k")
(def ^:private schema
  {:name {:db/index true}
   :friend {:db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}})

(defn- person [i]
  {:db/id i
   :name (str "person-" i)
   :age (mod (+ (* i 37) 11) 90)
   :friend [(inc (mod i 1000)) (inc (mod (+ i 7) 1000))]})

(defn- build-keys [db]
  {:entities (count (d/q '[:find [?e ...] :where [?e :name _]] db))
   :datoms (e/count (d/datoms db :eavt))
   :names (count (d/q '[:find [?n ...] :where [_ :name ?n]] db))
   :age-sum (reduce + (d/q '[:find [?a ...] :where [_ :age ?a]] db))
   :friends (e/count (d/datoms db :aevt :friend))
   :age-34 (count (d/q '[:find [?e ...] :where [?e :age 34]] db))
   :max-age (reduce max (d/q '[:find [?a ...] :where [_ :age ?a]] db))})

(defn- now-ms []
  #?(:clj (double (/ (System/nanoTime) 1e6))
     :cljs (.now js/Date)
     :lpy (python/time.time)
     :default 0))

(defmacro timed [label & body]
  `(let [t0# (now-ms)
         r# (do ~@body)
         t1# (now-ms)]
     [~label (- t1# t0#) r#]))

(defn -main [& [dir label]]
  (try
    (let [expected (build-keys (reduce (fn [db b]
                                         (d/db-with db (mapv person (range (inc (* b 100)) (inc (* (inc b) 100))))))
                                       (d/empty-db schema) (range 10)))
          domain (atom/persistent-atom-domain dir)
          a (atom/lookup-or-create-mmap-atom! domain atom-name nil)
          _ (when (nil? @a) (swap! a (fn [_] (d/empty-db schema))))
          [_ build-ms _] (timed :build
                           (doseq [b (range 10)]
                             (swap! a (fn [db] (d/db-with db (mapv person (range (inc (* b 100)) (inc (* (inc b) 100)))))))))
          db @a
          actual (build-keys db)
          keys-exact (= expected actual)
          [_ find-ms _] (timed :find-datom
                          (dotimes [i 1000]
                            (first (d/datoms db :avet :name (str "person-" (inc i))))))
          [_ pull-ms _] (timed :pull
                          (dotimes [i 1000]
                            (d/pull db [:name :age {:friend [:name]}] (inc i))))
          [_ seq-ms n] (timed :datoms-seq
                         (reduce (fn [c _] (inc c)) 0 (d/datoms db :eavt)))
          [_ tx-ms _] (timed :transact
                        (dotimes [i 100]
                          (swap! a (fn [db] (d/db-with db [[:db/add (inc i) :age (mod i 90)]])))))]
      (println (pr-str {:label (or label "jvm")
                        :keys-exact keys-exact
                        :expected expected
                        :actual actual
                        :seq-count n
                        :seal (atom/seal a)
                        :build-ms build-ms
                        :find-datom-ms find-ms
                        :pull-ms pull-ms
                        :datoms-seq-ms seq-ms
                        :transact-ms tx-ms
                        :verdict (if keys-exact :PERF-OK :PERF-FAIL)})))
    (catch #?(:cljs :default :lpy python/Exception :default Exception) t
      (println (pr-str {:label (or label "jvm") :fatal (str t)})))))
