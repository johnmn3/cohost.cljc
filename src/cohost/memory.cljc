(ns cohost.memory
  "Eve's value layer, hosted in cohost: the seam-identity contract for
   Eve-backed values (MEMORY-PLAN.md, unit 3).

   Per John's 2026-11-05 ruling, the heap a cohosted program sees is
   itself Eve-atom-backed: there is one value world, and the shim's
   data structures are Eve data structures by construction. Until
   the classes sit on Eve atoms proper (units 4-6), a value whose
   class is answered by an Eve structure reaches the program through
   the seam's current stratum for such values: a stand-in, a plain
   map tagged :cohost/kind ::root, holding the Eve root under
   :memory/root and the value's canonical Clojure-level form under
   :memory/form. Eve values are immutable, so the canonical form is
   computed once, when the root is wrapped (on the JVM seat:
   cohost.memory-eve), and never changes under the stand-in. This
   namespace's contract is the foundation the single world stands
   on: the = / hasheq answers every stratum of a class must meet.

   Equality and hashing are seam-level contracts, not representation
   properties. This namespace defines them:

   - equiv: two values are equiv when their canonical forms are equal
     as host values. A stand-in's canonical form is its :memory/form;
     any other value is its own canonical form. So an Eve-structure
     vector equals the host-stratum vector with the same elements, in
     both argument orders, and two stand-ins wrapping equal roots are
     equiv -- retiring the tagged-map distance unit 1 carried
     (equality = map equality of the stand-in), under which a
     stand-in equalled nothing but a byte-identical stand-in.
   - hasheq: the host hash of the canonical form -- the Murmur-family
     collection hash over the value's Clojure-level form, exactly as
     stock defines it for the Zone-H twin. Eve's SHA-256 Merkle
     digest is the root's identity inside Eve; it never crosses the
     seam as a hash answer (John's ruling: the mapping owed is
     semantic, never numeric).

   The shim's equality/hash dispatch (Util-equiv, Util-hasheq,
   -equiv, -hasheq, -hashCode in cohost.shim) routes through here
   whenever either side is an Eve-backed stand-in; every other value
   takes the host's own answer, unchanged.

   Named distances, kept from the plan:
   - identical? never crosses strata (plan D2): a stand-in is not its
     root, and two stand-ins are not identical however equal.
   - The contract holds at the seam's dispatch points for the values
     themselves. A stand-in nested inside a host-stratum collection
     is compared by the host collection's own machinery, which on
     the JVM seat does not route through the shim; unit 4's
     Eve-backed collections are where nested values meet this layer
     again."
  (:refer-clojure :exclude [equiv hasheq]))

(defn eve-value?
  "True when x is an Eve-backed stand-in of this layer."
  [x]
  (and (map? x) (= ::root (:cohost/kind x))))

(defn wrap
  "Build the stand-in for an Eve root. kind names the root's
   collection kind (:vector, :map, :set, :list, or :value for a
   scalar root); root is the Eve value itself (opaque to this
   layer); form is root's canonical Clojure-level form -- a host
   value equal to the root, with every nested Eve value likewise
   canonicalized."
  [kind root form]
  {:cohost/kind ::root
   :memory/kind kind
   :memory/root root
   :memory/form form})

(defn kind
  "The collection kind of an Eve-backed stand-in's root."
  [x]
  (:memory/kind x))

(defn root
  "The Eve root an Eve-backed stand-in holds."
  [x]
  (:memory/root x))

(defn canonical-form
  "x's canonical Clojure-level form: a stand-in's cached form; any
   other value is its own canonical form."
  [x]
  (if (eve-value? x) (:memory/form x) x))

(declare canonical-form-of)

(defn equiv
  "Seam equality: canonical forms equal as host values. Sequential
   forms keep stock semantics (a Zone-E vector equals a Zone-H
   list with the same elements), because the canonical forms are
   host values compared by the host's own =. The canonical form
   is canonical-form-of's: a bare Eve-backed value (unit 4)
   canonicalizes through its hosting, which is also where
   resident references resolve -- Eve's own equiv cannot see
   through a reference to the registered object, so the seam
   answers, not the root."
  [a b]
  (= (canonical-form-of a) (canonical-form-of b)))

