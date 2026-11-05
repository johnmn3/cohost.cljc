;; xbench.clj — Zone X crossing bench (Eve-in-cohost memory plan, unit 5 shape)
;;
;; MEASUREMENT ONLY. The thinnest faithful prototype of the plan's Zone E
;; atom on the JVM seat:
;;
;;   stand-in (the shim's clojure.lang.Atom seat)
;;     -> value layer fns (the cohost.memory analogue)
;;       -> wrapper: a CAS word (AtomicReference) over an immutable root
;;
;; The stand-in implements clojure.lang.IDeref / clojure.lang.IAtom with
;; method bodies that dispatch to the layer, so arm B is literally the
;; library-shaped calls (deref a) / (swap! a inc) / (reset! a v) crossing
;; the seam exactly as stock code would issue them.
;;
;; Arms (all in this one JVM session, interleaved per round, order rotated):
;;   A  stock clojure.lang.Atom                       — the JVM baseline
;;   B  Zone E stand-in: interface dispatch + layer hop + wrapper CAS
;;   C  the wrapper with NO layer hop and NO interface dispatch:
;;      field access + wrapper CAS called directly — splits B's cost into
;;      (wrapper/representation) vs (dispatch+layer hop = B - C)
;;
;; No validators, no watches, no persistence, in-memory roots only.
;; Stock Atom's swap/reset path does include its (null) validator/watch
;; checks; the prototype omits that machinery by design ("no extra
;; machinery"), so B-vs-A is the dispatch+wrapper floor, not a claim
;; about unit 5's finished atom.
;;
;; Run: clojure -M dev/xbench.clj   (from the repo root)

(ns xbench
  (:import [java.util.concurrent.atomic AtomicReference]
           [clojure.lang IFn]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

;; ------------------------------------------------------------------
;; The wrapper: Eve's reference-type shape — a CAS word over an
;; immutable root. On this seat the CAS word is an AtomicReference;
;; the root is whatever immutable value was published into it.
;; ------------------------------------------------------------------

(deftype EveWrapper [^AtomicReference cell])

(defn wrapper-new [root] (EveWrapper. (AtomicReference. root)))

(defn wrapper-deref ^Object [^EveWrapper w]
  (.get ^AtomicReference (.-cell w)))

(defn wrapper-reset! ^Object [^EveWrapper w ^Object v]
  (.set ^AtomicReference (.-cell w) v)
  v)

(defn wrapper-swap! ^Object [^EveWrapper w ^IFn f]
  (loop []
    (let [old (.get ^AtomicReference (.-cell w))
          new (.invoke f old)]
      (if (.compareAndSet ^AtomicReference (.-cell w) old new)
        new
        (recur)))))

(defn wrapper-cas! [^EveWrapper w ^Object old ^Object new]
  (.compareAndSet ^AtomicReference (.-cell w) old new))

;; ------------------------------------------------------------------
;; The value layer (cohost.memory analogue): the dispatch target the
;; shim's class entries call. One hop, no padding — the hop itself is
;; part of what is being priced.
;; ------------------------------------------------------------------

(defn layer-deref ^Object [^EveWrapper w] (wrapper-deref w))
(defn layer-reset! ^Object [^EveWrapper w ^Object v] (wrapper-reset! w v))
(defn layer-swap! ^Object [^EveWrapper w ^IFn f] (wrapper-swap! w f))
(defn layer-cas! [^EveWrapper w ^Object o ^Object n] (wrapper-cas! w o n))

;; ------------------------------------------------------------------
;; The stand-in: occupies the shim's Atom seat. Method bodies dispatch
;; to the layer over the wrapper it holds — nothing else.
;; ------------------------------------------------------------------

(deftype ZoneEAtom [^EveWrapper wrapper]
  clojure.lang.IDeref
  (deref [_] (layer-deref wrapper))

  clojure.lang.IAtom
  (swap [_ f] (layer-swap! wrapper f))
  (swap [_ f a] (layer-swap! wrapper (fn [x] (.invoke ^IFn f x a))))
  (swap [_ f a b] (layer-swap! wrapper (fn [x] (.invoke ^IFn f x a b))))
  (swap [_ f a b more] (layer-swap! wrapper (fn [x] (apply f x a b more))))
  (compareAndSet [_ old new] (layer-cas! wrapper old new))
  (reset [_ v] (layer-reset! wrapper v)))

(defn ze-atom [root] (ZoneEAtom. (wrapper-new root)))

;; ------------------------------------------------------------------
;; Op bodies. Each takes (subject, n) and returns a result value that
;; gets hashed into a blackhole so nothing is optimized away.
;; ------------------------------------------------------------------

(defn deref-loop [d n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (unchecked-add acc (long (deref d))))
      acc)))

(defn deref-loop-c [^EveWrapper w n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (unchecked-add acc (long (wrapper-deref w))))
      acc)))

(defn swap-loop [a n]
  (dotimes [_ n] (swap! a inc))
  @a)

(defn swap-loop-c [^EveWrapper w n]
  (dotimes [_ n] (wrapper-swap! w inc))
  (wrapper-deref w))

(defn reset-loop [a n]
  (dotimes [i n] (reset! a i))
  @a)

