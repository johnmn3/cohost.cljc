(ns cohost.atom-eve
  "Eve-in-cohost, unit 5: the JVM seat's Eve hosting for
   cohost.atom (the cohost.memory-eve pattern: a .clj file
   nothing in the shim/boot graph requires, so jank and CLJS
   builds of cohost never load Eve's JVM namespaces; the boot
   fill loads it explicitly on this seat).

   Program atoms live in Eve heap domains, sharded: a domain
   holds at most 256 atom slots (eve.deftype-proto.data/
   MAX_ATOM_SLOTS, slot 0 is the domain's own registry), so
   the hosting mints a fresh domain when the current one
   fills. The registry atom lives in its own domain -- the
   STARTUP contention domain of plan SS5/SS10: minted once at
   install, holding the namespace table while the runtime
   domains hold program atoms."
  (:require [cohost.atom :as atom-layer]
            [eve.atom :as eve-atom]
            [eve.deftype-proto.data :as eve-data]))

(defonce ^:private hosting
  (atom {:domains []        ;; every program domain minted
         :current nil       ;; the program domain taking atoms
         :count 0           ;; atoms in :current
         :counter 0         ;; atom names, unique across domains
         :startup nil       ;; the startup domain
         :registry nil}))   ;; the registry Eve atom

(def ^:private slots-per-domain 255)

(defn- mint-program-domain! []
  (let [d (eve-atom/atom-domain {})]
    (swap! hosting (fn [s] (-> s
                               (update :domains conj d)
                               (assoc :current d :count 0))))
    d))

(defn- make-eve-atom [slab-init]
  (locking hosting
    (let [s @hosting
          _ (when (or (nil? (:current s))
                      (>= (:count s) slots-per-domain))
              (mint-program-domain!))
          s @hosting
          n (inc (:counter s))]
      (swap! hosting assoc :counter n :count (inc (:count s)))
      (eve-atom/lookup-or-create-mmap-atom! (:current s)
                                            (str "cohost.atom/a" n)
                                            slab-init))))

(defn- registry-eve-atom []
  (locking hosting
    (or (:registry @hosting)
        (let [dom (eve-atom/atom-domain {})
              ra (eve-atom/lookup-or-create-mmap-atom!
                  dom "cohost.registry/table" {})]
          (swap! hosting assoc :startup dom :registry ra)
          ra))))

;; ----------------------------------------------------------------
;; Unit 7: the shared tiers on the JVM seat.
;;
;; :disk -- Eve's file-backed (mmap) domain at :eve/path, the atom
;; registered there as :eve/name. eve.atom/persistent-atom-domain
;; caches by path and joins when the domain's files exist (its
;; exclusive create of <path>.root picks the one creating process:
;; OS-resolved, no coordinator); lookup-or-create-mmap-atom! answers
;; the existing atom of that name, else creates it holding init.
;;
;; :net -- Eve's network atom (eve.net.atom/remote-atom) at
;; :eve/endpoint, domain :eve/domain-id, atom :eve/name (written
;; through eve.net.gateway/atom-id). Its handle is a map with its own
;; deref/swap!/reset! fns, so the hosting adapts it to the IDeref/
;; IAtom shape the layer calls -- the operations are Eve's, verbatim.
;; Eve's own rules apply as-is: a net atom's value is a map
;; (:not-a-map otherwise), every swap a broker CAS. The disk tier a
;; network atom contains is the gateway member's disk domain (Eve's
;; model); the client handle's block cache is in memory -- named in
;; the unit report, not filled here.

(defn- disk-handle [m init]
  (let [d (eve-atom/persistent-atom-domain (:eve/path m))]
    (eve-atom/lookup-or-create-mmap-atom! d (:eve/name m) init)))

(defn- net-handle [m init]
  (let [na-remote (requiring-resolve 'eve.net.atom/remote-atom)
        na-deref (requiring-resolve 'eve.net.atom/deref)
        na-swap (requiring-resolve 'eve.net.atom/swap!)
        atom-id (requiring-resolve 'eve.net.gateway/atom-id)
        cfg (cond-> {:endpoint (:eve/endpoint m)
                     :domain-id (:eve/domain-id m)
                     :atom-id (atom-id (:eve/name m))
                     :api-key (:eve/api-key m)}
              (:eve/gak m) (assoc :gak (:eve/gak m)))
        ra (if-let [p (:eve/platform m)] (na-remote cfg p) (na-remote cfg))
        sw (fn [f] (na-swap ra f))]
    ;; a fresh net atom takes init as its first value; an existing
    ;; one keeps its own (the broker's root is the bottom tier's)
    (let [cur (na-deref ra)]
      (when (and (nil? cur) (some? init))
        (sw (fn [c] (if (nil? c) init c)))))
    (reify
      clojure.lang.IDeref
      (deref [_] (na-deref ra))
      clojure.lang.IAtom
      (swap [_ f] (sw f))
      (swap [_ f x] (sw #(f % x)))
      (swap [_ f x y] (sw #(f % x y)))
      (swap [_ f x y more] (sw #(apply f % x y more)))
      (reset [_ v] (sw (constantly v)))
      (compareAndSet [_ o n]
        (let [hit (volatile! false)]
          (sw (fn [c] (if (= c o) (do (vreset! hit true) n) (do (vreset! hit false) c))))
          @hit)))))

(defn- make-tier-atom [kind m init]
  (case kind
    :disk (disk-handle m init)
    :net (net-handle m init)))

(defn close-disk-domain!
  "Release this process's slot in the disk domain at path (probes:
   a process that ends otherwise releases by heartbeat staleness)."
  [path]
  (when-let [d (get @@#'eve-atom/domain-cache path)]
    (eve-atom/close-atom-domain! d)))

(defn root-digests
  "The two digests of a disk atom's current root, as Eve computes
   them: :seal -- eve.atom/seal, the stored version (root pointer,
   name hash, block count, FNV-1a over every block the value owns,
   offset and bytes); :sha256 -- Eve's content digest (SHA-256 over
   the root's canonical codec bytes, eve.digest's Merkle identity),
   as 8 i32 words. The layer atom's Eve handle is read directly."
  [a]
  (let [h (atom-layer/eve-handle a)
        compute (requiring-resolve 'eve.digest/compute-digest)]
    {:seal (eve-atom/seal h)
     :sha256 (@compute @h)}))

(defn install!
  "Register this seat's Eve factory with the portable layer.
   Returns true."
  []
  (atom-layer/install-factory! {:make-atom make-eve-atom
                                :make-tier-atom make-tier-atom
                                :registry-atom registry-eve-atom
                                :eve-value? (fn [v]
                                              (satisfies? eve-data/IEveRoot v))})
  true)

(defn hosting-state
  "Domain counts for probes and the report."
  []
  (let [s @hosting]
    {:program-domains (count (:domains s))
     :atoms-minted (:counter s)
     :startup-domain? (some? (:startup s))}))
