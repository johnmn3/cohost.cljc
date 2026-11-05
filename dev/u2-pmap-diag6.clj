(require 'cohost.hosts.jvm 'cohost.thread)
(alias 't 'cohost.thread)
(def ids (java.util.IdentityHashMap.))
(def events (atom []))
(def orig-submit @#'cohost.thread/submit!)
(def orig-claim @#'cohost.thread/claim!)
(def id-seq (atom 0))
(alter-var-root #'cohost.thread/submit!
                (fn [_]
                  (fn [ex thunk & [fut]]
                    (let [id (swap! id-seq inc)
                          wrapped (fn [] (swap! events conj [:start id (.getName (Thread/currentThread))])
                                      (thunk))]
                      (.put ids wrapped id)
                      (swap! events conj [:submit id (.getName (Thread/currentThread)) (some? fut)])
                      (orig-submit ex wrapped fut)))))
(alter-var-root #'cohost.thread/claim!
                (fn [_]
                  (fn [q]
                    (let [v (orig-claim q)]
                      (when (some? v)
                        (swap! events conj [:claim (.get ids v) (.getName (Thread/currentThread))]))
                      v))))
(def ag (t/new-agent 0 {}))
(dotimes [i 50]
  (t/dispatch! ag (fn [s] (inc s)) [] (t/send-executor)))
(dotimes [i 200]
  (let [futs (mapv (fn [x] (t/future-call (fn [] (* x x)))) (range 20))
        deadline (+ (System/currentTimeMillis) 8000)
        stuck (loop []
                (let [pending (filter (fn [f] (not (t/future-done? f))) futs)]
                  (cond (empty? pending) nil
                        (> (System/currentTimeMillis) deadline) pending
                        :else (do (Thread/sleep 25) (recur)))))]
    (when stuck
      (let [evs @events
            submitted (into #{} (comp (filter #(= :submit (first %))) (map second)) evs)
            claimed (into #{} (comp (filter #(= :claim (first %))) (map second)) evs)
            started (into #{} (comp (filter #(= :start (first %))) (map second)) evs)
            claim-counts (frequencies (map second (filter #(= :claim (first %)) evs)))]
        (println "STUCK round" i "pending:" (count stuck))
        (println "submitted-not-claimed:" (sort (remove claimed submitted)))
        (println "claimed-not-started:" (sort (remove started claimed)))
        (println "double-claimed:" (sort (for [[id c] claim-counts :when (> c 1)] [id c])))
        (println "total submits:" (count submitted)
                 "claims:" (count (filter #(= :claim (first %)) evs)))
        (System/exit 3))))
  (when (zero? (mod i 20)) (println "ok" i)))
(println "DIAG6-DONE")
(System/exit 0)