(defn reset-loop-c [^EveWrapper w n]
  (dotimes [i n] (wrapper-reset! w i))
  (wrapper-deref w))

(defn contended [n run-thread]
  (let [per (quot n 4)
        ts (mapv (fn [_] (Thread. ^Runnable (fn [] (run-thread per)))) (range 4))]
    (run! #(.start ^Thread %) ts)
    (run! #(.join ^Thread %) ts)))

(def ops
  [{:name "deref (uncontended)"
    :n 2000000
    :arms {:A [(fn [] (atom 0)) deref-loop]
           :B [(fn [] (ze-atom 0)) deref-loop]
           :C [(fn [] (wrapper-new 0)) deref-loop-c]}}
   {:name "swap! inc (uncontended)"
    :n 500000
    :arms {:A [(fn [] (atom 0)) swap-loop]
           :B [(fn [] (ze-atom 0)) swap-loop]
           :C [(fn [] (wrapper-new 0)) swap-loop-c]}}
   {:name "reset!"
    :n 1000000
    :arms {:A [(fn [] (atom 0)) reset-loop]
           :B [(fn [] (ze-atom 0)) reset-loop]
           :C [(fn [] (wrapper-new 0)) reset-loop-c]}}
   {:name "swap! inc (contention, 4 threads)"
    :n (* 4 250000)
    :rounds 11
    :arms {:A [(fn [] (atom 0))
               (fn [a n] (contended n (fn [per] (dotimes [_ per] (swap! a inc)))) @a)]
           :B [(fn [] (ze-atom 0))
               (fn [a n] (contended n (fn [per] (dotimes [_ per] (swap! a inc)))) @a)]
           :C [(fn [] (wrapper-new 0))
               (fn [w n] (contended n (fn [per] (dotimes [_ per] (wrapper-swap! w inc))))
                 (wrapper-deref w))]}}])

;; ------------------------------------------------------------------
;; Harness: rounds, arms interleaved, order rotated per round,
;; medians of the measured rounds.
;; ------------------------------------------------------------------

(def blackhole (volatile! 0))
(def rounds 7)

(defn bench-once [mk f n]
  (let [subject (mk)
        t0 (System/nanoTime)
        r (f subject n)
        t1 (System/nanoTime)]
    (vswap! blackhole unchecked-add (long (hash r)))
    (/ (double (- t1 t0)) (double n))))

(defn median [xs]
  (let [s (vec (sort xs)) c (count s)]
    (if (odd? c)
      (nth s (quot c 2))
      (/ (+ (nth s (dec (quot c 2))) (nth s (quot c 2))) 2.0))))

(defn run []
  (println "xbench — Zone X crossing, JVM seat")
  (println "java:" (System/getProperty "java.version")
           "| clojure:" (clojure-version)
           "| cores:" (.availableProcessors (Runtime/getRuntime)))
  ;; correctness first: every arm must land the exact expected values
  (let [a (atom 0) z (ze-atom 0) w (wrapper-new 0)]
    (dotimes [_ 1000] (swap! a inc) (swap! z inc) (wrapper-swap! w inc))
    (assert (= 1000 @a @z (wrapper-deref w)) "uncontended swap mismatch")
    (reset! z 7)
    (assert (= 7 @z) "reset mismatch"))
  (let [cop (first (filter #(.contains ^String (:name %) "contention") ops))
        n (:n cop)]
    (doseq [[k [mk f]] (:arms cop)]
      (assert (= (long n) (long (f (mk) n))) (str "contention total mismatch, arm " k))))
  (println "correctness: uncontended + contended totals agree across arms")
  ;; warmup: 2 full passes over every op/arm, discarded
  (dotimes [_ 2]
    (doseq [{:keys [arms n]} ops
            [_ [mk f]] arms]
      (bench-once mk f n)))
  ;; measured rounds, arms interleaved, order rotated by round
  (let [arm-order [:A :B :C]
        results (atom {})]
    (doseq [{:keys [name n arms] op-rounds :rounds} ops]
      (doseq [r (range (or op-rounds rounds))]
        (doseq [k (take 3 (drop (mod (+ r (hash name)) 3) (cycle arm-order)))]
          (let [[mk f] (get arms k)]
            (swap! results update [name k] (fnil conj []) (bench-once mk f n))))))
    (println)
    (println (format "%-36s %10s %10s %10s %10s %10s" "op" "A ns/op" "B ns/op" "C ns/op" "B/A" "B-C ns"))
    (doseq [{:keys [name]} ops]
      (let [ma (median (get @results [name :A]))
            mb (median (get @results [name :B]))
            mc (median (get @results [name :C]))]
        (println (format "%-36s %10.2f %10.2f %10.2f %9.2fx %10.2f"
                         name ma mb mc (/ mb ma) (- mb mc)))
        (println (format "%-36s A rounds: %s" "" (mapv #(format "%.2f" %) (get @results [name :A]))))
        (println (format "%-36s B rounds: %s" "" (mapv #(format "%.2f" %) (get @results [name :B]))))
        (println (format "%-36s C rounds: %s" "" (mapv #(format "%.2f" %) (get @results [name :C]))))))
    (println "blackhole:" @blackhole)))

(run)
(System/exit 0)
