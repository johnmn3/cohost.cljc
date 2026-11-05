;; unit 4 timings: host-backed (H) vs Eve-backed (E) arms in
;; ONE booted session, interleaved round by round (the fill is
;; the layer's switch; creators consult it per call). Timed
;; forms run through the booted core; timing is host nano-time
;; around the eval. Read benches build their value in an
;; untimed setup form (a def in the booted world) per arm.
;; Medians of 3 rounds are the numbers.
;; (vec is host-answered on this seat, so vector builds go
;; through the variadic creator: (apply vector ...).)
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)
(alias 'memory 'cohost.memory)
(def report (boot/boot! {}))
(boot/make-user-ns!)
(def benches
  [["vec build 10k" nil "(count (apply vector (range 10000)))"]
   ["map build 10k" nil "(count (apply hash-map (interleave (range 10000) (range 10000))))"]
   ["map assoc-grow 10k" nil "(count (reduce (fn [m i] (assoc m i i)) (hash-map 0 0) (range 1 10000)))"]
   ["vec conj-grow 10k" nil "(count (reduce conj (vector 0 1 2 3 4 5 6) (range 7 10000)))"]
   ["map lookup 10k" "(def bench-m (apply hash-map (interleave (range 10000) (range 10000))))"
    "(reduce (fn [s i] (+ s (get bench-m i))) 0 (range 10000))"]
   ["vec lookup 10k" "(def bench-v (apply vector (range 10000)))"
    "(reduce (fn [s i] (+ s (nth bench-v i))) 0 (range 10000))"]
   ["vec reduce 10k" "(def bench-v (apply vector (range 10000)))"
    "(reduce + bench-v)"]])
(def results (atom {}))
(doseq [round [1 2 3]]
  (doseq [arm [:H :E]]
    (memory/set-fill! (= arm :E))
    (doseq [[nm setup form] benches]
      (when setup (boot/eval-str setup))
      (let [t0 (host/nano-time)
            v (boot/eval-str form)
            dt (/ (double (- (host/nano-time) t0)) 1000000.0)]
        (swap! results update [nm arm] (fnil conj []) dt)
        (println (str "round " round " " (name arm) " " nm " -> " v "  " (format "%.1f" dt) " ms"))))))
(println "=== medians (ms) ===")
(doseq [[nm _ _] benches]
  (let [med (fn [xs] (nth (sort xs) 1))
        h (med (get @results [nm :H])) e (med (get @results [nm :E]))]
    (println (str nm ": H " (format "%.1f" h) "  E " (format "%.1f" e) "  E/H " (format "%.2f" (/ e h))))))
(shutdown-agents)
