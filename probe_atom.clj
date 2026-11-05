;; Eve-in-cohost, unit 5: the atom layer probe. Run on the JVM seat
;; with Eve's sources on the classpath:
;;   clojure -Sdeps '{:paths ["src" "/home/hatch/src/eve/src"]}' -M probe_atom.clj
;; Exercises cohost.atom + cohost.atom-eve directly (the shim wiring
;; is exercised by eve_cohost_check_u5.clj through a booted core):
;; stock Atom semantics over Eve-backed atoms, the host seat for
;; roots Eve cannot represent, watches/validators/meta, the
;; registry, unit-3 stand-ins as roots, the slab-identity
;; measurement, thread-layer composition, and the CAS-contention
;; probe (contended swap!, N threads, vs stock, same session
;; interleaved).

(require 'cohost.hosts.auto)
(require '[cohost.atom :as al]
         '[cohost.atom-eve :as hosting]
         '[cohost.memory :as memory]
         '[cohost.memory-eve :as memory-eve]
         '[cohost.thread :as thread]
         '[eve.atom :as eve-atom]
         '[eve.mem :as eve-mem]
         '[eve.deftype-proto.data :as eve-data]
         '[eve.vec :as eve-vec])

(hosting/install!)
(al/set-fill! true)

(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))
(defn check-true [label actual] (check label true (boolean actual)))

;; -- construction, deref, swap, reset ------------------------------
(def a (al/make-atom 5))
(check-true "atom-value?" (al/atom-value? a))
(check "gate starts :open" :open (:gate (al/state-of a)))
(check "deref" 5 (al/deref-atom a))
(check "swap! returns new" 6 (al/swap-atom! a inc))
(check "deref after swap" 6 (al/deref-atom a))
(check "swap! with args" 16 (al/swap-atom! a + 4 6))
(check "reset! returns v" 10 (al/reset-atom! a 10))
(check "deref after reset" 10 (al/deref-atom a))

;; -- compare-and-set!: seam equality, not identity ------------------
(def b (al/make-atom {:a [1 2]}))
(def b-old (al/deref-atom b))
(check "cas by = old" true (al/compare-and-set-atom! b {:a [1 2]} {:a [3]}))
(check "deref after cas" {:a [3]} (al/deref-atom b))
(check "cas wrong old" false (al/compare-and-set-atom! b {:a [1 2]} {:a [9]}))
(check "deref unchanged" {:a [3]} (al/deref-atom b))
(check-true "cas old was a fresh materialization" (not (identical? b-old (al/deref-atom b))))

;; -- swap-vals / reset-vals ------------------------------------------
(check "swap-vals" [10 11] (do (al/reset-atom! a 10) (al/swap-vals-atom! a inc)))
(check "reset-vals" [11 4] (al/reset-vals-atom! a 4))

;; -- validators --------------------------------------------------------
(def v (al/make-atom 12))
(al/set-validator-atom! v even?)
(check "validator accepts" 14 (al/swap-atom! v + 2))
(check "validator rejects swap"
       :threw
       (try (al/swap-atom! v inc) :no-throw
            (catch IllegalStateException _ :threw)))
(check "value intact after rejected swap" 14 (al/deref-atom v))
(check "validator rejects reset"
       :threw
       (try (al/reset-atom! v 15) :no-throw
            (catch IllegalStateException _ :threw)))
(check "set-validator! validates current"
       :threw
       (try (al/set-validator-atom! (al/make-atom 3) even?) :no-throw
            (catch IllegalStateException _ :threw)))
(check "validator rejects cas"
       :threw
       (try (al/compare-and-set-atom! v 14 15) :no-throw
            (catch IllegalStateException _ :threw)))
(al/set-validator-atom! v nil)
(check "validator cleared" 15 (al/swap-atom! v inc))

;; -- watches: exact pairs, every publish kind --------------------------
(def w (al/make-atom 0))
(def seen (atom []))
(al/add-watch-atom! w :k (fn [k ref old new] (swap! seen conj [k old new])))
(al/swap-atom! w inc)
(al/reset-atom! w 10)
(al/compare-and-set-atom! w 10 20)
(check "watch pairs" [[:k 0 1] [:k 1 10] [:k 10 20]] @seen)
(check "get-watches keys" [:k] (keys (al/get-watches-atom w)))
(al/remove-watch-atom! w :k)
(al/swap-atom! w inc)
(check "removed watch silent" 3 (count @seen))
;; a watch that swaps the same atom once (fires after the gate is
;; released, so the nested swap is a fresh publish)
(def w2 (al/make-atom 0))
(def w2-seen (atom []))
(al/add-watch-atom! w2 :self
                    (fn [k ref old new]
                      (swap! w2-seen conj [old new])
                      (when (= 1 new) (al/swap-atom! ref inc))))
(al/swap-atom! w2 inc)
(check "re-entrant watch" [[0 1] [1 2]] @w2-seen)
(check "re-entrant watch value" 2 (al/deref-atom w2))

;; -- meta ----------------------------------------------------------------
(def m (al/make-atom 1 {:meta {:doc "x"}}))
(check "meta at creation" {:doc "x"} (meta m))
(def m2 (with-meta m {:doc "y"}))
(al/swap-atom! m2 inc)
(check "with-meta shares state" 2 (al/deref-atom m))
(check "meta replaced" {:doc "y"} (meta m2))

;; -- the host seat: roots Eve cannot represent -------------------------
(def f0 (fn [x] x))
(def ha (al/make-atom f0))
(check "fn root gate" :host (:gate (al/state-of ha)))
(check-true "fn root identical" (identical? f0 (al/deref-atom ha)))
(check "eve handle marked" :cohost.atom/host-held @(al/eve-handle ha))
(def mm (al/make-atom {:methods {1 f0}}))
(check "multimethod shape gate" :host (:gate (al/state-of mm)))
(check-true "multimethod fn identical" (identical? f0 (get-in (al/deref-atom mm) [:methods 1])))
;; transition: data root -> host root -> data root (stays :host)
(def t (al/make-atom [1 2]))
(check "starts open" :open (:gate (al/state-of t)))
(al/swap-atom! t (fn [_] f0))
(check "transitioned to host" :host (:gate (al/state-of t)))
(check-true "transitioned root identical" (identical? f0 (al/deref-atom t)))
(check "eve handle marked after transition" :cohost.atom/host-held @(al/eve-handle t))
(al/reset-atom! t [9 9])
(check "data root in host seat" [9 9] (al/deref-atom t))
(check "one-way: gate stays host" :host (:gate (al/state-of t)))
(check "host-seat swap works" [9 9 7] (al/swap-atom! t conj 7))
;; a watched atom whose swap result is unrepresentable
(def wt (al/make-atom 0))
(def wt-seen (atom []))
(al/add-watch-atom! wt :k (fn [k ref old new] (swap! wt-seen conj [old (fn? new)])))
(al/swap-atom! wt (fn [_] f0))
(check "watched transition fires" [[0 true]] @wt-seen)
(check "watched transition gate" :host (:gate (al/state-of wt)))

;; -- unit 3 composition: a stand-in as a root ----------------------------
;; A stand-in's identity is its slab root in its own domain, so
;; the atom holds the stand-in itself as a resident: deref
;; returns it identical, and its root's identity is exactly
;; stable across swaps that do not change it.
(def hv (memory-eve/eve-vector [1 2 3]))
(def standin (memory/wrap :vector hv [1 2 3]))
(def sa (al/make-atom []))
(al/reset-atom! sa standin)
(check "stand-in root seats as resident" :host (:gate (al/state-of sa)))
(check-true "stand-in root identical" (identical? standin (al/deref-atom sa)))
(check-true "stand-in equiv" (memory/equiv standin (al/deref-atom sa)))
(def sroot (memory/root (al/deref-atom sa)))
(al/swap-atom! sa (fn [v] v))
(check-true "stand-in root identity stable across no-op swap"
            (identical? sroot (memory/root (al/deref-atom sa))))
;; a bare Eve value from a FOREIGN domain as a root: Eve refuses
;; loudly, the layer seats it -- never garbage in the slab
(def fa (al/make-atom []))
(al/reset-atom! fa hv)
(check "foreign eve root seats" :host (:gate (al/state-of fa)))
(check-true "foreign eve root identical" (identical? hv (al/deref-atom fa)))

;; -- the registry (startup domain) --------------------------------------
(al/publish-intern! 'user 'x)
(al/publish-var-root! 'user 'answer 42)
(al/publish-var-root! 'user 'f f0)
(check "registry data root" {:root 42} (get-in (al/registry-table) ['user 'answer]))
(check "registry resident" {:resident true} (get-in (al/registry-table) ['user 'f]))
(check "registry intern entry" {:resident true} (get-in (al/registry-table) ['user 'x]))
(check-true "startup domain minted" (:startup-domain? (hosting/hosting-state)))

;; -- slab identity: a swap that does not change the root does not
;;    change its slab identity (unit 4/5 gate shape) ---------------------
(def ia (al/make-atom []))
(def isio (eve-atom/atom-sio (al/eve-handle ia)))
(def ivec (eve-vec/eve-vec isio [7 8 9]))
(al/reset-atom! ia ivec)
(check "eve root gate" :open (:gate (al/state-of ia)))
(defn slot-ptr [handle]
  (let [ds (.-domain-state handle)
        idx (.-atom-slot-idx handle)]
    (eve-mem/-load-i32 (:root-r ds)
                       (eve-data/atom-slot-offset idx eve-data/ATOM_SLOT_PTR_OFFSET))))
(def ih (al/eve-handle ia))
(def p0 (slot-ptr ih))
(al/swap-atom! ia (fn [v] v))
(check "identity stable across no-op swap" p0 (slot-ptr ih))
(al/swap-atom! ia conj 10)
(check-true "identity moves on change" (not= p0 (slot-ptr ih)))
(check "eve root value" [7 8 9 10] (al/deref-atom ia))

;; -- thread-layer composition --------------------------------------------
(def ta (al/make-atom 0))
(def fut (thread/future-call (fn [] (al/swap-atom! ta inc))))
(check "future over layer atom" 1 (thread/deref-future fut))
(check "atom after future" 1 (al/deref-atom ta))
(def tas (mapv (fn [_] (al/make-atom 0)) (range 3)))
(doall (thread/pmap* (fn [x] (al/swap-atom! x inc)) tas))
(check "pmap over layer atoms" [1 1 1] (mapv al/deref-atom tas))

;; -- the contention probe --------------------------------------------------
(defn contended [mk swapf nthreads per]
  (let [a (mk 0)
        latch (java.util.concurrent.CountDownLatch. 1)
        ts (mapv (fn [_]
                   (Thread. (fn [] (.await latch)
                              (dotimes [_ per] (swapf a inc)))))
                 (range nthreads))]
    (doseq [t ts] (.start t))
    (.countDown latch)
    (let [t0 (System/nanoTime)]
      (doseq [t ts] (.join t))
      {:value (if (al/atom-value? a) (al/deref-atom a) @a)
       :ns (- (System/nanoTime) t0)})))
(defn bench-rounds [label mk swapf]
  (let [rs (mapv (fn [_] (contended mk swapf 4 2500)) (range 3))
        expect (* 4 2500)]
    (check (str label " no lost updates") expect (:value (first rs)))
    (println label "rounds ns/op:"
             (mapv (fn [r] (format "%.0f" (double (/ (:ns r) (* 4 2500.0))))) rs))))
(bench-rounds "stock" (fn [x] (atom x)) (fn [a f] (swap! a f)))
(bench-rounds "layer" (fn [x] (al/make-atom x)) al/swap-atom!)
(def wa (al/make-atom 0))
(al/add-watch-atom! wa :k (fn [k ref old new] nil))
(let [latch (java.util.concurrent.CountDownLatch. 1)
      ts (mapv (fn [_] (Thread. (fn [] (.await latch)
                                  (dotimes [_ 250] (al/swap-atom! wa inc)))))
               (range 4))]
  (doseq [t ts] (.start t))
  (.countDown latch)
  (doseq [t ts] (.join t))
  (check "watched contention exact" 1000 (al/deref-atom wa)))

(println "hosting:" (hosting/hosting-state))
(println (str "PROBE-ATOM: " @checks " checks, " (count @failures) " failures"))
(when (seq @failures) (println "FAILED:" @failures))
(System/exit (if (seq @failures) 1 0))
