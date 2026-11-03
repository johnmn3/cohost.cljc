





;;







(require 'cohost.run)
(require 'cohost.boot)
(require 'cohost.adapter)
(require 'cohost.bench)

(def opts
  (loop [args (seq (or *command-line-args* ())) o {:roots [] :libs []}]
    (if-let [[a b & more] args]
      (case a
        "--out" (recur more (assoc o :out b))
        "--root" (recur more (update o :roots conj b))
        "--lib" (recur more (update o :libs conj (symbol b)))
        (recur (next args) o))
      o)))

(let [r (cohost.boot/boot! {:record? true})]
  (cohost.boot/print-report r))
(doseq [d (:roots opts)] (cohost.boot/add-source-root! d))
(doseq [lib (concat cohost.bench/libs (:libs opts))]
  (cohost.adapter/try-all (fn [] (cohost.boot/load-lib! lib))
                          (fn [e] (println "RECORD-LIB-ERROR" lib (cohost.adapter/error-message e)))))
(let [ev (cohost.boot/snapshot-stop!)
      text (pr-str ev)]
  (cohost.adapter/spit-file (or (:out opts) "target/snapshot-events.edn") text)
  (println "SNAPSHOT-EVENTS" (count ev) "bytes" (count text)))
