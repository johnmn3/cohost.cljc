(ns ds-example.demo
  "The DataScript-on-cohost example app: ONE DataScript database in
   ONE shared persistent Eve atom (a journaled disk domain), transacted
   and read by programs running in different cohost bodies — each body
   a different runtime hosting stock Clojure core.clj over the Eve
   backend. This file is the whole app; it is plain .cljc over
   datascript.core + eve.atom, identical in every body. The library
   itself lives in johnmn3/datascript.cljc — this example carries no
   copy of it.

   Modes (argv):  init <dir>          create the domain + atom, transact the base set
                  add  <dir> <tag>    transact one tagged batch (10 entities)
                  read <dir>          print datom count, the name query, the seal"
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [datascript.core :as d]))

(def ^:private atom-name "ds-example/demo-db")

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    (when (nil? @a)
      (swap! a (fn [_] (d/empty-db))))
    a))

(defn -main [& [mode dir tag]]
  (try
    (let [a (open-atom dir)
          report (fn [extra]
                   (println (pr-str (merge {:mode (keyword mode)
                                            :datoms (e/count (d/datoms @a :eavt))
                                            :names (vec (sort (d/q '[:find [?n ...] :where [?e :name ?n]] @a)))
                                            :seal (atom/seal a)}
                                           extra))))]
      (case mode
        "init" (do (swap! a (fn [db] (d/db-with db [{:db/id 1 :name "base-1" :age 21}
                                                    {:db/id 2 :name "base-2" :age 22}])))
                   (report {}))
        "add" (do (swap! a (fn [db] (d/db-with db (mapv (fn [i] {:db/id (+ 100 i)
                                                                 :name (str tag "-" i)
                                                                 :age i})
                                                        (range 10)))))
                  (report {:tag tag}))
        "read" (report {})
        (println (pr-str {:fatal (str "unknown mode " mode)}))))
    (catch #?(:cljs :default :lpy python/Exception :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))
