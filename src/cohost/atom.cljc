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
            [cohost.memory :as memory]
            [cohost.thread :as thread]))

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
;; Unit 7: tiered atoms (mem -> disk -> net). The kind is chosen by
;; the developer and carried as metadata on the atom value (John,
;; Q5 RULED: metadata):
;;
;;   (atom x :meta {:eve/kind :disk :eve/path "/var/app/db" :eve/name "app/counter"})
;;   (atom x :meta {:eve/kind :net  :eve/endpoint "http://..." :eve/domain-id "d1"
;;                  :eve/name "app/counter" :eve/api-key "k"})
;;
;; No kind key = :mem, the default, in the runtime atom domain
;; (unit 5's atoms, unchanged). A disk atom's root lives in a
;; file-backed Eve domain at :eve/path, under the registered name
;; :eve/name; a second process naming the same path and name joins
;; it OS-resolved (Eve's exclusive create of the .root file picks
;; the creator, every other process joins) and sees its swaps. A
;; net atom is broker/gateway-resolved through Eve's network atom.
;;
;; Rules, enforced here (MEMORY-PLAN unit 7):
;; - no automatic upgrade or fallback: a kind whose seat hosting is
;;   missing throws; nothing silently becomes a mem atom;
;; - a disk/net root must be Eve-representable: an unrepresentable
;;   root fails loudly at creation, at promotion, and at every
;;   publish, naming the value -- never a silent marshal and never
;;   the host seat (a host-seated root would stop being shared);
;; - kinds move only upward (mem -> disk -> net) by changing the
;;   atom's meta; a downgrade (net -> disk included) throws; so
;;   does re-pointing a tiered atom at a different place.

(def kind-key :eve/kind)
(def ^:private kind-rank {:mem 0 :disk 1 :net 2})
(def ^:private place-keys
  {:disk [:eve/path :eve/name]
   :net [:eve/endpoint :eve/domain-id :eve/name]})

(defn kind-of
  "The kind an atom meta map names (:mem when none)."
  [m]
  (get m kind-key :mem))

(defn- kind-error
  "A layer error user code catches as an ex-info carrying its data
   ({:cohost.atom/error <reason> ...})."
  [msg data]
  (host/ex-info* msg data))

(defn- check-kind-spec! [kind m]
  (when-not (contains? kind-rank kind)
    (throw (kind-error (str "cohost.atom: unknown atom kind " (pr-str kind)
                            " (the kinds are :mem :disk :net)")
                       {:cohost.atom/error :unknown-kind :kind kind})))
  (doseq [k (get place-keys kind)]
    (when (nil? (get m k))
      (throw (kind-error (str "cohost.atom: a " (name kind) " atom needs " (pr-str k) " in its meta")
                         {:cohost.atom/error :missing-place :kind kind :key k}))))
  nil)

(defn tier-representable?
  "What a shared (disk/net) tier can hold: Eve's codec data, and
   Eve values themselves at any depth (a swap fn receives the
   atom's own Eve root and returns Eve structure sharing it; an
   Eve value of a foreign domain Eve refuses loudly on its own).
   Not a unit-3 stand-in (a cohost wrapper over a heap-domain
   root), never a fn or a host object."
  [v]
  (cond (memory/eve-value? v) false
        (eve-typed? v) true
        (or (nil? v) (boolean? v) (string? v) (keyword? v) (symbol? v) (number? v)) true
        (map? v) (and (every? tier-representable? (keys v))
                      (every? tier-representable? (vals v)))
        (or (set? v) (vector? v) (sequential? v)) (every? tier-representable? v)
        :else false))

(defn- first-unrepresentable
  "The first value inside v Eve cannot hold in a shared (disk/net)
   tier, or ::none."
  [v]
  (cond (tier-representable? v) ::none
        (map? v) (or (some (fn [[k x]]
                             (let [a (first-unrepresentable k)]
                               (if (= ::none a)
                                 (let [b (first-unrepresentable x)] (when-not (= ::none b) b))
                                 a)))
                           v)
                     v)
        (or (set? v) (sequential? v))
        (or (some (fn [x] (let [a (first-unrepresentable x)] (when-not (= ::none a) a))) v) v)
        :else v))

(defn check-tier-value!
  "Throw, naming the value, when v cannot live in a kind-tier
   atom. Mem atoms accept anything (unit 5's host seat)."
  [kind v]
  (when (and (not= :mem kind) (not (tier-representable? v)))
    (let [bad (first-unrepresentable v)]
      (throw (kind-error (str "cohost.atom: a " (name kind) " atom cannot hold "
                              (host/try-all (fn [] (pr-str bad)) (fn [_] "an unprintable value"))
                              " -- Eve cannot represent it in a shared tier"
                              " (no silent marshal; keep it in a :mem atom)")
                         {:cohost.atom/error :unrepresentable :kind kind}))))
  v)

(defn- tiered? [s] (not= :mem (:kind s :mem)))

(defn- make-tier-handle [kind m init]
  (let [mk (:make-tier-atom @factory)]
    (when-not mk
      (throw (kind-error (str "cohost.atom: this seat has no hosting for " (name kind)
                              " atoms (NOT-YET on this seat; no fallback to :mem)")
                         {:cohost.atom/error :kind-unavailable :kind kind})))
    (mk kind m init)))

;; ----------------------------------------------------------------
;; Construction

(declare register-conveying!)

(defn make-atom
  "A layer atom holding init. opts: {:meta m :validator f} --
   meta rides the wrapper state (an atom's meta is the
   wrapper's, like its watches: Eve's atoms carry none); the
   validator runs against the initial root, as stock's does.
   The meta's :eve/kind picks the tier (unit 7)."
  ([init] (make-atom init nil))
  ([init {:keys [meta validator]}]
   (let [kind (kind-of meta)]
     (if (= :mem kind)
       (let [rep (root-representable? init)
             h ((:make-atom @factory) (if rep init host-held))
             st (atom {:eve h
                       :gate (if rep :open :host)
                       :root (when-not rep init)
                       :kind :mem
                       ;; never published: stock core's (atom x :meta m)
                       ;; is (new Atom x) then reset-meta!, so a kind
                       ;; arriving on a fresh atom is create-or-join
                       :fresh true
                       :watches {}
                       :validator validator
                       :meta meta
                       :readers 0})
             a {:cohost/kind ::atom :state st}]
         (validate! validator init)
         a)
       (do (check-kind-spec! kind meta)
           (check-tier-value! kind init)
           (validate! validator init)
           (let [h (make-tier-handle kind meta init)]
             ;; a joined atom answers its existing root: the
             ;; validator holds over what this process will see
             (validate! validator @h)
             (register-conveying!
              {:cohost/kind ::atom
               :state (atom {:eve h :gate :open :root nil :kind kind
                             :watches {} :validator validator :meta meta
                             :readers 0})})))))))

(defn kind
  "The atom's current kind (:mem, :disk or :net)."
  [a]
  (:kind @(:state a) :mem))

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
   host-held so its slab root names the state honestly. Never
   for a tiered atom: its root must stay shared (unit 7)."
  [s new]
  (check-tier-value! (:kind s :mem) new)
  (when (tiered? s)
    ;; representable yet refused by Eve: still no host seat
    (throw (kind-error "cohost.atom: Eve refused the publish into a shared-tier atom"
                       {:cohost.atom/error :publish-refused :kind (:kind s)})))
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
        k (:kind s :mem)
        f' (if (or vf (not= :mem k))
             (fn [old] (let [new (apply f old args)]
                         (check-tier-value! k new)
                         (validate! vf new)
                         new))
             (fn [old] (apply f old args)))]
    (host/try-all (fn [] (swap! (:eve s) f'))
                  (fn [e] (if (publish-refused-error? e)
                            ::transition
                            (throw e))))))

