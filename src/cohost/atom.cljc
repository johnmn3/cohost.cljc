(ns cohost.atom
  "Eve-in-cohost, unit 5 (doc/eve-cohost/MEMORY-PLAN.md): the
   portable atom layer. clojure.lang.Atom, filled from behind the
   shim (the closure-seam ruling: Eve's facilities sit behind the
   Java facade, never beside it), backed by Eve atoms in the mem
   tier.

   An atom of the layer is a plain map tagged :cohost/kind ::atom
   (the cohost.thread value shape) whose :state is a host atom
   holding {:eve -- the Eve atom handle, the CAS word and slab
   root; :gate -- :open (the root lives in the Eve atom), :host
   (the root lives in the wrapper's seat) or :busy (a claimed
   publish is in flight); :root -- the host seat; :watches;
   :validator; :readers -- fast-path swaps in flight}.

   Why the wrapper at all, measured on the JVM seat (unit 5
   STATUS): Eve's codec refuses host values outright (a fn, a
   host object, an atom: value->eve-bytes throws), and Eve's
   MmapAtom carries no compareAndSet, no watches, no validators
   and no metadata. So: a root Eve can represent lives in the
   Eve atom, and swap! delegates to Eve's own CAS loop (its f
   runs per CAS attempt, exactly stock's shape, so a validator
   rides inside the wrapped f). A root Eve cannot represent --
   a fn, a host object, the multimethod state map -- is held by
   reference in the wrapper's host seat: a domain resident,
   registered on entry (plan SS1's Zone-X shape; John,
   2026-11-05: host references held in atoms are residents, no
   special accounting). The atom stays an atom; only the seat
   changes, and the transition is one-way (:open -> :host): a
   host-seat root is exactly what unit 7's tier promotion will
   refuse, loudly, by name.

   Watches need the exact (old, new) pair of the winning
   publish, which Eve's loop does not surface; a watched swap
   takes the claim instead: :gate goes :busy, readers drain,
   f runs once against the materialized root, the pair fires
   after the publish commits. compare-and-set! compares roots
   by seam equality (cohost.memory/equiv over canonical forms:
   a materializing deref leaves no identity to compare) and
   also runs under the claim.

   The namespace/var registry (plan SS10, ruling Q-A) is an Eve
   atom in the STARTUP contention domain, minted by the seat
   hosting: an atom whose root is the table {ns {var entry}},
   entry {:root v} for a data root, {:resident true} for a
   host root (a fn: the host var stays its seat). The shim's
   Var paths publish here when the fill is in force.

   The layer is portable; the seat hosting supplies the Eve
   handles through a factory (cohost.atom-eve on the JVM seat).
   With no factory the layer is unavailable and the shim keeps
   the host's atom, the unit 1 availability shape."
  (:require [cohost.adapter :as host]
            [cohost.memory :as memory]))

;; ----------------------------------------------------------------
;; Seat hosting + the fill switch

(defonce ^:private factory
  ;; {:make-atom (fn [slab-init] eve-handle)
  ;;  :registry-atom (fn [] eve-handle)}
  (atom nil))

(defn install-factory!
  "Called by the seat hosting (cohost.atom-eve on the JVM) at
   install time."
  [f]
  (reset! factory f)
  nil)

(defn available? [] (some? @factory))

(defonce ^:private fill-flag (atom false))

(defn fill-active? [] (boolean @fill-flag))

(defn set-fill!
  "Turn the fill on (the boot opt) or off. When on, the shim's
   new-Atom mints layer atoms and its Var paths publish to the
   registry; when off, nothing in this namespace is called."
  [on]
  (reset! fill-flag (boolean on))
  nil)

;; The value the Eve atom holds while the root lives in the
;; wrapper's host seat: the slab root names the state honestly.
(def host-held :cohost.atom/host-held)

(defn atom-value? [x] (and (map? x) (= ::atom (:cohost/kind x))))

;; ----------------------------------------------------------------
;; Representability: what Eve's codec holds (measured, unit 5
;; STATUS): nil, booleans, numbers, strings, keywords, symbols,
;; and maps/sets/vectors/seqs of the same. A wrong true here is
;; safe -- Eve throws at publish and the caller falls to the
;; host seat; a wrong false costs residence, never semantics.

(defn- eve-typed?
  "An Eve value itself (an EveVector and kin), as opposed to
   host data Eve can serialize. Asked of the seat hosting: the
   portable layer does not name Eve's types."
  [v]
  (boolean (when-let [f @factory]
             (when-let [p (:eve-value? f)]
               (p v)))))