(defn hasheq
  "Seam hash: the host hash of the canonical form -- the same
   Murmur-family collection hash stock computes for the Zone-H
   twin, so hasheq agrees wherever equiv holds. Never the Eve
   root's Merkle digest."
  [x]
  (hash (canonical-form-of x)))

;; ---------------------------------------------------------------------------
;; Unit 4: Eve-backed collections behind the shim's creators.
;;
;; The fill: when in force, the shim's creators for PersistentVector,
;; PersistentHashMap, PersistentArrayMap (answered by Eve map),
;; PersistentHashSet and PersistentList build Eve-backed values
;; through the constructors a seat's hosting registered here, and
;; the shim's dispatch serves those values' seam answers (compare,
;; meta, transients) through this layer. A seat registers hosting
;; only where real Eve runs (JVM seat: cohost.memory-eve); a seat
;; with no Eve hosting cannot take the fill -- NOT-YET, never a
;; substitute realization (MEMORY-PLAN.md SS9).
;;
;; On the JVM seat the values are Eve's own types, bare: Eve's
;; deftypes implement the clojure.lang contracts, so the host's
;; dispatch serves conj/assoc/nth/get/count/seq natively and unit
;; 3's contract holds by Eve's own answers. Meta is the exception:
;; Eve's types stub it ((meta [_] nil), withMeta returns this), so
;; meta rides a wrapper the hosting mints when a program attaches
;; it -- the plan's rule (SS3): meta rides the value's wrapper,
;; never the block.

(defonce ^:private fill-state (atom :off))

(defn fill-on?
  "True when the Eve-backed collections fill is in force."
  []
  (= :on @fill-state))

(defn set-fill!
  "Put the fill in force (true) or take it out (false). Called by
   cohost.boot at every boot, from the boot's :eve-collections opt."
  [on?]
  (reset! fill-state (if on? :on :off))
  (fill-on?))

(defonce ^:private hosting (atom nil))