;; ----------------------------------------------------------------
;; The tiered path (unit 7). A disk/net atom is shared with other
;; processes, so a process-local claim cannot make a publish
;; exclusive: every publish rides the Eve handle's own CAS loop (a
;; disk atom's CAS word in the mapped root file; a net atom's
;; broker CAS). f runs per attempt, as stock's does; only the
;; committing attempt's (old, new) pair survives in the volatile,
;; because every attempt overwrites it -- so watches fire with the
;; exact pair of the winning publish. Watches fire for this
;; process's publishes; another process's swaps are seen on deref
;; (Eve has no cross-process notification; named in the report).

(defn- swap-tiered! [a f args]
  (let [s @(:state a)
        k (:kind s)
        vf (:validator s)
        pair (volatile! nil)]
    (swap! (:eve s) (fn [old]
                      (let [new (apply f old args)]
                        (check-tier-value! k new)
                        (validate! vf new)
                        (vreset! pair [old new])
                        new)))
    (let [[old new] @pair]
      (fire-watches! a s old new)
      new)))

(defn- cas-tiered! [a oldv newv]
  (let [s @(:state a)
        k (:kind s)
        hit (volatile! nil)]
    (check-tier-value! k newv)
    (swap! (:eve s) (fn [cur]
                      (if (memory/equiv cur oldv)
                        (do (validate! (:validator s) newv)
                            (vreset! hit [cur newv])
                            newv)
                        (do (vreset! hit nil) cur))))
    (if-let [[cur new] @hit]
      (do (fire-watches! a s cur new) true)
      false)))

