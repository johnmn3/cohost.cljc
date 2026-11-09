(ns cohost.hosts.bb
  "Babashka as a host: the JVM adapter, interpreted. hosts/java.clj and
   hosts/jvm.clj are loaded from their own source with the few class
   references SCI cannot resolve edited out (each edit must match exactly
   once, so a change upstream fails loudly here), and the entries whose
   JVM interop does not reach SCI's vars and namespaces are overridden."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cohost.adapter :as adapter]
            [cohost.memory]
            [clojure.edn]))

(defn- patched [path edits]
  (reduce (fn [src [from to]]
            (let [i (string/index-of src from)]
              (when-not (and i (= i (string/last-index-of src from)))
                (throw (ex-info (str "cohost.hosts.bb: edit does not match once in " path) {:from from})))
              (string/replace src from to)))
          (slurp (io/resource path))
          edits))

(defn- line-of [src needle]
  ;; the whole source line holding needle, with its newline
  (let [i (string/index-of src needle)
        a (inc (or (string/last-index-of src "\n" i) -1))
        b (string/index-of src "\n" i)]
    (subs src a (inc b))))

;; a class bb does not carry, matched by name on the class and its supers
(defn instance-named? [cname x]
  (and (some? x) (boolean (some #(= cname (.getName ^Class %)) (cons (class x) (supers (class x)))))))

(let [path "cohost/hosts/java.clj"
      src (slurp (io/resource path))
      drop-line (fn [needle] [(line-of src needle) ""])]
  (load-string
   (patched path
            [;; exception ctors for classes bb lacks: the shim's stand-in serves
             (drop-line "'java.lang.ArrayIndexOutOfBoundsException [")
             (drop-line "'java.lang.StringIndexOutOfBoundsException [")
             (drop-line "'java.util.ConcurrentModificationException [")
             ["(instance? StringIndexOutOfBoundsException e)" "(cohost.hosts.bb/instance-named? \"java.lang.StringIndexOutOfBoundsException\" e)"]
             ["(instance? ArrayIndexOutOfBoundsException e)" "(cohost.hosts.bb/instance-named? \"java.lang.ArrayIndexOutOfBoundsException\" e)"]
             ["(instance? IllegalAccessError e)" "(cohost.hosts.bb/instance-named? \"java.lang.IllegalAccessError\" e)"]
             ["(instance? java.util.IllegalFormatException e)" "(cohost.hosts.bb/instance-named? \"java.util.IllegalFormatException\" e)"]])))

(let [path "cohost/hosts/jvm.clj"]
  (load-string
   (patched path
            [["(instance? clojure.lang.IChunk x)" "(cohost.hosts.bb/instance-named? \"clojure.lang.IChunk\" x)"]
             ;; IChunk.reduce is not reachable: walk the chunk by index
             ["(.reduce ^clojure.lang.IChunk c f init)"
              "(loop [i 0 acc init] (if (< i (count c)) (let [acc (f acc (nth c i))] (if (reduced? acc) acc (recur (inc i) acc))) acc))"]
             ;; no Reflector: the entries that need it are dropped below
             ["(fn [& args] (clojure.lang.Reflector/invokeConstructor k (to-array args)))" "nil"]
             ["(fn [& args] (clojure.lang.Reflector/invokeStaticMethod k (str m) (to-array args)))" "nil"]
             ["(clojure.lang.Reflector/invokeInstanceMethod x nm (object-array args))" "absent"]
             ["(clojure.lang.Reflector/getInstanceField x nm)" "absent"]
             ["(clojure.lang.Reflector/invokeNoArgInstanceMember x nm false)" "absent"]
             ["(adapter/install! (merge java/capabilities adapter))" ""]])))

(defn- jvm-syntax-quote
  "bb's reader expands syntax-quote as edamame does: (clojure.core/sequence
   (seq (concat ...))) for a list and (apply array-map ...) for a map, where
   the JVM's LispReader writes (seq (concat ...)) and (apply hash-map ...).
   Booting core.clj, those names are not yet defined where the first stock
   macros expand: rewrite the expansion to the JVM's shape, keeping metadata."
  [form]
  (let [walk (fn walk [f]
               (let [m (meta f)
                     g (cond (and (seq? f) (= 'clojure.core/sequence (first f)) (= 2 (count f))
                                  (seq? (second f)) (= 'clojure.core/seq (first (second f))))
                             (walk (second f))
                             (and (seq? f) (= 3 (count f)) (= 'clojure.core/apply (first f))
                                  (= 'clojure.core/array-map (second f)))
                             (list 'clojure.core/apply 'clojure.core/hash-map (walk (nth f 2)))
                             (seq? f) (apply list (map walk f))
                             (map-entry? f) f
                             (vector? f) (mapv walk f)
                             (map? f) (into (empty f) (map (fn [[k v]] [(walk k) (walk v)])) f)
                             (set? f) (into (empty f) (map walk) f)
                             :else f)]
                 (if (and m (not (identical? f g)) (instance? clojure.lang.IObj g))
                   (with-meta g (merge (meta g) m))
                   g)))]
    (walk form)))

(def adapter
  "SCI's vars and namespaces are sci.lang's, not clojure.lang's: these
   entries reach them through clojure.core instead of the JVM's methods."
  {:read-forms (fn [src cb]
                 (let [r (java.io.PushbackReader. (java.io.StringReader. src))]
                   (loop []
                     (let [f (read {:eof ::eof :read-cond :allow} r)]
                       (when-not (= f ::eof) (cb (jvm-syntax-quote f)) (recur))))))
   :resolve-core (fn [sym] (get (ns-publics 'clojure.core) sym))
   :interned-var (fn [ns-sym sym] (when-let [n (find-ns ns-sym)] (get (ns-interns n) sym)))
   :macro-var? (fn [v] (boolean (:macro (meta v))))
   :mark-macro! (fn [v] (alter-meta! v assoc :macro true) v)
   :var-raw-root (fn [v] (var-get v))
   :set-dynamic! (fn [v] (alter-meta! v assoc :dynamic true) v)
   :dynamic-var? (fn [v] (boolean (:dynamic (meta v))))
   :namespace? (fn [x] (instance? sci.lang.Namespace x))
   :var-set! (fn [v val] (var-set v val))
   :thread-bindings (fn [] (get-thread-bindings))
   :native-ctor (fn [_] nil)
   :native-static (fn [_ _] nil)})

(adapter/install! (merge @(resolve 'cohost.hosts.java/capabilities)
                         @(resolve 'cohost.hosts.jvm/adapter)
                         adapter))
