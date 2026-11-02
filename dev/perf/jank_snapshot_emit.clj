;; jank-FAST Step 6 stage 2. Turn target/jank-snapshot/events.edn (written by
;; dev/perf/jank_snapshot_record.jank) into one jank source module per booted
;; namespace, under target/jank-snapshot/src/.
;;   bb dev/perf/jank_snapshot_emit.clj
;;
;; A module is the recorded boot of its namespace, in the order it happened:
;; the forms the boot evaluated, and between them calls of cohost.boot's
;; snap-* fns, which redo what the boot did through the adapter (interns from
;; the host, macro marking and wrapping, the loader's directives, the shim's
;; prebuilt instance tests) and restore the boot's state as data. Each module
;; starts in a bare namespace (:refer-clojure :only []), as the boot's own
;; namespaces do; the replayed directives refer cohost.core into a library's.
;; Prints the modules in load order, one line each: MODULE <ns> <file> <forms>.
(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(def events
  (binding [*read-eval* false]
    (read-string (slurp "target/jank-snapshot/events.edn"))))

(defn decode
  "Undo cohost.boot's snap-encode: {:cohost.snap/meta m :cohost.snap/val x}
   is x with metadata m."
  [x]
  (cond
    (and (map? x) (contains? x :cohost.snap/val) (contains? x :cohost.snap/meta))
    (let [v (decode (:cohost.snap/val x))
          m (decode (:cohost.snap/meta x))]
      (if (instance? clojure.lang.IObj v) (with-meta v m) v))
    (map? x) (into {} (map (fn [[k v]] [(decode k) (decode v)]) x))
    (vector? x) (mapv decode x)
    (set? x) (set (map decode x))
    (seq? x) (apply list (map decode x))
    :else x))

(defn pr-src [form]
  (binding [*print-length* nil *print-level* nil *print-meta* true *print-namespace-maps* false]
    (pr-str form)))

(defn quoted [x] (str "(quote " (pr-src x) ")"))

(def failed (set (keep #(when (= :lib-fail (:op %)) (:lib %)) events)))

(def lib-begins
  "lib -> its :lib-begin event, for the libraries that loaded."
  (into {} (keep (fn [e] (when (and (= :lib-begin (:op e)) (not (failed (:lib e)))) [(:lib e) e])) events)))

(defn module-ns [module]
  (if (= 'clojure.core module) 'cohost.core (:target (lib-begins module))))

(def modules
  "Load order: the core, then each library as its load began."
  (cons 'clojure.core (keep (fn [e] (when (and (= :lib-begin (:op e)) (lib-begins (:lib e))) (:lib e))) events)))

(def libs-map (into {} (map (fn [[lib e]] [lib (:target e)]) lib-begins)))

(defn events-of [module] (filter #(= module (:module %)) events))

(defn hoisted
  "The shim's instance tests and missing-method dispatchers the module's forms
   use: first, since a compiled form refers to their vars."
  [evs]
  (distinct
   (keep (fn [e]
           (case (:op e)
             :is (str "(cohost.boot/snap-is! " (quoted (:class e)) ")")
             :dot (str "(cohost.boot/snap-dot! " (quoted (:method e)) ")")
             nil))
         evs)))

(defn event-src [e]
  (case (:op e)
    :form (pr-src (decode (:form e)))
    :host (str "(cohost.boot/snap-take! " (quoted (:sym e)) ")")
    :extra (str "(cohost.boot/snap-extra! " (quoted (:sym e)) ")")
    :scan (str "(cohost.boot/snap-scan! " (quoted (:ns e)) ")")
    :inline (str "(cohost.boot/snap-inline! " (quoted (:sym e)) " " (pr-src (decode (:form e))) ")")
    :directive (str "(cohost.boot/snap-directive! " (quoted (decode (:d e))) ")")
    :end (str "(cohost.boot/snap-end! " (quoted (:data e)) ")")
    :lib-end (str "(cohost.boot/snap-lib-end! " (quoted (:lib e)) " " (quoted (:entry e)) " "
                  (quoted (:data e)) " " (quoted (:parent (lib-begins (:lib e)))) ")")
    :native (throw (ex-info "a native in the snapshot: not emitted on jank" e))
    (:is :dot :begin :lib-begin :lib-fail) nil))

(defn collapse-scans
  "One snap-scan! per run of :scan events of one namespace (scan-macros! does
   them all in one call)."
  [evs]
  (reduce (fn [acc e]
            (let [p (peek acc)]
              (if (and (= :scan (:op e)) p (= :scan (:op p)) (= (:ns p) (:ns e)))
                acc
                (conj acc e))))
          [] evs))

(defn module-src [module]
  (let [nm (module-ns module)
        evs (events-of module)
        core? (= 'clojure.core module)
        begin (first (filter #(= :begin (:op %)) events))
        lb (lib-begins module)]
    ;; cohost.run (the adapter, the boot) is required before the bare ns:
    ;; jank analyzes a required file's ns form in the requiring namespace
    (str (when core? "(clojure.core/require (quote cohost.run))\n")
         "(clojure.core/ns " nm "\n"
         "  (:refer-clojure :only [])"
         (if core? ")\n" "\n  (:require [cohost.boot] [cohost.core]))\n")
         (if core?
           (str "(cohost.boot/snap-begin! " (quoted (:opts begin)) " " (quoted libs-map) ")\n")
           (str "(cohost.boot/snap-lib-begin! " (quoted (:lib lb)) " " (quoted (:target lb)) " "
                (pr-src (:path lb)) " " (quoted (:parent lb)) ")\n"))
         (str/join "\n" (hoisted evs)) "\n"
         (str/join "\n" (keep event-src (collapse-scans evs))) "\n")))

(defn module-file [nm]
  (str "target/jank-snapshot/src/" (-> (str nm) (str/replace "-" "_") (str/replace "." "/")) ".jank"))

(fs/delete-tree "target/jank-snapshot/src")
(doseq [m modules]
  (let [nm (module-ns m)
        file (module-file nm)]
    (fs/create-dirs (fs/parent file))
    (spit file (module-src m))
    (println "MODULE" nm file (count (filter #(= :form (:op %)) (events-of m))))))
(when (seq failed) (println "FAILED-LIBS" (pr-str (sort failed))))

;; The build's entry: one compile-module of cohost.snapshot compiles the core
;; and every library module (a library a library requires is loaded, and
;; compiled, by its directive's replay). A boot from the snapshot requires
;; cohost.core only; the libraries load when load-lib! asks for them.
(let [top (keep (fn [m] (when (and (not= 'clojure.core m) (nil? (:parent (lib-begins m)))) (module-ns m))) modules)]
  (spit (module-file 'cohost.snapshot)
        (str "(clojure.core/ns cohost.snapshot\n  (:require [cohost.core]"
             (apply str (map #(str " [" % "]") top)) "))\n"))
  (println "MODULE cohost.snapshot" (module-file 'cohost.snapshot) (count top)))