;; ----------------------------------------------------------------
;; The Atom surface (called by the shim's branches)

(defn swap-atom! [a f & args]
  (let [s @(:state a)]
    (when (:fresh s) (swap! (:state a) dissoc :fresh))
    (cond (tiered? s) (swap-tiered! a f args)
          (= :host (:gate s)) (swap-host! a f args)
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
    (when (:fresh s) (swap! (:state a) dissoc :fresh))
    (cond (tiered? s) (swap-tiered! a (fn [_] v) [])
          (= :host (:gate s)) (swap-host! a (fn [_] v) [])
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
    (when (:fresh s0) (swap! (:state a) dissoc :fresh))
    (cond
      (tiered? s0) (cas-tiered! a oldv newv)
      (= :host (:gate s0))
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
      :else
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

;; Unit 7: a meta change that names a different :eve/kind is a
;; promotion. Upward only (mem -> disk -> net); the current root is
;; published into the new tier, and must be representable there.
;; The atom stays the same value (same wrapper state); only its
;; Eve handle changes, under the claim for a mem atom so no publish
;; races the move.

(defn- same-place? [kind m0 m1]
  (every? (fn [k] (= (get m0 k) (get m1 k))) (get place-keys kind)))

(defn- promote! [a k0 k1 m1]
  (check-kind-spec! k1 m1)
  (let [st (:state a)
        s (if (and (= :mem k0) (= :open (:gate @st))) (claim! a) @st)
        s (if (= ::host s) @st s)
        claimed? (= :busy (:gate @st))
        root (if (= :host (:gate s)) (:root s) @(:eve s))
        done (volatile! false)]
    (try
      (check-tier-value! k1 root)
      (let [h (make-tier-handle k1 m1 root)
            there @h]
        (when (:fresh s) (validate! (:validator s) there))
        (when-not (or (:fresh s) (memory/equiv there root))
          (throw (kind-error (str "cohost.atom: cannot promote to " (name k1)
                                  " -- the target atom already holds a different value")
                             {:cohost.atom/error :promotion-target-occupied :kind k1})))
        (swap! st (fn [x] (-> (assoc x :eve h :gate :open :root nil :kind k1 :meta m1 :readers 0)
                              (dissoc :fresh))))
        (vreset! done true)
        (register-conveying! a))
      (finally
        (when (and claimed? (not @done))
          (release! a {:gate :open :root (:root s)}))))
    a))

(defn- apply-meta! [a m1]
  (let [s @(:state a)
        k0 (:kind s :mem)
        k1 (kind-of m1)]
    (cond
      (= k0 k1)
      (do (when (and (not= :mem k0) (not (same-place? k0 (:meta s) m1)))
            (throw (kind-error (str "cohost.atom: a " (name k0) " atom cannot be re-pointed"
                                    " at a different place (make a new atom)")
                               {:cohost.atom/error :re-point :kind k0})))
          (swap! (:state a) assoc :meta m1)
          a)
      (not (contains? kind-rank k1))
      (do (check-kind-spec! k1 m1) a)
      (< (kind-rank k1) (kind-rank k0))
      (throw (kind-error (str "cohost.atom: downgrading a " (name k0) " atom to " (name k1)
                              " is not supported")
                         {:cohost.atom/error :downgrade :from k0 :to k1}))
      :else (promote! a k0 k1 m1))))

(defn with-meta-atom [a m]
  (apply-meta! a m)
  (with-meta a m))

(defn set-meta-atom! [a m]
  (apply-meta! a m))

(defn alter-meta-atom [a f args]
  (apply-meta! a (apply f (:meta @(:state a)) args)))

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

;; ----------------------------------------------------------------
;; Unit 7: bindings conveyance across processes, riding the shared
;; atom itself.
;;
;; Expressed in metadata, like the kinds (no new function surface):
;;   (def shared (atom {} :meta {:eve/kind :disk :eve/path p :eve/name "app/s"
;;                               :eve/conveys true}))
;;   (def ^{:dynamic true :eve/convey shared} *tenant* nil)
;; A thread-boundary capture (core's binding-conveyor-fn through the
;; shim's Var/getThreadBindings, and the thread layer's futures and
;; agent sends) PUBLISHES the frame it captured for vars conveyed
;; through a shared atom, and CONVEYS the frame other processes
;; published there for vars this thread has not bound itself. So
;;   instance A:  (binding [*tenant* "acme"] @(future ...))
;;   instance B:  @(future *tenant*)            ; => "acme"
;; The frame lives in the shared atom's Eve meta-atom (eve.atom/
;; meta-atom: coordination state kept beside the atom, same domain,
;; a reserved registry name): the atom's own value, version and
;; equality are untouched. A frame entry is {var-symbol value}; its
;; values obey the tier's rule -- unrepresentable fails loudly. Disk
;; kind only: Eve's network atom has no meta-atom (named gap).

(defonce ^:private conveying
  ;; the conveying shared atoms this process made: [layer-atom ...]
  (atom []))

(defn- conveying? [a] (true? (:eve/conveys (meta-of a))))

(defn- frame-handle [a]
  (let [s @(:state a)
        fa (:frame-atom @factory)]
    (when-not (= :disk (:kind s))
      (throw (kind-error (str "cohost.atom: binding conveyance rides a :disk atom (this one is "
                              (name (:kind s :mem)) ")")
                         {:cohost.atom/error :convey-unsupported :kind (:kind s :mem)})))
    (or (:frame s)
        (let [h (fa (:eve s))]
          (swap! (:state a) assoc :frame h)
          h))))

(defn- var-qsym [v]
  (let [n (host/var-ns v)]
    (symbol (str (if (symbol? n) n (host/try-all (fn [] (ns-name n)) (fn [_] n))))
            (str (host/var-sym v)))))

(defn- convey-target [v]
  (let [t (get (host/try-all (fn [] (meta v)) (fn [_] nil)) :eve/convey)]
    (when (atom-value? t) t)))

(defn- publish-frames! [b]
  (let [groups (volatile! {})]
    (doseq [[v x] b]
      (when-let [t (convey-target v)]
        (when-not (conveying? t)
          (throw (kind-error "cohost.atom: :eve/convey names an atom without :eve/conveys true"
                             {:cohost.atom/error :convey-not-shared})))
        (check-tier-value! :disk x)
        (vswap! groups update (:state t) (fn [[_ m]] [t (assoc (or m {}) (var-qsym v) x)]))))
    (doseq [[_ [t entries]] @groups]
      (swap! (frame-handle t) (fn [f] (merge (if (map? f) (into {} f) {}) entries)))))
  nil)

(defn- resolve-qsym [q]
  (get (host/try-all (fn [] (host/ns-interns-map (symbol (namespace q))))
                     (fn [_] nil))
       (symbol (name q))))

(defn convey-frame
  "The capture hook: publish b's conveyed vars, then add the frames
   other processes published for vars b does not bind."
  [b]
  (if (or (not (fill-active?)) (empty? @conveying))
    b
    (do (publish-frames! b)
        (let [bound (into #{} (map var-qsym) (keys b))]
          (reduce (fn [acc t]
                    (let [f @(frame-handle t)]
                      (reduce (fn [acc [q x]]
                                (if (contains? bound q)
                                  acc
                                  (if-let [v (resolve-qsym q)]
                                    (assoc acc v x)
                                    acc)))
                              acc
                              (when (map? f) f))))
                  b
                  @conveying)))))

(defn register-conveying!
  "Track a :disk atom whose meta says :eve/conveys true (make-atom
   and promotion call this); installs the capture hook."
  [a]
  (when (conveying? a)
    (frame-handle a)
    (swap! conveying (fn [v] (if (some #(identical? (:state %) (:state a)) v) v (conj v a))))
    (thread/set-capture-hook! convey-frame))
  a)
