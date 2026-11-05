;; u4 probe 5: capacity wall -- size threshold, and whether capacities help
(require 'cohost.hosts.jvm 'cohost.adapter
         '[eve.deftype-proto.alloc :as alloc] '[eve.vec :as eve-vec] '[eve.map :as eve-map])
(alias 'host 'cohost.adapter)
(defn t [label f] (println (str label " => " (host/try-all (fn [] (pr-str (f))) (fn [e] (str "ERR " (host/error-message e)))))))
(defn mkmap [ctx m] (eve-map/jvm-eve-hash-map-from-offset ctx (eve-map/jvm-write-map! ctx m)))
(def big {:capacities {0 67108864 1 67108864 2 67108864 3 67108864 4 67108864 5 67108864}})
(doseq [n [2000 4000 6000 8000]]
  (t (str "map " n " default") #(count (mkmap (alloc/make-jvm-heap-slab-ctx {}) (into {} (map (fn [i] [i i]) (range n)))))))
(doseq [n [10000 50000]]
  (t (str "map " n " bigcap") #(count (mkmap (alloc/make-jvm-heap-slab-ctx big) (into {} (map (fn [i] [i i]) (range n)))))))
(doseq [n [5000 10000 20000 50000]]
  (t (str "vec " n " default") #(count (eve-vec/eve-vec (alloc/make-jvm-heap-slab-ctx {}) (range n)))))
(t "vec 100000 bigcap" #(count (eve-vec/eve-vec (alloc/make-jvm-heap-slab-ctx big) (range 100000))))
;; assoc-grown map to 10000 (incremental, no single big write)
(t "assoc-grown map 10000" #(count (loop [i 0 m (mkmap (alloc/make-jvm-heap-slab-ctx {}) {})] (if (< i 10000) (recur (inc i) (assoc m i i)) m))))
;; conj-grown vec to 100000
(t "conj-grown vec 100000" #(count (loop [i 0 v (eve-vec/eve-vec (alloc/make-jvm-heap-slab-ctx {}) [])] (if (< i 100000) (recur (inc i) (conj v i)) v))))
(System/exit 0)
