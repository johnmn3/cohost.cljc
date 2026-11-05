(require 'cohost.hosts.jvm)
(require 'cohost.thread)
(let [q-conj @#'cohost.thread/q-conj
      q-peek @#'cohost.thread/q-peek
      q-pop @#'cohost.thread/q-pop
      q-empty? @#'cohost.thread/q-empty?
      empty-q @#'cohost.thread/empty-queue
      ;; force the layer representation by building one directly
      lq {:cohost/kind :cohost.thread/queue :front () :back ()}
      results (atom [])]
  ;; the fallback value empty-queue gives when the cap yields nil is the
  ;; tagged map; here the JVM cap yields a real queue, so exercise the
  ;; layer representation explicitly, in FIFO order, with pops interleaved
  (let [q1 (reduce q-conj lq [1 2 3])
        q2 (q-pop q1)
        q3 (q-conj q2 4)
        q4 (q-pop (q-pop q3))]
    (swap! results conj [:empty-at-start (q-empty? lq)])
    (swap! results conj [:peek-1 (q-peek q1)])
    (swap! results conj [:peek-after-pop (q-peek q2)])
    (swap! results conj [:seq-drain (loop [q q3 acc []]
                                       (if (q-empty? q) acc (recur (q-pop q) (conj acc (q-peek q)))))])
    (swap! results conj [:peek-4 (q-peek q4)])
    (swap! results conj [:empty-at-end (q-empty? (q-pop q4))])
    (swap! results conj [:pop-empty (q-pop lq)]))
  ;; host queue path unchanged in shape
  (let [hq (empty-q)]
    (swap! results conj [:host-queue-class (class hq)]))
  (doseq [[k v] @results] (println k v))
  (println "QUEUE-CHECK-DONE"))
