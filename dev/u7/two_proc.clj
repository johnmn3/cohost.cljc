;; Eve-in-cohost unit 7, the two-process gate: the JVM process.
;; The atom is a cohost layer atom whose kind is chosen by metadata --
;;   (atom init :meta {:eve/kind :disk :eve/path <path> :eve/name "u7/shared"})
;; reaching cohost.atom/make-atom exactly as the shim's new-Atom passes it --
;; over Eve's JVM hosting (one Eve tree with the jank process: atom.broker/eve).
;;   dev/u7/clj-eve.sh dev/u7/two_proc.clj race <path> <n>
;;   dev/u7/clj-eve.sh dev/u7/two_proc.clj read <path>
;;   dev/u7/clj-eve.sh dev/u7/two_proc.clj mark <path> <tag>   (one swap, then halt: no close)
(require 'cohost.hosts.auto)
(require '[cohost.atom :as al]
         '[cohost.atom-eve :as hosting]
         'eve.deftype-proto.serialize 'eve.codec 'eve.crypto)

(hosting/install!)
(al/set-fill! true)

(def side :jvm)
(def init {:count 0 :jvm 0 :jank 0 :ready 0 :done 0 :marks []})

(defn join! [path]
  (al/make-atom init {:meta {:eve/kind :disk :eve/path path :eve/name "u7/shared"}}))

(defn bump [m k] (assoc m k (inc (get m k 0))))

(defn wait-for! [a k target]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (let [v (get (al/deref-atom a) k 0)]
        (cond (>= v target) true
              (> (- (System/currentTimeMillis) t0) 600000) (throw (ex-info "u7: barrier timeout" {:k k :at v}))
              :else (do (Thread/sleep 2) (recur)))))))

(defn plain [v] (into {} (map (fn [[k x]] [k (if (coll? x) (vec x) x)])) v))

(defn content-sha
  "Eve's content digest (eve.digest's compute-digest, spelled with its public
   parts): SHA-256 over the root's canonical codec bytes under a shared write."
  [v]
  (let [bs (binding [eve.deftype-proto.serialize/*shared-write* true] (eve.codec/value->eve-bytes v))
        raw (eve.crypto/sha256 (mapv (fn [b] (bit-and b 0xFF)) bs))]
    (str "sha256:" (apply str (map (fn [b] (format "%02x" (bit-and b 0xFF))) raw)) " over " (alength ^bytes bs) " bytes")))

(defn report! [tag a]
  (let [{:keys [seal]} (hosting/root-digests a)
        sha256 (content-sha @(al/eve-handle a))]
    (println (str "RESULT " (pr-str {:side side :tag tag :kind (al/kind a)
                                     :value (plain (al/deref-atom a))
                                     :seal seal :sha256 sha256})))))

(let [[mode path x] *command-line-args*]
  (case mode
    "race" (let [n (parse-long x)
                 a (join! path)]
             (al/swap-atom! a bump :ready)
             (wait-for! a :ready 2)
             (dotimes [_ n] (al/swap-atom! a (fn [m] (bump (bump m :count) side))))
             (al/swap-atom! a bump :done)
             (wait-for! a :done 2)
             (report! "race" a)
             (hosting/close-disk-domain! path))
    "read" (let [a (join! path)]
             (report! "read" a)
             (hosting/close-disk-domain! path))
    "mark" (let [a (join! path)]
             (al/swap-atom! a (fn [m] (assoc m :marks (conj (vec (get m :marks [])) x))))
             (println (str "MARKED " x))
             (flush)
             ;; killed, not closed: no slot release, no checkpoint
             (.halt (Runtime/getRuntime) 0))))
(shutdown-agents)
(System/exit 0)
