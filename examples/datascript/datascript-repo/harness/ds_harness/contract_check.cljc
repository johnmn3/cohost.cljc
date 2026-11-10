(ns ds-harness.contract-check
  "The lib's contract proof driver (datascript.cljc repo, harness/).

   One fixed dataset in ONE persistent Eve atom (a disk domain, journaled
   by default — the runner verifies the journal artifact exists in the
   domain directory). Two modes, meant to run as two processes:

     write <dir>   create/open the domain, transact the dataset inside
                   swap!s, run the check battery, print verdicts + seal
     read  <dir>   fresh process: reopen, deref, run the same battery,
                   print verdicts + seal (the runner compares seals)

   Battery: create (empty-db in the atom) -> transact -> datalog query
   -> pull -> entity -> datom count. Every check prints PASS/FAIL with
   its expected and actual values; the mode line ends CONTRACT-*-OK
   only when every check passed."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [datascript.core :as d]))

(def ^:private atom-name "ds-harness/contract-db")

(def ^:private schema
  {:friend {:db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}})

(def ^:private tx-data
  [{:db/id 1 :name "Ada" :age 36 :friend [2 3]}
   {:db/id 2 :name "Grace" :age 30 :friend [3]}
   {:db/id 3 :name "Edsger" :age 34}])

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    (when (nil? @a)
      (swap! a (fn [_] (d/empty-db schema))))
    a))

(defn- battery [db]
  (let [names (sort (d/q '[:find [?n ...] :where [?e :name ?n]] db))
        age34 (d/q '[:find [?e ...] :where [?e :age 34]] db)
        pulled (d/pull db [:name {:friend [:name]}] 1)
        friend-names (sort (map :name (:friend pulled)))]
    {:datoms {:expect 9 :actual (e/count (d/datoms db :eavt))}
     :names {:expect ["Ada" "Edsger" "Grace"] :actual (vec names)}
     :age34 {:expect [3] :actual (vec (sort age34))}
     :entity1-name {:expect "Ada" :actual (:name (d/entity db 1))}
     :pull1-name {:expect "Ada" :actual (:name pulled)}
     :pull1-friends {:expect ["Edsger" "Grace"] :actual (vec friend-names)}}))

(defn- verdicts [checks]
  (into {}
        (map (fn [[k {:keys [expect actual]}]]
               [k {:pass (= expect actual) :expect expect :actual actual}])
             checks)))

(defn -main [& [mode dir]]
  (try
    (let [a (open-atom dir)]
      (when (= mode "write")
        (swap! a (fn [db] (d/db-with db tx-data))))
      (let [checks (verdicts (battery @a))
            ok (every? :pass (vals checks))]
        (println (pr-str {:mode (keyword mode)
                          :checks checks
                          :seal (atom/seal a)
                          :verdict (if ok
                                     (if (= mode "write") :CONTRACT-WRITE-OK :CONTRACT-READ-OK)
                                     :CONTRACT-FAIL)}))))
    (catch #?(:cljs :default :lpy python/Exception :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))
