;; scratch: which trimmed sentinels stay reachable, and after how much GC?
(require 'cohost.hosts.auto)
(require '[cohost.tx :as tx])
(import '(java.lang.ref WeakReference))
(defn drive [r n]
  (let [wrs (java.util.ArrayList.)]
    (dotimes [_ n]
      (let [s (Object.) wr (WeakReference. s)]
        (.add wrs wr)
        (tx/run-in-transaction (fn [] (tx/ref-set* r s)))))
    (vec wrs)))
(def r (tx/make-ref nil))
(tx/set-max-history! r 3)
(tx/add-watch* r :probe (fn [_ _ _ _] nil))
(def wrs (drive r 9))
(dotimes [i 12]
  (System/gc) (Thread/sleep 100)
  (println "after gc" i "alive:" (mapv (fn [wr] (if (.get wr) 1 0)) wrs)))
(println "history points:" (mapv :point (:history (let [c @(:cell r)] c))))
(System/exit 0)
