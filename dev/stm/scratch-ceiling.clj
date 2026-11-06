;; scratch: how many cell-shaped installs does one fresh Eve domain
;; sustain before coalesc-alloc! throws, and does a quiescent pause
;; reclaim headroom? (STM lane, redesign -- not a probe.)
(require 'cohost.hosts.auto)
(require '[eve.atom :as eve-atom])
(let [d (eve-atom/atom-domain {})
      h (eve-atom/lookup-or-create-mmap-atom! d "ceiling/probe"
          {:value 0 :point 0 :version 0 :history []})
      n (atom 0)]
  (try
    (loop []
      (let [cur @h]
        (reset! h (assoc cur :value (inc (:value cur))
                         :point (inc (:point cur))
                         :version (inc (:version cur))
                         :history (let [hs (conj (:history cur)
                                                 {:point (:point cur)
                                                  :value (:value cur)})]
                                    (vec (take 10 hs)))))
        (swap! n inc)
        (recur)))
    (catch clojure.lang.ExceptionInfo e
      (println "CEILING: installs before OOM =" @n " ex:" (ex-message e))))
  (println "pausing 20 s (quiescence / epoch retirement window)...")
  (Thread/sleep 20000)
  (try
    (dotimes [i 50] (reset! h (assoc @h :value i)))
    (println "AFTER PAUSE: 50 more installs OK -- headroom returned")
    (catch clojure.lang.ExceptionInfo e
      (println "AFTER PAUSE: still OOM on write:" (ex-message e))))
  (System/exit 0))