(defn representable? [v]
  (cond (nil? v) true
        (boolean? v) true
        (string? v) true
        (keyword? v) true
        (symbol? v) true
        (number? v) true
        ;; A seam stand-in (unit 3) anywhere in the root: its
        ;; identity is its slab root, which lives in its own
        ;; domain; serializing it into this atom would degrade
        ;; it to its form at best and read a foreign slab as
        ;; garbage at worst. The whole root seats as a resident
        ;; instead -- the stand-in itself is held, so its root's
        ;; identity is exactly stable.
        (memory/eve-value? v) false
        ;; An Eve value nested in host data: same reasoning --
        ;; Eve's writer would walk it with this atom's sio.
        (eve-typed? v) false
        (map? v) (and (every? representable? (keys v))
                      (every? representable? (vals v)))
        (set? v) (every? representable? v)
        (vector? v) (every? representable? v)
        (sequential? v) (every? representable? v)
        :else false))

(defn root-representable?
  "Representability for a whole root: additionally, a bare Eve
   value as the root itself -- Eve's swap path stores it by
   pointer and refuses a foreign domain loudly (below), so
   trying is safe."
  [v]
  (or (representable? v)
      (eve-typed? v)))

(defn- unsupported-type-error? [e]
  (let [m (host/error-message e)]
    (and (string? m) (not (neg? (host/index-of m "unsupported type" 0))))))

(defn- publish-refused-error?
  "Eve refused the publish: a type its codec cannot hold, or
   an Eve value from a different slab domain. Either way the
   root seats in the wrapper instead -- semantics preserved,
   loudly in the state (:gate :host), never corrupted."
  [e]
  (let [m (host/error-message e)]
    (and (string? m)
         (or (not (neg? (host/index-of m "unsupported type" 0)))
             (not (neg? (host/index-of m "different slab domain" 0)))))))

