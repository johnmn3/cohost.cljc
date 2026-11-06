;; STM lane (redesign): the history-reclamation invariant, JVM seat.
;;   clojure -M probe_tx_reclaim.clj     (host cells; no Eve needed)
;;
;; The operator's concern: on the JVM, superseded versions become
;; unreachable and the GC frees them; on a host without a GC the same
;; code leaks unless something explicitly retires them. This probe
;; checks the JVM-seat half of the invariant, the half this layer is
;; responsible for on EVERY seat: after a history trim, the trimmed
;; versions are retained by NOTHING in the layer -- not the cell
;; (trimmed out), not attempt records (per-attempt, dropped by the
;; runner), not watch state (notifications are synchronous calls,
;; never stored), not send queues (they hold actions, not versions).
;;
;; Method: install nine distinct sentinel Objects as a ref's successive
;; values (max-history 3, a non-retaining watch attached so the watch
;; path is exercised too), keep ONLY WeakReferences to them, then
;; force GC. The trimmed five must clear; the retained three history
;; values and the current value must not. Sentinels are created inside
;; `drive`, whose frame is gone before the GC poll begins, so the
;; probe itself holds no strong reference to any of them.

(require 'cohost.hosts.auto)
(require '[cohost.tx :as tx])
(import '(java.lang.ref WeakReference))

(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))

(defn drive
  "Install n fresh sentinel values into r in sequence; return a vector
   of WeakReferences to them, in install order. THE ONLY references
   this function returns are weak."
  [r n]
  (let [wrs (java.util.ArrayList.)]
    (dotimes [_ n]
      (let [s (Object.)
            wr (WeakReference. s)]
        (.add wrs wr)
        (tx/run-in-transaction (fn [] (tx/ref-set* r s)))))
    (vec wrs)))

(def r (tx/make-ref nil))
(tx/set-max-history! r 3)
;; a watch that retains nothing: it sees every old/new pair the commits
;; produce and must not become a second home for any version
(tx/add-watch* r :probe (fn [_ _ _ _] nil))

(def wrs (drive r 9))

;; structural arm (deterministic): the cell itself keeps exactly the
;; bound -- current value plus 3 history entries, the newest points
(check "history is trimmed to max-history" 3 (tx/ref-history-count r))
(check "current value is the last sentinel installed" true
       (identical? (.get (nth wrs 8)) (tx/deref-ref r)))

;; reachability arm: force collection and poll briefly
(defn gc-poll [pred deadline-ms]
  (let [end (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (System/gc)
      (Thread/sleep 50)
      (cond (pred) true
            (> (System/currentTimeMillis) end) (pred)
            :else (recur)))))

(def trimmed-cleared?
  (gc-poll (fn [] (every? (fn [wr] (nil? (.get wr))) (subvec wrs 0 5))) 5000))
(check "trimmed versions are unreachable (weak refs cleared)" true trimmed-cleared?)
(check "retained history + current values survive GC" [true true true true]
       (mapv (fn [wr] (some? (.get wr))) (subvec wrs 5)))

(println (if (empty? @failures)
           (str "PROBE-TX-RECLAIM: ALL " @checks " CHECKS PASS")
           (str "PROBE-TX-RECLAIM: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))