(defn register-hosting!
  "Register a seat's Eve hosting: a map with
     :value?        (fn [x]) -- x is an Eve-backed collection value
                    of this layer (bare Eve type or meta wrapper)
     :kind          (fn [x]) -- :vector/:map/:set/:list
     :root-of       (fn [x]) -- the bare Eve root a value holds
     :constructors  {:vector (fn [coll]) :map (fn [flat-kvs])
                     :map-from (fn [m]) :set (fn [coll])
                     :list (fn [coll])
                     :empty-vector :empty-map :empty-set :empty-list
                     (values, not fns)}
     :representable? (fn [v]) -- v can be written by Eve's codec
     :canonical-form (fn [x]) -- x's canonical Clojure-level form
     :with-meta     (fn [x m]) -- x carrying meta m (wrapper minted
                    when m is non-nil and x is bare)
     :ops           {:conj :assoc :dissoc :disj :pop :peek :nth
                     :count :get :contains :find -- host fns over
                     seam values (bare or wrapped); :nth/:get take
                     a not-found third argument}
     :install-fill  (fn []) -- seat-specific fill install (the JVM
                    seat rebinds the shim's EMPTY statics)
   Called at load time by the hosting namespace
   (cohost.memory-eve on the JVM seat)."
  [m]
  (reset! hosting m)
  true)

(defn hosting-registered? []
  (some? @hosting))

(defn- host-fn*
  "The hosting map's entry k; throws the named no-hosting error
   when no seat hosting is registered."
  [k]
  (let [h @hosting]
    (when-not h
      (throw (ex-info "cohost.memory: no Eve hosting registered on this seat (NOT-YET)"
                      {:type :cohost.memory/no-hosting :op k})))
    (get h k)))

(defn eve-backed?
  "True when x is an Eve-backed collection value of this layer
   (unit 4): a bare Eve collection or its meta wrapper. Distinct
   from eve-value? (unit 3's stand-ins); a value is never both."
  [x]
  (if-let [f (:value? @hosting)] (boolean (f x)) false))

(defn eve-kind
  "The collection kind of an Eve-backed value."
  [x]
  ((host-fn* :kind) x))

(defn eve-root
  "The bare Eve root an Eve-backed value holds (itself, when bare)."
  [x]
  ((host-fn* :root-of) x))

(defn representable?
  "True when v can be written by Eve's codec on this seat: the
   codec's scalar universe (nil, booleans, strings, keywords,
   symbols, the integer types, doubles/floats, UUID, Date) and
   collections of representable values. Chars, ratios, BigInt,
   BigDecimal, records, host objects and closures are not
   representable -- they are residents of the runtime domain
   (John, Nov 5 2026 ~11:02 ET): at construction they are
   registered on entry and stored as references by the hosting,
   never rejected and never a silent host-backed flip. This
   predicate remains the codec question itself, for callers
   that need it."
  [v]
  ((host-fn* :representable?) v))

(defn unrepresentable!
  "Throw the seam's named error for a value Eve's codec cannot
   write. Retained for explicit-check callers; the construction
   path no longer throws it (residents, above)."
  [v]
  (throw (ex-info (str "cohost.memory: value is not representable in Eve's codec: "
                       (pr-str (type v)))
                  {:type :cohost.memory/unrepresentable :value v})))

(defn check-representable!
  "v, when representable on this seat; else the named error."
  [v]
  (when-not (representable? v) (unrepresentable! v))
  v)

(defn make-vector
  "An Eve-backed vector holding coll's elements (fill in force)."
  [coll]
  ((get (host-fn* :constructors) :vector) coll))

(defn make-map
  "An Eve-backed map from a flat alternating key/value seq, as
   the hash-map creator receives it. Duplicate keys: last wins,
   as stock's create."
  [flat-kvs]
  ((get (host-fn* :constructors) :map) flat-kvs))

(defn make-map-from
  "An Eve-backed map holding m's entries (the array-map
   creator's shape: Eve map answers the class, SS3)."
  [m]
  ((get (host-fn* :constructors) :map-from) m))

(defn make-set
  "An Eve-backed set holding coll's elements."
  [coll]
  ((get (host-fn* :constructors) :set) coll))

(defn make-list
  "An Eve-backed list holding coll's elements, in order."
  [coll]
  ((get (host-fn* :constructors) :list) coll))

(defn empty-of
  "The Eve-backed empty collection of a kind (:vector/:map/:set/
   :list): what (empty an Eve-backed value) and the fill's EMPTY
   statics answer."
  [kind]
  (get (host-fn* :constructors) (keyword (str "empty-" (name kind)))))

(defn install-fill!
  "Run the registered hosting's fill installation (the EMPTY
   statics swap on the JVM seat). A no-op when the hosting
   registered none."
  []
  (when-let [f (host-fn* :install-fill)] (f)))

(defn canonical-form-of
  "x's canonical Clojure-level form, for any value: an Eve-backed
   value's form is computed from its root by the hosting; any
   other value is canonicalized by the same walk (nested
   Eve-backed values inside host collections included); a unit-3
   stand-in answers its cached form."
  [x]
  (if (eve-value? x)
    (:memory/form x)
    (if (hosting-registered?)
      ((host-fn* :canonical-form) x)
      x)))

(defn compare-values
  "Seam compare for Eve-backed collections: the host compare of
   the canonical forms -- stock's lexicographic (sequential) and
   APersistentMap/Set compare semantics exactly, because the
   canonical forms are host values. Eve's types implement no
   Comparable; this is the seam answer the token world promises
   (class-of names them Comparable)."
  [a b]
  (compare (canonical-form-of a) (canonical-form-of b)))

