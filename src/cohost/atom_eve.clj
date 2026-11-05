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

(defn install!
  "Register this seat's Eve factory with the portable layer.
   Returns true."
  []
  (atom-layer/install-factory! {:make-atom make-eve-atom
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
