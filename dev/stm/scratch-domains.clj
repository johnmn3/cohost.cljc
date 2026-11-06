;; scratch: what does an Eve heap domain cost? (JVM seat, big heap)
(require '[eve.atom :as eve-atom])

(defn used-mb []
  (let [rt (Runtime/getRuntime)]
    (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0)))

(defn median [xs] (nth (sort xs) (quot (count xs) 2)))

;; warm up code paths + JIT
(dotimes [_ 10] (eve-atom/atom-domain {}))
(System/gc) (Thread/sleep 100)

;; --- domain creation: time + heap, n=120
(def N 120)
(def rss0 (used-mb))
(def times
  (mapv (fn [_] (let [t0 (System/nanoTime) d (eve-atom/atom-domain {}) dt (/ (- (System/nanoTime) t0) 1e6)] [dt d])) (range N)))
(def domains (mapv second times))
(System/gc) (Thread/sleep 100)
(def rss1 (used-mb))
(println (format "domain creation: median %.2f ms, min %.2f, max %.2f" (median (map first times)) (apply min (map first times)) (apply max (map first times))))
(println (format "heap for %d domains: %.1f MB -> %.1f MB (delta %.1f MB = %.2f MB/domain)" N rss0 rss1 (- rss1 rss0) (/ (- rss1 rss0) N)))

;; --- one atom in each domain
(def t0 (System/nanoTime))
(def atoms (mapv (fn [i d] (eve-atom/lookup-or-create-mmap-atom! d (str "r" i) {:value i :history []})) (range N) domains))
(println (format "atom create in own domain: %.1f µs each" (* (/ (- (System/nanoTime) t0) 1e6) (/ 1000.0 N))))

;; --- deref/swap: sole-domain atom vs shared-domain atoms
(def shared (eve-atom/atom-domain {}))
(def shared-atoms (mapv (fn [i] (eve-atom/lookup-or-create-mmap-atom! shared (str "s" i) {:value 0})) (range 200)))
(defn rate [label n f] (let [s (System/nanoTime)] (f n) (println (format "%s: %.0f ops/ms" label (/ n (/ (- (System/nanoTime) s) 1e6))))))
(rate "deref sole-domain" 20000 (fn [n] (dotimes [_ n] @(first atoms))))
(rate "deref shared-domain" 20000 (fn [n] (dotimes [_ n] @(first shared-atoms))))
(rate "swap! sole-domain" 2000 (fn [n] (dotimes [_ n] (swap! (first atoms) update :value inc))))
(rate "swap! shared-domain" 2000 (fn [n] (dotimes [_ n] (swap! (first shared-atoms) update :value inc))))
(println "sole-domain atom value after swaps:" (:value @(first atoms)))