(defn with-meta*
  "x carrying meta m, at the seam: an Eve-backed value goes
   through the hosting (a wrapper is minted when m is non-nil
   and x is bare; the wrapper's own withMeta answers after);
   any other value takes the host's with-meta."
  [x m]
  (if (eve-backed? x)
    ((host-fn* :with-meta) x m)
    (with-meta x m)))

(defn conj-checked
  "conj at the seam for an Eve-backed coll: the hosting's
   conversion re-homes the incoming element on entry (codec
   values in Eve form; host references registered as domain
   residents), so conj never meets a value Eve cannot hold."
  [coll x]
  ((get (host-fn* :ops) :conj) coll x))

(defn assoc-checked
  "assoc at the seam for an Eve-backed coll: key and value
   re-homed on entry, as conj-checked."
  [coll k v]
  ((get (host-fn* :ops) :assoc) coll k v))

(defn- lookup-absent?
  "A key that cannot be in an Eve collection: not an integer
   for a vector (stock's shape). Whether a key's Eve form
   exists at all is the hosting's probe conversion to answer
   (a non-resident host reference is absent; a resident is
   looked up by its reference)."
  [coll k]
  (and (= :vector (eve-kind coll)) (not (integer? k))))

(defn lookup
  "get at the seam for an Eve-backed coll: an absent-shaped key
   answers the not-found value (stock's shape) instead of a
   codec error from serializing the probe key."
  ([coll k] (lookup coll k nil))
  ([coll k not-found]
   (if (lookup-absent? coll k)
     not-found
     ((get (host-fn* :ops) :get) coll k not-found))))

(defn lookup-contains?
  "contains? at the seam: absent-shaped keys are absent."
  [coll k]
  (and (not (lookup-absent? coll k))
       (boolean ((get (host-fn* :ops) :contains) coll k))))

(defn lookup-find
  "find at the seam: absent-shaped keys find nothing."
  [coll k]
  (when-not (lookup-absent? coll k)
    ((get (host-fn* :ops) :find) coll k)))

;; ---------------------------------------------------------------------------
;; Guarded transients (MEMORY-PLAN.md SS3): local mutation is
;; guarded transients -- mutation confined to the scope's local
;; atom, sealed at the boundary. A layer transient's state lives
;; in one atom (its local atom): {:root <Eve root> :meta m
;; :sealed false}. Mutation swaps a new Eve root into that atom
;; through Eve's own persistent ops; nothing mutates in place.
;; persistent! seals: every later use throws. A transient is
;; born in a scope (with-transient-scope, or its own); use after
;; its scope closes throws. The transient value itself is a
;; tagged map stand-in, like unit 1's layer values; its state
;; atom inside makes distinct transients un-equal to anything
;; but themselves, as stock transients are.

(def ^:dynamic *transient-scope*
  "The current transient scope: an atom {:closed false}. Bound
   by with-transient-scope; a transient created outside one gets
   a scope of its own."
  nil)

(defn with-transient-scope
  "Run f with a fresh transient scope bound; the scope closes
   when f returns, and any transient born in it throws on use
   afterwards. Returns f's value."
  [f]
  (let [scope (atom {:closed false})]
    (binding [*transient-scope* scope]
      (try (f) (finally (swap! scope assoc :closed true))))))

(defn transient-value?
  "True when x is a layer guarded transient."
  [x]
  (and (map? x) (= ::transient (:cohost/kind x))))

(defn as-transient
  "The guarded transient of an Eve-backed value: born in the
   current scope, holding the value's bare root and its meta."
  [x]
  {:cohost/kind ::transient
   :memory/kind (eve-kind x)
   :memory/state (atom {:root (eve-root x) :meta (meta x) :sealed false})
   :memory/scope (or *transient-scope* (atom {:closed false}))})

(defn- t-state [t] (:memory/state t))

(defn- t-check!
  "The guard: a sealed transient and a transient outside its
   scope both throw, before any op answers."
  [t]
  (let [st @(t-state t)]
    (when (:sealed st)
      (throw (ex-info "Transient used after persistent! call"
                      {:type :cohost.memory/transient-sealed})))
    (when (:closed @(:memory/scope t))
      (throw (ex-info "Transient used outside its scope"
                      {:type :cohost.memory/transient-scope-closed}))))
  t)

(defn- t-root [t] (:root @(t-state t)))

(defn- t-swap-root!
  [t f & args]
  (t-check! t)
  (apply swap! (t-state t) update :root f args)
  t)

(defn t-conj
  "conj! at the seam. For a map transient, x is an entry, a
   [k v] pair, a map (its entries), or nil -- stock's shapes."
  [t x]
  (t-check! t)
  (if (and (= :map (:memory/kind t)) (some? x))
    (cond
      (map? x) (reduce (fn [t2 [k v]] (t-swap-root! t2 (get (host-fn* :ops) :assoc) k v)) t (seq x))
      (sequential? x) (if (= 2 (count x))
                        (t-swap-root! t (get (host-fn* :ops) :assoc) (first x) (second x))
                        (throw (ex-info "Vector arg to map conj must be a pair" {:type :cohost.memory/bad-conj})))
      :else (throw (ex-info (str "Don't know how to conj " (pr-str (type x)) " onto a map transient") {:type :cohost.memory/bad-conj})))
    (t-swap-root! t (get (host-fn* :ops) :conj) x)))

(defn t-assoc
  "assoc! at the seam (assocN for a vector transient)."
  [t k v]
  (t-swap-root! t (get (host-fn* :ops) :assoc) k v))

(defn t-dissoc
  "dissoc! (without) at the seam."
  [t k]
  (t-swap-root! t (get (host-fn* :ops) :dissoc) k))

(defn t-disj
  "disj! at the seam."
  [t k]
  (t-swap-root! t (get (host-fn* :ops) :disj) k))

(defn t-pop
  "pop! at the seam."
  [t]
  (t-swap-root! t (get (host-fn* :ops) :pop)))

(defn t-persistent!
  "persistent! at the seam: seal the transient and answer its
   root as a persistent Eve-backed value, carrying the
   transient's meta (stock's transients carry meta through)."
  [t]
  (t-check! t)
  (let [st (swap! (t-state t) assoc :sealed true)]
    (if (some? (:meta st))
      (with-meta* (:root st) (:meta st))
      (:root st))))

(defn t-count [t] (t-check! t) ((get (host-fn* :ops) :count) (t-root t)))

(defn t-get
  ([t k] (t-get t k nil))
  ([t k not-found]
   (t-check! t)
   ((get (host-fn* :ops) :get) (t-root t) k not-found)))

(defn t-contains?
  [t k]
  (t-check! t)
  (boolean ((get (host-fn* :ops) :contains) (t-root t) k)))

(defn t-find
  [t k]
  (t-check! t)
  ((get (host-fn* :ops) :find) (t-root t) k))

(defn t-peek
  [t]
  (t-check! t)
  ((get (host-fn* :ops) :peek) (t-root t)))

(defn t-nth
  ([t i] (t-nth t i nil false))
  ([t i not-found] (t-nth t i not-found true))
  ([t i not-found has-nf?]
   (t-check! t)
   (if (= :vector (:memory/kind t))
     (if has-nf?
       ((get (host-fn* :ops) :nth) (t-root t) i not-found)
       (nth (t-root t) i))
     (throw (ex-info (str "nth not supported on this type: " (name (:memory/kind t)) " transient")
                     {:type :cohost.memory/nth-unsupported})))))

(defn t-meta [t] (:meta @(t-state t)))

(defn t-with-meta
  [t m]
  (t-check! t)
  (swap! (t-state t) assoc :meta m)
  t)

(defn transient-class-token
  "The stock transient class token of a layer transient's kind,
   for cast-error messages that name the class stock would name."
  [t]
  (case (:memory/kind t)
    :vector 'clojure.lang.PersistentVector$TransientVector
    :map 'clojure.lang.PersistentHashMap$TransientHashMap
    :set 'clojure.lang.PersistentHashSet$TransientHashSet
    'clojure.lang.ITransientCollection))
