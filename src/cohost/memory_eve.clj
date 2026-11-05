(ns cohost.memory-eve
  "JVM-seat hosting for cohost.memory (MEMORY-PLAN.md, unit 3): real
   Eve roots, in memory, wrapped as Eve-backed stand-ins.

   This namespace is JVM-only (.clj) and is loaded only where Eve's
   CLJC sources are on the classpath; nothing in cohost's shim or
   boot requires it, so the jank and CLJS builds never see it. The
   roots live in one in-memory heap slab context (the mem tier: no
   regions, no files). Eve values are immutable, so a stand-in's
   canonical form, computed here at wrap time by walking the root,
   is a permanent snapshot of the root's value.

   An Eve heap atom's deref already materializes host values on this
   seat; atom-root-snapshot therefore re-hosts the current root in
   Eve and wraps that -- the snapshot is an Eve-backed value equal
   to the root the atom held at deref time, per Eve's per-atom
   snapshot model."
  (:require [cohost.memory :as memory]
            [eve.deftype-proto.alloc :as alloc]
            [eve.mem :as eve-mem]
            [eve.vec :as eve-vec]
            [eve.map :as eve-map]
            [eve.set :as eve-set]
            [eve.list :as eve-list]
            [eve.atom :as eve-atom]))

(defonce ^:private slab-ctx
  ;; the seat's one in-memory heap slab context, held in an atom
  (atom nil))

