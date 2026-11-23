(ns cohost.eve-bridge
    "The bridge that makes a cohost user type an Eve type.

   A body adapter (on the jank :cohost-types body, the adapter's own
   deftype/defrecord macros; elsewhere, an explicit call at the type's
   definition site) registers each user type here at definition time.
   Registration does exactly what eve.deftype/eve-deftype's expansion
   does at load, keyed by the same identity Eve itself uses — the
   type's NAME, never a host class:

     type-key   \"my.ns/Widget\" (the cohost dotted name, last dot -> /)
     type-id    0x40 + (eve's hash of the type-key) mod 173
     name-hash  the same hash & 0xFFFFFF     (eve.deftype.registry)
     fingerprint FNV-1a over [type-key [[field class hint]...]]
                (eve.flat.sister/layout-fingerprint)

   Every field is the :serialized class (stock deftype fields carry no
   hints): a 4-byte slot in the type's block holding the offset of a
   [len][EVE bytes] value block — eve.deftype.slab-runtime's own field
   machinery writes and reads them. A record gets one extra slot for
   its extmap, so assoc'd entries ride too.

   Storage: one serialize BUILDER claims bridged instances and writes
   the type's block in the atom's domain; one deftype CONSTRUCTOR per
   type reads a block back into the body's own instance (through the
   :factory the registering body supplied). A second body that defined
   the same-named type with the same layout registers the same
   (type-id, name-hash) and its own constructor — the block written by
   the first body reads as the second body's own instance.

   The digest/image half: an instance's canonical image is
   (eve.codec/value->eve-bytes (plain-view v)) where the plain view is
   [:cohost/type type-key [field-values...]] — on hosts whose codec has
   the IPlain branch (the byte-array codec: JVM, bb, cljs) that is
   literally what imaging the value computes once the type's IPlain
   view is registered; the view is the bridge's portable definition."
    (:require [clojure.string :as str]
              [eve.codec :as codec]
              [eve.deftype.registry :as reg]
              [eve.deftype-proto.serialize :as ser]
              [eve.deftype-proto.data :as d]
              [eve.deftype.slab-runtime :as srt]
              [eve.slab-io :as slab]
              [eve.flat.sister :as sister]
              [eve.type-readers :as tr]))

;; type-key -> {:name :kind :fields :slab-fields :total-size :type-id
;;              :name-hash :factory :pred :fields-of :extmap-of}
(defonce types (atom {}))
(defonce ^:private builder-installed? (atom false))

