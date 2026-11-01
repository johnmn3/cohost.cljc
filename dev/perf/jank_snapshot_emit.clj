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
         (for [e (sort-by :name (vals shim-events))]
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

(defn host-forms [ns-sym]
  (for [e events
        :when (and (= (:op e) :host) (= (str (:ns e)) (str ns-sym)))]
    (str "(def " (:sym e) " (clojure.core/deref (clojure.core/ns-resolve 'clojure.core '" (:sym e) ")))\n"
         "(clojure.core/alter-meta! (var " (:sym e) ") clojure.core/merge '" (pr-src (:meta e)) ")")))

(defn macro-forms [ns-sym]
  (for [e events
        :when (and (= (:op e) :macro) (= (str (:ns e)) (str ns-sym)) (:wrap? e))]
    (str "(clojure.core/alter-meta! (var " (:sym e) ") clojure.core/assoc :macro true :cohost/wrapped true)\n"
         "(clojure.core/alter-var-root (var " (:sym e) ") (fn [f] (cohost.boot/wrap-macro (cohost.boot/ctx) f)))")))

(defn body-forms [ns-sym]
  (for [e events
        :when (and (= (:op e) :form) (= (str (:ns e)) (str ns-sym)))]
    (pr-src (retarget (:form e)))))

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
    (spit file (str (ns-header nm)
                    "\n"
                    (str/join "\n" (host-forms nm))
                    "\n"
                    (str/join "\n" (body-forms nm))
                    "\n"
                    (str/join "\n" (macro-forms nm))
                    "\n"))
    (println "WROTE" file "forms" (count (filter #(and (= (:op %) :form) (= (str (:ns %)) (str nm))) events)))))

(println "SHIM-SNAP" (count shim-snap-names))
(println "NS" (pr-str nses))
