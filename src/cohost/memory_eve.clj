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
      (let [c (alloc/make-jvm-heap-slab-ctx {})]
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