(defn dotted->type-key
      "A cohost type's dotted name symbol (my.ns.Widget) as an Eve type key
   (\"my.ns/Widget\")."
      [dotted]
      (let [s (str dotted) i (str/last-index-of s ".")]
           (str (subs s 0 i) "/" (subs s (inc i)))))

(defn- fingerprint
       "The layout fingerprint: eve.flat.sister/layout-fingerprint itself —
   FNV-1a over the pr-str of [type-key [[field-name class hint]...]].
   Eve's fn is the identity's definition, so the bridge calls it
   rather than folding its own copy: the fingerprint recorded here
   and the one in the sister descriptor are the same computation and
   cannot drift. (The spike that proved this bridge carried a local
   byte fold because Eve's fn then had no :lpy arm — its :default
   (int (nth s i)) parsed a one-char string as a decimal on basilisp
   and threw. Eve's fn has since grown the portable char-code arms
   (:clje byte-list, :cljs charCodeAt, :lpy python/ord); the spike's
   fold and Eve's fn were proven bit-identical on the JVM before the
   fold was retired.)"
       [type-key fields]
       (sister/layout-fingerprint type-key fields))

(defn- slot-fields
       "The layout's fields: the declared fields, plus — for a record — one
   trailing :serialized slot for the extmap."
       [kind fields]
       (if (= kind :record) (conj (vec fields) '__extmap) (vec fields)))

(defn- write-root
       "The builder's body: v as a fresh block of its type in sio, presented
   as an IEveRoot over the block."
       [sio info v]
       (let [blk (slab/alloc! sio (:total-size info))
             vals (vec ((:fields-of info) v))
             vals (if (= :record (:kind info)) (conj vals ((:extmap-of info) v)) vals)]
            (slab/write-u8! sio blk 0 (:type-id info))
            (slab/write-u8! sio blk 1 (bit-and (:name-hash info) 0xFF))
            (slab/write-u8! sio blk 2 (bit-and (bit-shift-right (:name-hash info) 8) 0xFF))
            (slab/write-u8! sio blk 3 (bit-and (bit-shift-right (:name-hash info) 16) 0xFF))
            (doseq [[f val] (map vector (:slab-fields info) vals)]
                   (srt/slab-write-serialized! sio blk (:offset f) val))
            (reify d/IEveRoot (-root-header-off [_] blk))))

(defn- install-builder! []
       (when-not @builder-installed?
                 (reset! builder-installed? true)
                 (ser/register-builder!
                  (fn [v] (boolean (and (some? v)
                                        (some (fn [[_ info]] ((:pred info) v)) @types))))
                  (fn [sio v]
                      (write-root sio (some (fn [[_ info]] (when ((:pred info) v) info)) @types) v)))))

(defonce ^:private map-door-installed? (atom false))

(defn- info-of-record-instance [v]
       (some (fn [[_ info]] (when (and (= :record (:kind info)) ((:pred info) v)) info)) @types))

(defn- install-map-door!
       "The record door. A defrecord IS a map, and on hosts where a direct
   :map coll-writer is registered (the JVM codec's HAMT writer),
   eve.atom/write-plain-collection! claims a record with that writer
   BEFORE the builder loop runs — it would be stored as a plain map
   and read back as one, type lost (measured: the spike's Gadget came
   back a PersistentArrayMap). Wrap the :map writer: a bridged record
   writes its type block; every other map delegates to the registered
   writer untouched. On hosts with no :map writer (jank), the builder
   loop already claims records and no wrap is installed."
       []
       (when-not @map-door-installed?
                 (reset! map-door-installed? true)
                 (when-let [orig (ser/get-coll-writer :map)]
                           (ser/register-coll-writer! :map
                                                      (fn [sio serialize-elem v]
                                                          (if-let [info (info-of-record-instance v)]
                                                                  (d/-root-header-off (write-root sio info v))
                                                                  (orig sio serialize-elem v)))))))

(defn register-type!
      "Register the cohost type `name` (a dotted symbol) with Eve.
   spec: {:name :kind :deftype|:record :fields [sym...]
          :factory   (fn [field-values extmap] -> this body's instance)
          :pred      (fn [v] -> true when v is an instance of this type)
          :fields-of (fn [instance] -> its declared field values, in order)
          :extmap-of (fn [record-instance] -> its extmap or nil)}
   Answers the registration info (with :type-id :name-hash :fingerprint)."
      [{:keys [name kind fields factory pred fields-of extmap-of]}]
      (let [type-key (dotted->type-key name)
            layout (reg/compute-layout (mapv reg/parse-field (slot-fields kind fields)))
            slab-fields (vec (:fields layout))
            total-size (:total-size layout)
            [tid nh] (reg/claim-type-id! type-key)
            sister-fields (mapv (fn [f] {:name (:name f) :keyword (keyword (:name f))
                                         :type-class (:type-class f)
                                         :type-hint (some-> (:type-hint f) clojure.core/name)
                                         :mutability (:mutability f) :offset (:offset f)})
                                slab-fields)
            info {:name name :type-key type-key :kind kind :fields (vec fields) :slab-fields slab-fields
                  :total-size total-size :type-id tid :name-hash nh
                  :fingerprint (fingerprint type-key sister-fields)
                  :factory factory :pred pred :fields-of fields-of
                  :extmap-of (or extmap-of (fn [_] nil))}]
           (ser/register-deftype-layout! tid nh
                                         (mapv (fn [f] [(:offset f) :value]) slab-fields) total-size)
           (ser/register-type-refs! tid nh
                                    (mapv (fn [f] [(:offset f) :vval]) slab-fields))
           (reg/register-type! (str name) {:type-key type-key :storage :cohost
                                           :record/portable? true})
    ;; The sister-descriptor registration: register-sister! computes
    ;; Eve's layout-fingerprint internally. That fn is portable at
    ;; the current Eve pin (see fingerprint above), so the
    ;; registration runs on every host, :lpy included.
           (sister/register-sister! type-key sister-fields nil)
           (ser/register-deftype-constructor! tid nh
                                              (fn [sio header-off]
                                                  (let [vals (mapv (fn [f] (srt/slab-read-serialized sio header-off (:offset f)))
                                                                   slab-fields)]
                                                       (if (= :record kind)
                                                           (factory (pop vals) (peek vals))
                                                           (factory vals nil)))))
           (tr/register! type-key
                         (let [i (str/index-of type-key "/")]
                              (symbol (subs type-key 0 i) (subs type-key (inc i))))
                         (fn [data] (factory (mapv (fn [f] (get data (keyword (clojure.core/name f)))) fields)
                                             (when (= :record kind)
                                                   (into {} (remove (fn [[k _]] (contains? (set (map (fn [f] (keyword (clojure.core/name f))) fields)) k)) data)))))
                         {:mode :field-map})
           (swap! types assoc type-key info)
           (install-builder!)
           (when (= :record kind) (install-map-door!))
           info))

(defn info-of
      "The registration info of the type key, or nil."
      [type-key]
      (get @types type-key))

(defn info-of-instance
      "The registration info of v's type, when v is a bridged instance."
      [v]
      (some (fn [[_ info]] (when ((:pred info) v) info)) @types))

(defn plain-view
      "v's canonical plain view: [:cohost/type type-key [field values...]],
   a record's extmap riding as the vector's last element, exactly as in
   the type's block."
      [v]
      (when-let [info (info-of-instance v)]
                (let [vals (vec ((:fields-of info) v))
                      vals (if (= :record (:kind info)) (conj vals ((:extmap-of info) v)) vals)]
                     [:cohost/type (:type-key info) vals])))

(defn image-bytes
      "The canonical EVE bytes of a bridged instance: the flat image of its
   plain view. Byte-identical in every body that registers the type."
      [v]
      (codec/value->eve-bytes (plain-view v)))
