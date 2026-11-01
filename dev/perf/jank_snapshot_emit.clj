;; Turn target/jank-snapshot/events.edn into jank sources.
;;   bb dev/perf/jank_snapshot_emit.clj
;; Runtime-interned shim vars (is$, and dot$ recorded from missing-dot) become
;; defs in cohost.shim.snap. Calls in the recorded forms are retargeted there.
;; Each booted namespace refers cohost.core, not the host core.
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def events (edn/read-string (slurp "target/jank-snapshot/events.edn")))

(defn collect-syms [acc x]
  (cond
    (symbol? x)
    (let [nm (name x)]
      (if (and (= (namespace x) "cohost.shim")
               (or (str/starts-with? nm "is$")
                   (str/starts-with? nm "dot$")
                   (str/starts-with? nm "cast$")))
        (conj acc nm)
        acc))
    (map? x) (reduce collect-syms (reduce collect-syms acc (keys x)) (vals x))
    (coll? x) (reduce collect-syms acc x)
    :else acc))

(def shim-events
  (reduce (fn [m nm]
            (cond
              (contains? m nm) m
              (str/starts-with? nm "dot$")
              (assoc m nm {:op :dot :name nm :builtin true
                           :method (symbol (str "-" (subs nm 4)))})
              (str/starts-with? nm "cast$")
              (assoc m nm {:op :cast :name nm
                           :class (symbol (str/replace (subs nm 5) "_" "."))})
              :else m))
          (reduce (fn [m e]
                    (case (:op e)
                      (:is :dot) (assoc m (:name e) e)
                      m))
                  {}
                  events)
          (reduce collect-syms #{} (map :form (filter #(= (:op %) :form) events)))))

(def shim-snap-names (set (keys shim-events)))

(defn retarget [x]
  (cond
    (symbol? x)
    (if (and (= (namespace x) "cohost.shim") (contains? shim-snap-names (name x)))
      (symbol "cohost.shim.snap" (name x))
      x)
    (map? x) (with-meta (into (empty x) (map (fn [[k v]] [(retarget k) (retarget v)]) x)) (meta x))
    (vector? x) (with-meta (mapv retarget x) (meta x))
    (set? x) (with-meta (into (empty x) (map retarget x)) (meta x))
    (seq? x) (with-meta (doall (map retarget x)) (meta x))
    :else x))

(defn pr-src [form]
  (binding [*print-length* nil *print-level* nil *print-meta* true *print-namespace-maps* false]
    (pr-str form)))

(defn class-sym [s]
  (let [raw (subs s 3)]
    (symbol (str/replace raw "_" "."))))

(def snap-src
  (str "(ns cohost.shim.snap\n  (:require [cohost.shim]))\n\n"
       (str/join "\n\n"
         (for [e (sort-by (fn [e] [({:is 0 :dot 1 :cast 2} (:op e)) (:name e)]) (vals shim-events))]
           (case (:op e)
             :is (str "(def " (:name e) "\n"
                      "  (let [n (cohost.shim/install-is! '" (:class e) ")]\n"
                      "    (clojure.core/deref (clojure.core/ns-resolve 'cohost.shim (clojure.core/symbol n)))))")
             :dot (if (:builtin e)
                    (str "(def " (:name e) "\n"
                         "  (let [names (cohost.shim/install-dots! ['" (:method e) "])]\n"
                         "    (clojure.core/deref (clojure.core/ns-resolve 'cohost.shim (clojure.core/symbol (clojure.core/name (clojure.core/first names)))))))")
                    (str "(def " (:name e) "\n"
                         "  (let [n (cohost.shim/install-missing-dot! '" (:method e) ")]\n"
                         "    (clojure.core/deref (clojure.core/ns-resolve 'cohost.shim (clojure.core/symbol n)))))"))
             :cast (str "(def " (:name e) "\n"
                        "  (let [names (cohost.shim/install-casts! [['" (:name e) " '" (:class e) "]])]\n"
                        "    (clojure.core/deref (clojure.core/ns-resolve 'cohost.shim (clojure.core/symbol (clojure.core/name (clojure.core/first names)))))))"))))
       "\n"))

(defn ns-header [nm]
  (str "(ns " nm "\n"
       "  (:require [cohost.shim]\n"
       "            [cohost.shim.snap]"
       (when-not (= (str nm) "cohost.core")
         "\n            [cohost.core :refer :all]")
       "))\n"))

(defn bridge-forms [nm]
  (when (= (str nm) "cohost.core")
    ["(do (clojure.core/ns-unmap *ns* 'concat*) (def concat* (clojure.core/deref (clojure.core/ns-resolve 'clojure.core 'concat*))))"
     "(do (clojure.core/ns-unmap *ns* 'str) (def str (clojure.core/deref (clojure.core/ns-resolve 'clojure.core 'str))))"]))

(def compiler-specials
  "Host copies of these names shadow the forms jank needs while it compiles
   the snapshot. cohost.core/ns in particular makes the namespace symbol fail
   to resolve."
  #{'ns})

(defn host-forms [ns-sym]
  (for [e events
        :when (and (= (:op e) :host) (= (str (:ns e)) (str ns-sym))
                   (not (contains? compiler-specials (:sym e))))]
    (str "(do (clojure.core/ns-unmap *ns* '" (:sym e) ")\n"
         "    (def " (:sym e) " (clojure.core/deref (clojure.core/ns-resolve 'clojure.core '" (:sym e) ")))\n"
         "    (clojure.core/alter-meta! (var " (:sym e) ") clojure.core/merge '" (pr-src (:meta e)) "))")))

(defn defined-syms [ns-sym]
  (set (concat
        (for [e events
              :when (and (= (:op e) :host) (= (str (:ns e)) (str ns-sym)))]
          (:sym e))
        (for [e events
              :when (and (= (:op e) :form) (= (str (:ns e)) (str ns-sym)))
              :let [f (:form e)
                    h (when (seq? f) (first f))
                    inner (when (and (seq? f) (= 'do h)) (second f))
                    target (cond
                             (and (symbol? h) (contains? #{'def 'defn 'defn- 'defmacro} h)) (second f)
                             (and (seq? inner) (symbol? (first inner))
                                  (contains? #{'def 'defn 'defn- 'defmacro} (first inner)))
                             (second inner))]
              :when (symbol? target)]
          target))))

(defn macro-forms [ns-sym]
  (let [defined (defined-syms ns-sym)]
    (for [e events
          :when (and (= (:op e) :macro) (= (str (:ns e)) (str ns-sym)) (:wrap? e)
                     (contains? defined (:sym e)))]
      (str "(clojure.core/alter-meta! (var " (:sym e) ") clojure.core/assoc :macro true :cohost/wrapped true)\n"
           "(clojure.core/alter-var-root (var " (:sym e) ") (fn* [f] (cohost.boot/wrap-macro (cohost.boot/ctx) f)))"))))

(defn def-name [form]
  (cond
    (and (seq? form) (symbol? (second form))
         (contains? #{'def 'defn 'defn- 'defmacro} (first form)))
    (second form)
    (and (seq? form) (= 'do (first form)) (seq? (second form))
         (symbol? (second (second form)))
         (contains? #{'def 'defn 'defn- 'defmacro} (first (second form))))
    (second (second form))))

(defn body-forms [ns-sym]
  (for [e events
        :when (and (= (:op e) :form) (= (str (:ns e)) (str ns-sym)))
        :let [form (retarget (:form e))
              nm (def-name form)]]
    (if nm
      (str "(do (clojure.core/ns-unmap *ns* '" nm ")\n    " (pr-src form) ")")
      (pr-src form))))

(def nses
  (distinct
   (concat ['cohost.core]
           (keep (fn [e] (when (= (:op e) :ns) (:name e))) events))))

(fs/create-dirs "target/jank-snapshot/src/cohost/shim")
(spit "target/jank-snapshot/src/cohost/shim/snap.jank" snap-src)
(doseq [nm nses]
  (let [file (str "target/jank-snapshot/src/"
                  (-> (str nm)
                      (str/replace "-" "_")
                      (str/replace "." "/"))
                  ".jank")]
    (fs/create-dirs (fs/parent file))
    (spit file                     (str (ns-header nm)
                    "\n"
                    (str/join "\n" (or (bridge-forms nm) []))
                    "\n"
                    (str/join "\n" (host-forms nm))
                    "\n"
                    (str/join "\n" (body-forms nm))
                    "\n"))
    (println "WROTE" file "forms" (count (filter #(and (= (:op %) :form) (= (str (:ns %)) (str nm))) events)))))

(let [wraps (mapcat macro-forms nses)]
  (when (seq wraps)
    (spit "target/jank-snapshot/src/cohost/core_wrap.jank"
          (str "(ns cohost.core-wrap\n  (:require [cohost.core]\n            [cohost.boot]))\n"
               (str/join "\n" wraps) "\n"))
    (println "WROTE core_wrap" (count wraps))))
(println "SHIM-SNAP" (count shim-snap-names))
(println "NS" (pr-str nses))