(defn- validate! [validator v]
  (when (and validator (not (validator v)))
    (throw (host/ex* 'java.lang.IllegalStateException "Invalid reference state"))))

(defn- fire-watches! [a s old new]
  (doseq [[k wf] (:watches s)]
    (wf k a old new))
  nil)

;; ----------------------------------------------------------------
;; Construction

(defn make-atom
  "A layer atom holding init. opts: {:meta m :validator f} --
   meta rides the wrapper state (an atom's meta is the
   wrapper's, like its watches: Eve's atoms carry none); the
   validator runs against the initial root, as stock's does."
  ([init] (make-atom init nil))
  ([init {:keys [meta validator]}]
   (let [rep (root-representable? init)
         h ((:make-atom @factory) (if rep init host-held))
         st (atom {:eve h
                   :gate (if rep :open :host)
                   :root (when-not rep init)
                   :watches {}
                   :validator validator
                   :meta meta
                   :readers 0})
         a {:cohost/kind ::atom :state st}]
     (validate! validator init)
     a)))

(defn state-of
  "The atom's wrapper state (probe/support)."
  [a]
  @(:state a))

(defn eve-handle
  "The Eve atom handle under the wrapper (probe/support)."
  [a]
  (:eve @(:state a)))

;; ----------------------------------------------------------------
;; Deref: plain deref, materializing, Eve's as-is behavior
;; (unit 7 owns guarded crossings).

(defn deref-atom [a]
  (loop []
    (let [s @(:state a)]
      (case (:gate s)
        :host (:root s)
        :open @(:eve s)
        ;; :busy -- a claimed publish is in flight; it is one
        ;; Eve reset plus a state swap, so spin for it to settle.
        (recur)))))

;; ----------------------------------------------------------------
;; The claim: an exclusive publish window. Fast-path swaps hold
;; :readers while Eve's loop runs; a claim waits for them to
;; drain, and no new reader claims while :gate is :busy.

(defn- claim-reader! [a]
  (let [st (:state a)]
    (loop []
      (let [s @st]
        (cond (not= :open (:gate s)) false
              (compare-and-set! st s (update s :readers inc)) true
              :else (recur))))))

(defn- release-reader! [a]
  (swap! (:state a) update :readers dec)
  nil)

(defn- claim!
  "Take the claim; returns the state snapshot as claimed, or
   ::host if the atom's root lives in the host seat by the time
   the claim lands (the caller then takes the host path)."
  [a]
  (let [st (:state a)]
    (loop []
      (let [s @st]
        (cond (and (= :open (:gate s)) (zero? (:readers s)))
              (if (compare-and-set! st s (assoc s :gate :busy))
                s
                (recur))
              (= :host (:gate s)) ::host
              :else (recur))))))

(defn- release! [a outcome]
  (swap! (:state a) (fn [s] (assoc s :gate (:gate outcome) :root (:root outcome))))
  nil)

(defn- host-outcome
  "Publish new into the host seat: the Eve atom is marked
   host-held so its slab root names the state honestly."
  [s new]
  (reset! (:eve s) host-held)
  {:gate :host :root new})

(defn- publish-claimed!
  "Publish new under the claim: into the Eve atom when it is
   representable (falling to the host seat if Eve's codec
   refuses it after all), else into the host seat. Returns the
   release outcome."
  [s new]
  (if (root-representable? new)
    (host/try-all (fn [] (reset! (:eve s) new) {:gate :open :root nil})
                  (fn [e] (if (publish-refused-error? e)
                            (host-outcome s new)
                            (throw e))))
    (host-outcome s new)))

;; ----------------------------------------------------------------
;; The host-seat path: a stock-shaped CAS loop on the wrapper
;; state. Once the root lives here it stays here (one-way).

(defn- swap-host! [a f args]
  (let [st (:state a)]
    (loop []
      (let [s @st
            old (:root s)
            new (apply f old args)]
        (validate! (:validator s) new)
        (if (compare-and-set! st s (assoc s :root new))
          (do (fire-watches! a s old new)
              new)
          (recur))))))

;; ----------------------------------------------------------------
;; The claimed path (watches present, or CAS): f runs exactly
;; once, against the materialized root; the watch pair is the
;; publish's own.

(defn- swap-claimed! [a f args]
  (let [s (claim! a)]
    (if (= ::host s)
      (swap-host! a f args)
      (let [released (volatile! false)]
        (try
          (let [old @(:eve s)
                new (apply f old args)]
            (validate! (:validator s) new)
            (let [outcome (publish-claimed! s new)]
              (release! a outcome)
              (vreset! released true)
              (fire-watches! a s old new)
              new))
          (finally
            (when-not @released
              (release! a {:gate :open :root (:root s)}))))))))

(defn- transition!
  "A swap whose result Eve refused (or a reset to a value Eve
   cannot hold), reached from the unclaimed path: re-run under
   the claim against a fresh root and publish into the host
   seat. f may run twice in this rare case; stock's contract
   already allows f multiple runs."
  [a f args]
  (let [s (claim! a)]
    (if (= ::host s)
      (swap-host! a f args)
      (let [released (volatile! false)]
        (try
          (let [old @(:eve s)
                new (apply f old args)]
            (validate! (:validator s) new)
            (release! a (host-outcome s new))
            (vreset! released true)
            new)
          (finally
            (when-not @released
              (release! a {:gate :open :root (:root s)}))))))))

(defn- eve-swap!
  "The unclaimed Eve path: Eve's own CAS loop, a validator
   riding inside the wrapped f (stock runs its validator per
   attempt, as Eve runs f). Runs under the caller's reader
   claim; returns ::transition when Eve's codec refused the
   result -- the caller transitions only after releasing its
   claim, since the claim path waits for readers to drain."
  [a f args]
  (let [s @(:state a)
        vf (:validator s)
        f' (if vf
             (fn [old] (let [new (apply f old args)]
                         (validate! vf new)
                         new))
             (fn [old] (apply f old args)))]
    (host/try-all (fn [] (swap! (:eve s) f'))
                  (fn [e] (if (publish-refused-error? e)
                            ::transition
                            (throw e))))))

;; ----------------------------------------------------------------
;; The Atom surface (called by the shim's branches)

(defn swap-atom! [a f & args]
  (let [s @(:state a)]
    (cond (= :host (:gate s)) (swap-host! a f args)
          (seq (:watches s)) (swap-claimed! a f args)
          :else (if (claim-reader! a)
                  (let [r (try (eve-swap! a f args)
                               (finally (release-reader! a)))]
                    (if (= ::transition r)
                      (transition! a f args)
                      r))
                  ;; the gate moved under us; re-dispatch fresh
                  (apply swap-atom! a f args)))))

(defn reset-atom! [a v]
  (let [s @(:state a)]
    (cond (= :host (:gate s)) (swap-host! a (fn [_] v) [])
          (seq (:watches s)) (swap-claimed! a (fn [_] v) [])
          :else (if (claim-reader! a)
                  (let [r (try
                            (validate! (:validator @(:state a)) v)
                            (if (root-representable? v)
                              (host/try-all (fn [] (reset! (:eve @(:state a)) v) v)
                                            (fn [e] (if (publish-refused-error? e)
                                                      ::transition
                                                      (throw e))))
                              ::transition)
                            (finally (release-reader! a)))]
                    (if (= ::transition r)
                      (transition! a (fn [_] v) [])
                      r))
                  (reset-atom! a v)))))

(defn compare-and-set-atom! [a oldv newv]
  (let [s0 @(:state a)]
    (if (= :host (:gate s0))
      ;; host seat: the same comparison, on the wrapper state
      (let [st (:state a)]
        (loop []
          (let [s @st]
            (cond (not= :host (:gate s)) (compare-and-set-atom! a oldv newv)
                  (not (memory/equiv (:root s) oldv)) false
                  :else (do (validate! (:validator s) newv)
                            (if (compare-and-set! st s (assoc s :root newv))
                              (do (fire-watches! a s (:root s) newv)
                                  true)
                              (recur)))))))
      (let [s (claim! a)]
        (if (= ::host s)
          (compare-and-set-atom! a oldv newv)
          (let [released (volatile! false)]
            (try
              (let [cur @(:eve s)]
                (if (memory/equiv cur oldv)
                  (do (validate! (:validator s) newv)
                      (let [outcome (publish-claimed! s newv)]
                        (release! a outcome)
                        (vreset! released true)
                        (fire-watches! a s cur newv)
                        true))
                  (do (release! a {:gate :open :root (:root s)})
                      (vreset! released true)
                      false)))
              (finally
                (when-not @released
                  (release! a {:gate :open :root (:root s)}))))))))))

(defn swap-vals-atom! [a f & args]
  (loop []
    (let [old (deref-atom a)
          new (apply f old args)]
      (if (compare-and-set-atom! a old new)
        [old new]
        (recur)))))

(defn reset-vals-atom! [a v]
  (loop []
    (let [old (deref-atom a)]
      (if (compare-and-set-atom! a old v)
        [old v]
        (recur)))))

(defn add-watch-atom! [a k f]
  (swap! (:state a) assoc-in [:watches k] f)
  a)

(defn remove-watch-atom! [a k]
  (swap! (:state a) update :watches dissoc k)
  a)

(defn get-watches-atom [a] (:watches @(:state a)))

(defn set-validator-atom! [a vf]
  (when vf
    (validate! vf (deref-atom a)))
  (swap! (:state a) assoc :validator vf)
  nil)

(defn get-validator-atom [a] (:validator @(:state a)))

;; Meta: the wrapper state's, like the watches. with-meta-atom
;; returns a fresh stand-in over the same state, as stock's
;; withMeta returns a fresh Atom over the same state cell.
(defn meta-of [a] (:meta @(:state a)))

(defn with-meta-atom [a m]
  (swap! (:state a) assoc :meta m)
  (with-meta a m))

(defn set-meta-atom! [a m]
  (swap! (:state a) assoc :meta m)
  a)

(defn alter-meta-atom [a f args]
  (swap! (:state a) (fn [s] (assoc s :meta (apply f (:meta s) args))))
  a)

;; ----------------------------------------------------------------
;; The registry: namespace/var containers as residents of the
;; startup domain (plan SS10). The registry atom's root is the
;; table {ns-sym {var-sym entry}}; the shim's Var paths publish
;; here when the fill is in force.

(defn registry-atom
  "The registry Eve atom, or nil when no hosting is installed."
  []
  (when-let [f @factory]
    ((:registry-atom f))))

(defn registry-table
  "The registry table, materialized (probe/support)."
  []
  (when-let [ra (registry-atom)]
    @ra))

(defn publish-intern!
  "Record that var-sym is interned in ns-sym. The entry starts
   as a resident: the host var is the seat until a data root is
   published over it."
  [ns-sym var-sym]
  (when (fill-active?)
    (when-let [ra (registry-atom)]
      (swap! ra (fn [t]
                  (if (contains? (get t ns-sym) var-sym)
                    t
                    (assoc-in t [ns-sym var-sym] {:resident true}))))))
  nil)

(defn publish-var-root!
  "Publish a var's root into its registry entry: a data root is
   held inline ({:root v}); a root Eve cannot represent is a
   resident ({:resident true}) -- the host var remains its seat
   (John, 2026-11-05: host references in vars are domain
   residents, registered on entry)."
  [ns-sym var-sym root]
  (when (fill-active?)
    (when-let [ra (registry-atom)]
      (swap! ra assoc-in [ns-sym var-sym]
             (if (representable? root)
               {:root root}
               {:resident true}))))
  nil)

(defn publish-namespace!
  "Sweep one namespace's interns into the registry: every
   interned var is recorded, with its root where it is bound.
   On the JVM seat a program's defs intern through the host
   compiler, which no shim path sees; the registry therefore
   tracks namespaces by sweeps at load boundaries (the boot
   sweeps what it booted when the fill turns on, and each
   library as its load completes), on top of the live publishes
   the shim's Var paths make (Var/intern, bindRoot, alterRoot).
   No-op when the fill is off."
  [ns-sym]
  (when (fill-active?)
    (when-let [m (host/try-all (fn [] (host/ns-interns-map ns-sym))
                               (fn [_] nil))]
      (doseq [[sym v] m]
        (publish-intern! ns-sym sym)
        (when (host/var-bound? v)
          (publish-var-root! ns-sym sym (host/var-root v))))))
  nil)