(defn- ctx []
  (or @slab-ctx
      ;; Unit 4: the default capacities (1 MB per size class)
      ;; exhaust under ordinary collection traffic -- a class's
      ;; region filling makes the allocator index past the class
      ;; table ("Index 6 out of bounds for length 6": a 10k-entry
      ;; map or a 20k vector on the default ctx; measured in this
      ;; lane's probes). 32 MB per class carries the battery /
      ;; library / corpus workloads with headroom. Dead roots are
      ;; not reclaimed in this ctx -- reclamation belongs to the
      ;; runtime atom domain (units 5/6), named in UNIT4-REPORT.
      (let [c (alloc/make-jvm-heap-slab-ctx
               {:capacities (into {} (map (fn [i] [i 33554432]) (range 16)))})]
        (reset! slab-ctx c)
        c)))

(defonce ^:private atom-ids (atom 0))

(defn canonicalize
  "Walk an Eve root (or a host value Eve's codec materialized) to
   its canonical Clojure-level form: Eve collections become the
   host collection of the same kind, recursively; host collections
   are rebuilt with any Eve values inside them canonicalized;
   scalars pass through."
  [v]
  (cond
    (instance? eve.vec.EveVector v) (into [] (map canonicalize) (seq v))
    (instance? eve.map.EveHashMap v) (into {} (map (fn [e] [(canonicalize (key e)) (canonicalize (val e))])) (seq v))
    (instance? eve.set.EveHashSet v) (into #{} (map canonicalize) (seq v))
    (instance? eve.list.EveList v) (apply list (map canonicalize (seq v)))
    (vector? v) (into [] (map canonicalize) v)
    (map? v) (into {} (map (fn [e] [(canonicalize (key e)) (canonicalize (val e))])) v)
    (set? v) (into #{} (map canonicalize) v)
    (sequential? v) (apply list (map canonicalize v))
    :else v))

(defn eve-vector
  "An Eve-backed stand-in whose root is an Eve persistent vector
   holding coll's elements."
  [coll]
  (let [root (eve-vec/eve-vec (ctx) coll)]
    (memory/wrap :vector root (canonicalize root))))

(defn eve-map
  "An Eve-backed stand-in whose root is an Eve persistent hash map
   holding m's entries."
  [m]
  (let [sio (ctx)
        root (eve-map/jvm-eve-hash-map-from-offset sio (eve-map/jvm-write-map! sio m))]
    (memory/wrap :map root (canonicalize root))))

(defn eve-set
  "An Eve-backed stand-in whose root is an Eve persistent hash set
   holding s's elements."
  [s]
  (let [sio (ctx)
        ser (partial eve-mem/value+sio->eve-bytes sio)
        root (eve-set/jvm-eve-hash-set-from-offset sio (eve-set/jvm-write-set! sio ser s))]
    (memory/wrap :set root (canonicalize root))))

(defn eve-list
  "An Eve-backed stand-in whose root is an Eve persistent list
   holding l's elements."
  [l]
  (let [sio (ctx)
        ser (partial eve-mem/value+sio->eve-bytes sio)
        root (eve-list/jvm-sab-list-from-offset sio (eve-list/jvm-write-list! sio ser l))]
    (memory/wrap :list root (canonicalize root))))

(defn eve-atom
  "A new Eve heap atom (mem tier) holding init. The atom itself is
   Eve's reference type -- a wrapper with its own CAS word over an
   immutable root; unit 5 puts it behind the shim's Atom class.
   Here it exists so atom-root-snapshot has real roots to take."
  [init]
  (eve-atom/atom {:id (keyword "cohost.memory" (str "a" (swap! atom-ids inc)))} init))

(defn atom-root-snapshot
  "An Eve-backed stand-in for the current root of Eve atom a: the
   root re-hosted in Eve (collections) or as itself (scalars),
   wrapped with its canonical form. A snapshot: later swaps of a
   do not move it."
  [a]
  (let [root @a]
    (cond
      (vector? root) (eve-vector root)
      (map? root) (eve-map root)
      (set? root) (eve-set root)
      (sequential? root) (eve-list root)
      :else (memory/wrap :value root root))))

;; ---------------------------------------------------------------------------
;; Unit 4 hosting: bare Eve-backed collections behind the shim.
;;
;; The constructors below return BARE Eve roots (not unit 3's
;; stand-ins): Eve's JVM deftypes implement the clojure.lang
;; contracts, so the host's own dispatch serves the values in
;; booted programs, and unit 3's seam contract holds by Eve's own
;; = / hasheq answers (measured in this lane's probes: conj, assoc,
;; nth, get, count, seq, subvec, into, find, merge, IFn, reduce all
;; native). The seam work is what Eve's types do not answer:
;; meta (stubbed in Eve: (meta [_] nil), withMeta returns this),
;; Comparable (absent), Reversible's rseq method (declared, not
;; implemented), vector transients (no IEditableCollection), and
;; the codec's value universe (below).

(declare wrapped? wrap unwrap root-of value?*)

(defn representable?
  "True when v can be written by Eve's JVM codec (eve.mem's
   value->eve-bytes / value+sio->eve-bytes universe): nil,
   booleans, strings, keywords, symbols, Long/Integer/Short/Byte,
   Double/Float, UUID, Date, and collections of representable
   values. Eve values (bare, wrapped, stand-ins) are already in
   Eve. Chars, ratios, BigInt, BigDecimal, BigInteger, records,
   and arbitrary host objects (closures included) are not --
   they are runtime-domain residents whose crossing rules are
   units 5/7 material; constructors reject them with the seam's
   named error rather than storing a lossy form."
  [v]
  (cond
    (nil? v) true
    (instance? Boolean v) true
    (instance? String v) true
    (instance? clojure.lang.Keyword v) true
    (instance? clojure.lang.Symbol v) true
    (instance? Long v) true
    (instance? Integer v) true
    (instance? Short v) true
    (instance? Byte v) true
    (instance? Double v) true
    (instance? Float v) true
    (instance? java.util.UUID v) true
    (instance? java.util.Date v) true
    (instance? eve.vec.EveVector v) true
    (instance? eve.map.EveHashMap v) true
    (instance? eve.set.EveHashSet v) true
    (instance? eve.list.EveList v) true
    (wrapped? v) true
    (memory/eve-value? v) (representable? (:memory/form v))
    (instance? clojure.lang.IRecord v) false
    (map? v) (every? (fn [e] (and (representable? (key e)) (representable? (val e)))) (seq v))
    (coll? v) (every? representable? (seq v))
    (instance? clojure.lang.ISeq v) (every? representable? (seq v))
    :else false))

;; unwrap is defined after the wrapper types (below): a stand-in
;; or a wrapper contributes its Eve root when stored into an Eve
;; collection; any other value itself (host values are
;; flat-encoded by Eve's writer; nested Eve values nest).

(defn- check-all! [coll]
  (run! memory/check-representable! coll))

(defn- analyzer-active?
  "True when the host analyzer is macroexpanding on this
   thread: a clojure.lang.Compiler macroexpand frame is on the
   stack. (Compiler.eval/load frames alone do not count --
   they stay on the stack while evaluated code RUNS, and code
   that runs is program code.) Values built for the analyzer
   are FORMS, and forms are the analyzer's territory -- its
   literal table is the host's own classes, exactly as the
   reader's output is: an Eve map or set sitting in a form as
   a literal is an embed error to it (\"Can't embed object in
   code\"), where an Eve vector or list is analyzed fine. So
   while macroexpansion is in progress the constructors
   answer host values (the form's pieces pass through
   unchanged, as stock's do); at program run time they answer
   Eve-backed values. The check is a bounded scan of the top
   frames: a macro's expansion assembly sits within a few
   frames of the macroexpand frame that invoked the macro."
  []
  (let [frames (.getStackTrace (Thread/currentThread))
        n (min 64 (alength frames))]
    (loop [i 0]
      (cond (>= i n) false
            (and (= "clojure.lang.Compiler" (.getClassName (aget frames i)))
                 (.startsWith (.getMethodName (aget frames i)) "macroexpand")) true
            :else (recur (inc i))))))

(defn- write-list-raw
  "The Eve list of already-converted items."
  [items]
  (let [sio (ctx)
        ser (partial eve-mem/value+sio->eve-bytes sio)]
    (eve-list/jvm-sab-list-from-offset sio (eve-list/jvm-write-list! sio ser (apply list items)))))

(defn- ->eve
  "Deep-convert a value for Eve's writer. Eve-backed values give
   their root; host collections are rebuilt in Eve form with
   converted elements. The conversion a bare seq needs is the
   point: Eve's writer encodes a generic seq (a Cons, a lazy
   seq) as a VECTOR, which would change the value's kind at the
   seam -- a macro expansion is a seq, not a vector, and the
   analyzer knows the difference. A seq therefore becomes an
   Eve list here, at any depth."
  [x]
  (cond (value?* x) (unwrap x)
        (vector? x) (eve-vec/eve-vec (ctx) (map ->eve x))
        (map? x) (let [sio (ctx)]
                   (eve-map/jvm-eve-hash-map-from-offset
                    sio (eve-map/jvm-write-map! sio (into {} (map (fn [e] [(->eve (key e)) (->eve (val e))]) x)))))
        (set? x) (let [sio (ctx)
                       ser (partial eve-mem/value+sio->eve-bytes sio)]
                   (eve-set/jvm-eve-hash-set-from-offset sio (eve-set/jvm-write-set! sio ser (map ->eve x))))
        (seq? x) (write-list-raw (map ->eve x))
        :else x))

(defn build-vector
  "A bare Eve vector holding coll's elements (a host vector
   while the analyzer is active -- see analyzer-active?)."
  [coll]
  (if (analyzer-active?)
    (vec coll)
    (do (check-all! coll)
        (eve-vec/eve-vec (ctx) (map ->eve coll)))))

(defn build-map
  "A bare Eve hash map from a flat alternating key/value seq.
   Duplicate keys: last wins (the pairs are assoc'd in order
   before the write, as stock's create). A host map while the
   analyzer is active."
  [flat-kvs]
  (when (odd? (count flat-kvs))
    (throw (IllegalArgumentException. (str "No value supplied for key: " (last flat-kvs)))))
  (if (analyzer-active?)
    (apply hash-map flat-kvs)
    (let [pairs (partition 2 flat-kvs)
          _ (run! (fn [[k v]] (memory/check-representable! k) (memory/check-representable! v)) pairs)
          m (reduce (fn [m [k v]] (assoc m (->eve k) (->eve v))) {} pairs)
          sio (ctx)]
      (eve-map/jvm-eve-hash-map-from-offset sio (eve-map/jvm-write-map! sio m)))))

(defn build-map-from
  "A bare Eve hash map holding m's entries."
  [m]
  (build-map (mapcat (fn [e] [(key e) (val e)]) (seq m))))

(defn build-set
  "A bare Eve hash set holding coll's elements (a host set
   while the analyzer is active)."
  [coll]
  (if (analyzer-active?)
    (set coll)
    (do (check-all! coll)
        (let [sio (ctx)
              ser (partial eve-mem/value+sio->eve-bytes sio)]
          (eve-set/jvm-eve-hash-set-from-offset sio (eve-set/jvm-write-set! sio ser (map ->eve coll)))))))

(defn build-list
  "A bare Eve list holding coll's elements, in order (a host
   list while the analyzer is active)."
  [coll]
  (if (analyzer-active?)
    (apply list coll)
    (do (check-all! coll)
        (write-list-raw (map ->eve coll)))))

(def empty-vector (eve-vec/eve-vec (ctx) []))
(def empty-map (build-map []))
(def empty-set (build-set #{}))
(def empty-list (build-list ()))

;; ---------------------------------------------------------------------------
;; Meta wrappers. Eve's JVM types stub meta ((meta [_] nil),
;; withMeta returns this), and the plan's rule is that meta rides
;; the value's wrapper, never the block (SS3). A wrapper is minted
;; only when a program attaches meta to an Eve-backed value (the
;; shim's -withMeta branch, through cohost.memory/with-meta*):
;; bare values stay bare. A wrapper holds [root meta] and
;; delegates every collection method to the Eve root, re-wrapping
;; value-producing results with the same meta -- so meta
;; propagates through assoc/conj/pop exactly as stock's does.
;; equals is the root's equiv (the seam contract), hashCode the
;; root's hasheq (stock's collection hashCode is its hasheq),
;; compareTo the seam compare (canonical forms).

(defn- ifn-method-forms
  "The clojure.lang.IFn method forms for a wrapper whose 1-arg
   invoke is invoke1 and whose 2-arg invoke is invoke2 (forms
   with _ as the receiver and k/nf as the argument names): the
   two lookup arities, every other arity throwing ArityException
   as AFn's do, applyTo dispatching on the argument count, and
   the Runnable/Callable/Comparator faces AFn supplies."
  [invoke1 invoke2]
  (let [args (fn [n] (mapv (fn [i] (symbol (str "a" i))) (range n)))]
    (concat
     [(list 'invoke ['_ 'k] invoke1)
      (list 'invoke ['_ 'k 'nf] invoke2)]
     (for [n (concat [0] (range 3 21))]
       (list 'invoke (into ['_] (args n))
             (list 'throw (list 'clojure.lang.ArityException. n "EveBacked"))))
     [;; NOTE: IFn's variadic invoke (Object, Object...) is NOT
      ;; emitted: deftype compiles an unhinted & rest invoke to a
      ;; fixed (Object, Object, Object) method, colliding with
      ;; the fixed 3-arity (measured). applyTo below still routes
      ;; apply; a direct call with 21+ arguments is the only
      ;; casualty.
      (list 'applyTo ['this 'args]
            (list 'case (list 'count 'args)
                  0 (list 'throw (list 'clojure.lang.ArityException. 0 "EveBacked"))
                  1 (list '.invoke 'this (list 'nth 'args 0))
                  2 (list '.invoke 'this (list 'nth 'args 0) (list 'nth 'args 1))
                  (list 'throw (list 'clojure.lang.ArityException. (list 'count 'args) "EveBacked"))))
      (list 'run ['_] (list 'throw (list 'clojure.lang.ArityException. 0 "EveBacked")))
      (list 'call ['_] (list 'throw (list 'clojure.lang.ArityException. 0 "EveBacked")))
      ;; clojure.lang.IFn no longer extends Comparator (1.12);
      ;; AFn lists it beside IFn, and so does this expansion.
      'java.util.Comparator
      (list 'compare ['this 'a 'b] (list '.invoke 'this 'a 'b))])))

