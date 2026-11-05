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
;; --project (D-8): the events of a project's namespaces recorded on top of a
;; snapshot boot (dev/perf/jank_snapshot_project_record.jank): only their
;; library modules, cohost.project (registers them: load-lib! requires them)
;; and cohost.project-build (requires them all: compile-module's entry).
;; Prints the modules in load order, one line each: MODULE <ns> <file> <forms>.
(require '[clojure.string :as str]
         '[babashka.fs :as fs])

;; For an app's executable (D-7, src/cohost/build_jank.clj):
;;   bb dev/perf/jank_snapshot_emit.clj --events <events.edn> --out <src dir> --main <app ns>
;; writes the modules under <src dir> and, for --main, cohost/app_main.jank:
;; the executable's entry, whose -main boots from the snapshot and calls the
;; app's -main.
(def cli-opts
  (loop [args (seq *command-line-args*) o {}]
    (if-let [[a b & more] args]
      (case a
        "--events" (recur more (assoc o :events b))
        "--out" (recur more (assoc o :out b))
        "--main" (recur more (assoc o :main b))
        "--project" (recur (next args) (assoc o :project true))
        "--host" (recur more (assoc o :host b))
        (recur (next args) o))
      o)))
(def src-root (or (:out cli-opts) "target/jank-snapshot/src"))

;; --host: the module dialect (D-6). jank (the default): .jank files, the
;; core qualified clojure.core, a bare namespace from its ns form. lpy
;; (Basilisp): .lpy files, basilisp.core, and an ordinary ns form -- Basilisp
;; refers its core whatever the ns form says, and the replay's first call
;; (snap-begin!, or the library's ns directive) runs the adapter's
;; ensure-bare-ns!, which clears the refers as the boot does.
(def lang (get {"lpy" {:core "basilisp.core" :ext ".lpy" :bare? false}
                "jolt" {:core "clojure.core" :ext ".clj" :bare? true}
                "lg" {:core "clojure.core" :ext ".clj" :bare? true}
                "cljw" {:core "clojure.core" :ext ".clj" :bare? true}
                ;; Phel finds a namespace's file by its literal (ns ...) form
                "phel" {:core "phel.core" :ext ".phel" :bare? false :plain-ns? true}}
               (:host cli-opts)
               {:core "clojure.core" :ext ".jank" :bare? true}))
(def C (:core lang))
(def NS (if (:plain-ns? lang) "ns" (str C "/ns")))

(def events
  (binding [*read-eval* false]
    (let [text (slurp (or (:events cli-opts) "target/jank-snapshot/events.edn"))]
      (read-string
       (if (= "phel" (:host cli-opts))
         ;; Phel prints $ in a string as \$ (PHP's escape): not a Clojure escape.
         ;; Only an unescaped one: an even run of backslashes before it is \\s.
         (str/replace text #"(?<!\\)((?:\\\\)*)\\\$" "$1\\$")
         text)))))

(defn decode
  "Undo cohost.boot's snap-encode: {:cohost.snap/meta m :cohost.snap/val x}
   is x with metadata m."
  [x]
  (cond
    (and (map? x) (contains? x :cohost.snap/regex))
    (if (= "phel" (:host cli-opts))
      ;; Phel writes no regex object into code: cohost's Phel reader makes one this way
      (list 'cohost.hosts.phel/re-literal (:cohost.snap/regex x))
      (re-pattern (:cohost.snap/regex x)))
    (and (map? x) (contains? x :cohost.snap/val) (contains? x :cohost.snap/meta))
    (let [v (decode (:cohost.snap/val x))
          m (decode (:cohost.snap/meta x))
          ;; U6-12b (B2): a native tag the codemod gives a fn* param is the
          ;; data (quote long) at eval; written as ^(quote long) into a module,
          ;; jank's analyzer reads it as a cpp/ type expression and fails
          ;; ("Unable to find `quote` within the global namespace"): write it
          ;; as the symbol it quotes, ^long, which jank reads the same
          t (:tag m)
          m (if (and (seq? t) (= 'quote (first t)) (symbol? (second t)) (nil? (nnext t)))
              (assoc m :tag (second t))
              m)]
      (if (instance? clojure.lang.IObj v) (with-meta v m) v))
    (map? x) (into {} (map (fn [[k v]] [(decode k) (decode v)]) x))
    (vector? x) (mapv decode x)
    (set? x) (set (map decode x))
    (seq? x) (apply list (map decode x))
    :else x))

(defn lpy-fix
  "Basilisp's reader takes no ns-qualified / (cohost.core//, the stock
   division fn, quoted in syntax-quote expansions): (quote ns//) is written
   (basilisp.core/symbol \"ns\" \"/\"), the same value. Its reader's own
   position keys (:basilisp.lang.reader/...) are dropped from metadata."
  [x]
  (let [strip (fn [m] (not-empty (into {} (remove (fn [[k _]] (and (keyword? k) (= "basilisp.lang.reader" (namespace k)))) m))))
        walk (fn walk [x]
               (let [y (cond
                         (and (seq? x) (= 'quote (first x)) (symbol? (second x))
                              (= "/" (name (second x))) (namespace (second x)))
                         (list 'basilisp.core/symbol (namespace (second x)) "/")
                         (map? x) (into (empty x) (map (fn [[k v]] [(walk k) (walk v)]) x))
                         (vector? x) (mapv walk x)
                         (set? x) (set (map walk x))
                         (seq? x) (apply list (map walk x))
                         :else x)]
                 (if (and (instance? clojure.lang.IObj y) (meta x))
                   (with-meta y (strip (meta x)))
                   y)))]
    (walk x)))

(defn escape-regexes
  "A regex's source as the host prints it can hold literal control
   characters (a recorded #\"\\r?\\n\" came back with a raw CR and LF); written
   into a module they do not survive the host's reader (Python reads a .lpy
   with universal newlines: the CR becomes LF). Every host's regex syntax
   reads \\r \\n \\t as those characters, so they are written escaped."
  [x]
  (let [esc (fn [^String p] (-> p (str/replace "\r" "\\r") (str/replace "\n" "\\n") (str/replace "\t" "\\t")))
        walk (fn walk [x]
               (let [y (cond
                         (instance? java.util.regex.Pattern x) (re-pattern (esc (str x)))
                         (map? x) (into (empty x) (map (fn [[k v]] [(walk k) (walk v)]) x))
                         (vector? x) (mapv walk x)
                         (set? x) (set (map walk x))
                         (seq? x) (apply list (map walk x))
                         :else x)]
                 (if (and (instance? clojure.lang.IObj y) (meta x)) (with-meta y (meta x)) y)))]
    (walk x)))

(defn pr-src [form]
  (binding [*print-length* nil *print-level* nil *print-meta* true *print-namespace-maps* false]
    (pr-str (escape-regexes (if (= "lpy" (:host cli-opts)) (lpy-fix form) form)))))

(defn quoted [x] (str "(quote " (pr-src x) ")"))

(def failed (set (keep #(when (= :lib-fail (:op %)) (:lib %)) events)))

(def lib-begins
  "lib -> its :lib-begin event, for the libraries that loaded."
  (into {} (keep (fn [e] (when (and (= :lib-begin (:op e)) (not (failed (:lib e)))) [(:lib e) e])) events)))

(defn module-ns [module]
  (if (= 'clojure.core module) 'cohost.core (:target (lib-begins module))))

(def modules
  "Load order: the core, then each library as its load began (a project's:
   its libraries only)."
  ((if (:project cli-opts) identity (partial cons 'clojure.core)) (keep (fn [e] (when (and (= :lib-begin (:op e)) (lib-begins (:lib e))) (:lib e))) events)))

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
    :thread-layer (str "(cohost.boot/snap-thread-layer!)")
    :http-layer (str "(cohost.boot/snap-http-layer!)")
    :lib-fill (str "(cohost.boot/snap-lib-fill! " (quoted (:lib e)) ")")
    :inline (str "(cohost.boot/snap-inline! " (quoted (:sym e)) " " (pr-src (decode (:form e))) ")")
    :directive (str "(cohost.boot/snap-directive! " (quoted (decode (:d e))) ")")
    :end (str "(cohost.boot/snap-end! " (quoted (:data e)) ")")
    :lib-end (str "(cohost.boot/snap-lib-end! " (quoted (:lib e)) " " (quoted (:entry e)) " "
                  (quoted (:data e)) " " (quoted (:parent (lib-begins (:lib e)))) ")")
    :declare (str "(def " (:sym e) ")")
    :native (str "(cohost.boot/snap-native! " (quoted (:ns e)) " " (quoted (:sym e)) " "
                 (pr-src (:lib e)) " " (quoted (:meta e)) ")")
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

(def lib-ends
  "The libraries in the order their loads finished: one a library loads
   finishes before it does."
  (keep (fn [e] (when (and (= :lib-end (:op e)) (lib-begins (:lib e))) (:lib e))) events))

(defn libs-before
  "Phel: the libraries whose loads finished before module's did."
  [module]
  (take-while #(not= module %) lib-ends))

;; Phel: a library form whose def failed in the boot from source (its report's
;; :error entries) still left the name in Phel's compile-time environment, so
;; the forms after it compiled there; the module, compiled as one file, gets
;; the same name by a declare (one name per declare: Phel's declares its first).
(def def-heads #{"def" "defn" "defn-" "defonce" "defmacro" "defmulti"})

(defn failed-def-names [module]
  (let [le (first (filter #(and (= :lib-end (:op %)) (= module (:lib %))) events))]
    (distinct
     (keep (fn [en]
             (when (:error en)
               (when-let [[_ head nm] (re-find #"^\((\S+)\s+(?:\^\S+\s+)?([^\s()\[\]{}]+)" (str (:form en)))]
                 (when (def-heads head) nm))))
           (get-in le [:entry :entries])))))

(defn- form-line
  "The original line of a recorded form event's form, from the position
   meta the record keeps (U4c: :jank/source's :start :line on jank,
   :line on the stream-reader hosts), or nil when it carries none."
  [e]
  (when (#{:form :inline} (:op e))
    (let [m (meta (decode (:form e)))]
      (or (get-in m [:jank/source :start :line]) (:line m)))))

(defn- join-at-lines
  "Join chunk strings with newlines, padding blank lines before a chunk
   whose target line (the parallel targets, nil for none) is ahead of
   the line it would land on, so the chunk starts on its target line.
   start-line is the line the first chunk lands on. A chunk multi-line
   as printed moves the lines after it by its own height, as today.
   Returns [text landings]: landings is the line each chunk starts on,
   parallel to chunks."
  [chunks targets start-line]
  (loop [cs (seq chunks) ts (seq targets) cur start-line out [] landings []]
    (if-not cs
      [(str/join "\n" out) landings]
      (let [pad (if (and (first ts) (> (first ts) cur)) (- (first ts) cur) 0)
            chunk (str (apply str (repeat pad "\n")) (first cs))]
        (recur (next cs) (next ts)
               (+ cur (count (filter #(= % \newline) chunk)) 1)
               (conj out chunk)
               (conj landings (+ cur pad)))))))

(def placement-totals (atom {:placed 0 :unplaceable 0 :targeted 0}))

(defn- verify-placement!
  "U4c assertion (coordinator addendum 645d12a section 3a): every
   recorded form that carries a target line must land on that line in
   the emitted module text, verified by scanning the final text (not
   the emitter's own accounting). A form whose target is above the
   line it lands on cannot be placed (padding cannot go back: the
   generated header occupies the first lines); those are counted and
   reported, not silently misplaced. A placeable form landing off its
   line is an emitter bug: fail loud."
  [module text chunks targets landings]
  (loop [idx 0 line 1 cs (seq chunks) ts (seq targets) ls (seq landings)]
    (when cs
      (let [found (if (first ts) (.indexOf ^String text ^String (first cs) idx) -1)
            actual (when (pos? found) (+ line (count (filter #(= % \newline) (subs text idx found)))))]
        (when (first ts)
          (swap! placement-totals update :targeted inc)
          (cond
            (neg? found)
            (throw (ex-info (str "emitter placement: a form of " module " was not found in the emitted text")
                            {:module module :target (first ts)}))
            (not= actual (first ls))
            (throw (ex-info (str "emitter placement: accounting mismatch in " module)
                            {:module module :target (first ts) :accounted (first ls) :scanned actual}))
            (= actual (first ts))
            (swap! placement-totals update :placed inc)
            (> actual (first ts))
            (swap! placement-totals update :unplaceable inc)
            :else
            (throw (ex-info (str "emitter placement: form of " module " landed above its target line")
                            {:module module :target (first ts) :actual actual}))))
        (recur (if (pos? found) (+ found (count (first cs))) idx)
               (if (pos? found)
                 (+ actual (count (filter #(= % \newline) (first cs))))
                 line)
               (next cs) (next ts) (next ls))))))

(defn module-src [module]
  (let [nm (module-ns module)
        evs (events-of module)
        core? (= 'clojure.core module)
        begin (first (filter #(= :begin (:op %)) events))
        lb (lib-begins module)
        ;; cohost.run (the adapter, the boot) is required before the bare ns:
        ;; jank analyzes a required file's ns form in the requiring namespace
        ;; cohost.cli too, so the snapshot's compile makes its object (D-8)
        ;; Phel: its ns form comes first in the file, so they are the ns's requires
        header (str (when (and core? (not (:plain-ns? lang)))
                      (str "(" C "/require (quote cohost.run))\n(" C "/require (quote cohost.cli))\n"))
                    "(" NS " " nm
                    (if (:bare? lang) "\n  (:refer-clojure :only [])" "")
                    (cond (and core? (:plain-ns? lang)) "\n  (:require [cohost.run] [cohost.cli]))\n"
                          core? ")\n"
                          ;; Phel: the libraries this one loaded (its replayed requires) load
                          ;; first, by its ns form, since nothing is required at run time
                          ;; (every library loaded before it, and every one it loaded)
                          (:plain-ns? lang)
                          (str "\n  (:require [cohost.boot] [cohost.core]"
                               (apply str (map #(str " [" (module-ns %) "]") (libs-before module)))
                               "))\n")
                          :else "\n  (:require [cohost.boot] [cohost.core]))\n")
                    (when (and (:plain-ns? lang) (not core?))
                      (apply str (map #(str "(declare " % ")\n") (failed-def-names module))))
                    (if core?
                      (str "(cohost.boot/snap-begin! " (quoted (:opts begin)) " " (quoted libs-map) ")\n")
                      (str "(cohost.boot/snap-lib-begin! " (quoted (:lib lb)) " " (quoted (:target lb)) " "
                           (pr-src (:path lb)) " " (quoted (:parent lb)) ")\n"))
                    (str/join "\n" (hoisted evs)) "\n")
        chunks (keep (fn [e] (when-let [s (event-src e)] [s (form-line e)]))
                     (collapse-scans evs))
        chunk-strs (map first chunks)
        targets (map second chunks)
        [body landings] (join-at-lines chunk-strs targets
                                       (inc (count (filter #(= % \newline) header))))
        text (str header body "\n")]
    ;; each recorded form at its original line (U4c), asserted against
    ;; the emitted text; unplaceable forms (target above the header's
    ;; end) are counted in the PLACEMENT summary, never silently off
    (verify-placement! nm text chunk-strs targets landings)
    text))

(defn module-file [nm]
  (str src-root "/" (-> (str nm) (str/replace "-" "_") (str/replace "." "/")) (:ext lang)))

(fs/delete-tree src-root)
(doseq [m modules]
  (let [nm (module-ns m)
        file (module-file nm)]
    (fs/create-dirs (fs/parent file))
    (spit file (module-src m))
    (println "MODULE" nm file (count (filter #(= :form (:op %)) (events-of m))))))
(when (seq failed) (println "FAILED-LIBS" (pr-str (sort failed))))
(let [{:keys [placed unplaceable targeted]} @placement-totals]
  (println "PLACEMENT" placed "of" targeted "targeted forms at their original lines,"
           unplaceable "unplaceable"))

;; The build's entry: one compile-module of cohost.snapshot compiles the core
;; and every library module (a library a library requires is loaded, and
;; compiled, by its directive's replay). A boot from the snapshot requires
;; cohost.core only; the libraries load when load-lib! asks for them.
;; Phel has no require at run time: there the entry requires every library
;; module, in load order, so a library's replayed require finds it loaded.
(let [top (keep (fn [m] (when (and (not= 'clojure.core m) (or (:plain-ns? lang) (nil? (:parent (lib-begins m))))) (module-ns m))) modules)]
  (when (:project cli-opts)
    ;; a project's own modules sit under its namespaces' directories; cohost/ may not exist yet
    (fs/create-dirs (fs/parent (module-file 'cohost.project)))
    (spit (module-file 'cohost.project)
          (str "(" NS " cohost.project\n  (:require [cohost.boot]))\n"
               "(cohost.boot/register-snapshot-libs! " (quoted libs-map) ")\n"))
    (spit (module-file 'cohost.project-build)
          (str "(" NS " cohost.project-build\n  (:require [cohost.core] [cohost.project]"
               (apply str (map #(str " [" % "]") top)) "))\n"))
    (println "MODULE cohost.project" (module-file 'cohost.project) (count libs-map)))
  (when-not (:project cli-opts)
  (spit (module-file 'cohost.snapshot)
        (str "(" NS " cohost.snapshot\n  (:require [cohost.core]"
             (apply str (map #(str " [" % "]") top)) "))\n"))
  (println "MODULE cohost.snapshot" (module-file 'cohost.snapshot) (count top))))

(when-let [app (:main cli-opts)]
  (let [lib (symbol app)
        m (module-ns lib)]
    (when-not (lib-begins lib)
      (throw (ex-info (str "the app's namespace " app " was not recorded (or failed to load)") {:failed failed})))
    (spit (module-file 'cohost.app-main)
          (str "(" NS " cohost.app-main\n  (:require [cohost.core] [cohost.cli] [" m "]))\n"
               "(" C "/defn -main [& args]\n"
               "  (cohost.boot/boot-from-snapshot! {})\n"
               "  (cohost.boot/make-user-ns!)\n"
               "  (cohost.cli/run-app-main! (quote " m ") args))\n"))
    (println "MODULE cohost.app-main" (module-file 'cohost.app-main) "main" (str m "/-main"))))