(defmacro ^:private defwrapper
  "Emit the meta-wrapper deftype for one collection kind: the
   common delegation (count, seq, empty, equiv, hasheq, meta,
   withMeta, reduce, equals, hashCode, toString) is generated
   here; the kind's own interfaces are given as extra forms.
   opts: :ifn {:one form :two form} -- also emit IFn over the
   two lookup forms; :seq form -- the Seqable seq body (default:
   the root's seq)."
  [tname kind {:keys [ifn seq]} & extra]
  (let [unq (fn [f] (if (and (seq? f) (= 'quote (first f))) (second f) f))
        ctor-form (list (symbol (str tname ".")) 'root 'meta)]
    `(deftype ~tname [~'root ~'meta]
       ~@extra
       clojure.lang.Counted
       (~'count [~'_] (.count ^clojure.lang.Counted ~'root))
       clojure.lang.Seqable
       (~'seq [~'this] ~(or (unq seq) (list '.seq (with-meta 'root {:tag 'clojure.lang.Seqable}))))
       clojure.lang.IPersistentCollection
       (~'empty [~'_] (.empty ^clojure.lang.IPersistentCollection ~'root))
       (~'equiv [~'_ ~'o] (.equiv ^clojure.lang.IPersistentCollection ~'root ~'o))
       clojure.lang.IHashEq
       (~'hasheq [~'_] (.hasheq ^clojure.lang.IHashEq ~'root))
       clojure.lang.IMeta
       (~'meta [~'_] ~'meta)
       clojure.lang.IObj
       (~'withMeta [~'_ ~'m] (if (nil? ~'m) ~'root ~ctor-form))
       clojure.lang.IReduceInit
       (~'reduce [~'_ ~'f ~'init] (.reduce ^clojure.lang.IReduceInit ~'root ~'f ~'init))
       ~@(when ifn (cons 'clojure.lang.IFn (ifn-method-forms (unq (:one ifn)) (unq (:two ifn)))))
       java.lang.Object
       (~'equals [~'this ~'o] (or (identical? ~'this ~'o) (and (some? ~'o) (.equiv ^clojure.lang.IPersistentCollection ~'root ~'o))))
       (~'hashCode [~'_] (.hasheq ^clojure.lang.IHashEq ~'root))
       (~'toString [~'_] (str (canonicalize ~'root))))))

(defwrapper EveBackedVector :vector
  {:ifn {:one '(.nth ^clojure.lang.Indexed root (int (long k)))
         :two '(.valAt ^clojure.lang.ILookup root k nf)}}
  clojure.lang.IPersistentVector
  (cons [_ x] (EveBackedVector. (.cons ^clojure.lang.IPersistentVector root x) meta))
  (assocN [_ i v] (EveBackedVector. (.assocN ^clojure.lang.IPersistentVector root i v) meta))
  (length [_] (.length ^clojure.lang.IPersistentVector root))
  clojure.lang.Associative
  (assoc [_ k v] (EveBackedVector. (.assoc ^clojure.lang.Associative root k v) meta))
  (entryAt [_ k] (.entryAt ^clojure.lang.Associative root k))
  (containsKey [_ k] (.containsKey ^clojure.lang.Associative root k))
  clojure.lang.ILookup
  (valAt [_ k] (.valAt ^clojure.lang.ILookup root k))
  (valAt [_ k nf] (.valAt ^clojure.lang.ILookup root k nf))
  clojure.lang.Indexed
  (nth [_ i] (.nth ^clojure.lang.Indexed root i))
  (nth [_ i nf] (.nth ^clojure.lang.Indexed root i nf))
  clojure.lang.IPersistentStack
  (peek [_] (.peek ^clojure.lang.IPersistentStack root))
  (pop [_] (EveBackedVector. (.pop ^clojure.lang.IPersistentStack root) meta))
  clojure.lang.Reversible
  (rseq [_] (reverse (seq root)))
  clojure.lang.IEditableCollection
  (asTransient [this] (memory/as-transient this))
  java.lang.Comparable
  (compareTo [this o] (memory/compare-values this o))
  java.lang.Iterable
  (iterator [_] (.iterator ^java.lang.Iterable root))
  java.util.List
  (size [_] (.count ^clojure.lang.Counted root))
  (isEmpty [_] (zero? (.count ^clojure.lang.Counted root)))
  (contains [_ o] (.contains ^java.util.List root o))
  (containsAll [_ c] (.containsAll ^java.util.List root c))
  (get [_ i] (.nth ^clojure.lang.Indexed root i))
  (indexOf [_ o] (.indexOf ^java.util.List root o))
  (lastIndexOf [_ o] (.lastIndexOf ^java.util.List root o))
  (subList [_ a b] (let [r (.subList ^java.util.List root a b)]
                     (if (instance? eve.vec.EveVector r) (EveBackedVector. r meta) r)))
  (toArray [_] (.toArray ^java.util.List root))
  (^objects toArray [_ ^objects a] (.toArray ^java.util.List root a))
  (listIterator [_] (.listIterator ^java.util.List root))
  (listIterator [_ i] (.listIterator ^java.util.List root i))
  (add [_ o] (throw (UnsupportedOperationException.)))
  (add [_ i o] (throw (UnsupportedOperationException.)))
  (addAll [_ c] (throw (UnsupportedOperationException.)))
  (addAll [_ i c] (throw (UnsupportedOperationException.)))
  (^boolean remove [_ ^Object o] (throw (UnsupportedOperationException.)))
  (remove [_ ^int i] (throw (UnsupportedOperationException.)))
  (removeAll [_ c] (throw (UnsupportedOperationException.)))
  (retainAll [_ c] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  (set [_ i o] (throw (UnsupportedOperationException.))))

(defwrapper EveBackedMap :map
  {:ifn {:one '(.valAt ^clojure.lang.ILookup root k)
         :two '(.valAt ^clojure.lang.ILookup root k nf)}}
  clojure.lang.IPersistentMap
  (assoc [_ k v] (EveBackedMap. (.assoc ^clojure.lang.IPersistentMap root k v) meta))
  (assocEx [_ k v] (.assocEx ^clojure.lang.IPersistentMap root k v))
  (without [_ k] (EveBackedMap. (.without ^clojure.lang.IPersistentMap root k) meta))
  (iterator [_] (.iterator ^clojure.lang.IPersistentMap root))
  clojure.lang.Associative
  (entryAt [_ k] (.entryAt ^clojure.lang.Associative root k))
  (containsKey [_ k] (.containsKey ^clojure.lang.Associative root k))
  clojure.lang.ILookup
  (valAt [_ k] (.valAt ^clojure.lang.ILookup root k))
  (valAt [_ k nf] (.valAt ^clojure.lang.ILookup root k nf))
  clojure.lang.IPersistentCollection
  (cons [_ x] (EveBackedMap. (.cons ^clojure.lang.IPersistentCollection root x) meta))
  clojure.lang.IKVReduce
  (kvreduce [_ f init] (.kvreduce ^clojure.lang.IKVReduce root f init))
  clojure.lang.IEditableCollection
  (asTransient [this] (memory/as-transient this))
  java.lang.Comparable
  (compareTo [this o] (memory/compare-values this o)))

(defwrapper EveBackedSet :set
  {:ifn {:one '(.get ^clojure.lang.IPersistentSet root k)
         :two '(let [v (.get ^clojure.lang.IPersistentSet root k)] (if (some? v) v nf))}}
  clojure.lang.IPersistentSet
  (disjoin [_ k] (EveBackedSet. (.disjoin ^clojure.lang.IPersistentSet root k) meta))
  (contains [_ k] (.contains ^clojure.lang.IPersistentSet root k))
  (get [_ k] (.get ^clojure.lang.IPersistentSet root k))
  clojure.lang.ILookup
  (valAt [_ k] (.get ^clojure.lang.IPersistentSet root k))
  (valAt [_ k nf] (let [v (.get ^clojure.lang.IPersistentSet root k)] (if (some? v) v nf)))
  clojure.lang.IPersistentCollection
  (cons [_ x] (EveBackedSet. (.cons ^clojure.lang.IPersistentCollection root x) meta))
  clojure.lang.IEditableCollection
  (asTransient [this] (memory/as-transient this))
  java.lang.Comparable
  (compareTo [this o] (memory/compare-values this o)))

(defwrapper EveBackedList :list
  {:seq '(when (pos? (.count ^clojure.lang.Counted root)) this)}
  clojure.lang.ISeq
  (first [_] (.first ^clojure.lang.ISeq root))
  (next [_] (let [r (.next ^clojure.lang.ISeq root)]
              (if (instance? eve.list.EveList r) (EveBackedList. r meta) r)))
  (more [_] (let [r (.more ^clojure.lang.ISeq root)]
              (if (instance? eve.list.EveList r) (EveBackedList. r meta) r)))
  (cons [_ x] (EveBackedList. (.cons ^clojure.lang.ISeq root x) meta))
  clojure.lang.IPersistentStack
  (peek [_] (.peek ^clojure.lang.IPersistentStack root))
  (pop [_] (let [r (.pop ^clojure.lang.IPersistentStack root)]
             (if (instance? eve.list.EveList r) (EveBackedList. r meta) r)))
  java.lang.Iterable
  (iterator [_] (.iterator ^java.lang.Iterable root)))

(defn wrapped?
  "True when x is a meta wrapper over an Eve root."
  [x]
  (or (instance? EveBackedVector x)
      (instance? EveBackedMap x)
      (instance? EveBackedSet x)
      (instance? EveBackedList x)))

(defn wrap
  "The wrapper of kind holding root and meta m."
  [root kind m]
  (case kind
    :vector (EveBackedVector. root m)
    :map (EveBackedMap. root m)
    :set (EveBackedSet. root m)
    :list (EveBackedList. root m)))

(defn root-of
  "The bare Eve root of a seam value (bare or wrapped)."
  [x]
  (cond (instance? EveBackedVector x) (.-root ^EveBackedVector x)
        (instance? EveBackedMap x) (.-root ^EveBackedMap x)
        (instance? EveBackedSet x) (.-root ^EveBackedSet x)
        (instance? EveBackedList x) (.-root ^EveBackedList x)
        :else x))

(defn kind-of
  "The collection kind of a seam value (bare or wrapped)."
  [x]
  (cond (or (instance? eve.vec.EveVector x) (instance? EveBackedVector x)) :vector
        (or (instance? eve.map.EveHashMap x) (instance? EveBackedMap x)) :map
        (or (instance? eve.set.EveHashSet x) (instance? EveBackedSet x)) :set
        (or (instance? eve.list.EveList x) (instance? EveBackedList x)) :list))

(defn value?*
  "True when x is an Eve-backed collection value of this layer:
   a bare Eve collection or its meta wrapper."
  [x]
  (or (instance? eve.vec.EveVector x)
      (instance? eve.map.EveHashMap x)
      (instance? eve.set.EveHashSet x)
      (instance? eve.list.EveList x)
      (wrapped? x)))

(defn unwrap
  "The Eve root a seam value contributes when stored into an Eve
   collection: a stand-in's root, a wrapper's root, anything else
   itself (host values are flat-encoded by Eve's writer; nested
   Eve values nest as Eve values)."
  [v]
  (cond (memory/eve-value? v) (:memory/root v)
        (wrapped? v) (root-of v)
        :else v))

(defn canonical-form-of
  "x's canonical Clojure-level form: a wrapper canonicalizes its
   root; anything else goes through canonicalize (which rebuilds
   host collections, canonicalizing any Eve values inside)."
  [x]
  (if (wrapped? x) (canonicalize (root-of x)) (canonicalize x)))

(defn with-meta*
  "x carrying meta m: nil meta unwraps to the bare root; a
   wrapper is re-wrapped over its root; a bare root is wrapped."
  [x m]
  (cond (nil? m) (root-of x)
        (wrapped? x) (wrap (root-of x) (kind-of x) m)
        :else (wrap x (kind-of x) m)))

(defn install-fill!
  "Seat-specific fill install: the shim's EMPTY statics for the
   filled classes answer Eve empties while the fill is in force
   (core.clj reads PersistentArrayMap/EMPTY directly). Resolved
   lazily so this namespace never requires the shim."
  []
  (doseq [[sym v] {'PersistentVector-EMPTY empty-vector
                   'PersistentArrayMap-EMPTY empty-map}]
    (when-let [var (try (ns-resolve 'cohost.shim sym) (catch Throwable _ nil))]
      (alter-var-root var (constantly v))))
  true)

(memory/register-hosting!
 {:value? value?*
  :kind kind-of
  :root-of root-of
  :constructors {:vector build-vector
                 :map build-map
                 :map-from build-map-from
                 :set build-set
                 :list build-list
                 :empty-vector empty-vector
                 :empty-map empty-map
                 :empty-set empty-set
                 :empty-list empty-list}
  :representable? representable?
  :canonical-form canonical-form-of
  :with-meta with-meta*
  ;; the ops convert what they store and what they probe with,
  ;; like the constructors: a stored seq must land as an Eve
  ;; list (see ->eve), and a seq probe key must be encoded as
  ;; the list it was stored as, or the lookup misses
  :ops {:conj (fn [c x] (conj c (->eve x)))
        :assoc (fn [c k v] (assoc c (->eve k) (->eve v)))
        :dissoc (fn [c k] (dissoc c (->eve k)))
        :disj (fn [c k] (disj c (->eve k)))
        :pop (fn [c] (pop c))
        :peek (fn [c] (peek c))
        :nth (fn [c i nf] (nth c i nf))
        :count (fn [c] (count c))
        :get (fn [c k nf] (get c (->eve k) nf))
        :contains (fn [c k] (contains? c (->eve k)))
        :find (fn [c k] (find c (->eve k)))}
  :install-fill install-fill!})
