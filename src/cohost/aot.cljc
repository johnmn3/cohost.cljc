(ns cohost.aot
  "Pre-AOT on first run, for every sister, across all apps — the platform's compile cache.

   The law (John, 2026-11-14): all sisters pre-AOT their runtimes on first run, the way Clojure's
   .cpcache works across all projects; libraries cache too, not just app entries — including JVM
   libraries run through jolt/champaca. A sister's compiled form is a function of its BASIS: the
   sister, its runtime version, and the exact source/jar set it will load. Same basis, same artifact,
   whoever asks — the cache is per-user (~/.cache/cohost/aot), never per-app or per-checkout.

   This namespace is the contract every sister's compiler registers with:

     basis    — {:sister :jvm, :runtime \"25.0.4.1\", :files #{<abs paths>}, :extra \"<resolved cp>\"}
     stamp    — sha256 over the basis with every file content-hashed (mtimes lie: checkouts and
                resets rewrite them; content is the identity)
     artifact — <cache-root>/aot/<sister>/<stamp>/, published atomically (build in a sibling temp
                dir, rename into place), built under a lock so two first runs do not build twice
     entries  — an artifact holds the compiled form of the whole basis graph; each ENTRY (an app or
                runtime main namespace) is marked by its compiled presence, and ensure! compiles
                only the entries an artifact lacks. Two apps over one basis share the libraries'
                classes; each adds only its own entry's.

   The sisters' compilers, as they stand:

     :jvm      clojure.core/compile of the entries over the basis classpath -> .class files.
               Reference consumer: the Eve capstone launcher (capstone/cohost-media), whose JVM
               children (supervisor, gateway, member) used to re-compile the whole Eve graph in
               every process (~45 s cold on a loaded box; the namespaces' own init is ~15 s of it
               and no classpath mechanism removes that — the classes remove the compilation).
     :champaca champaca's own libcache (per-file content-keyed object code) plus clj_champaca.clj's
               save-cache! first-run arm — already a cross-app lib cache; its cache dir is
               <cache-root>/champaca (bin/cohost_clj_body.clj's champaca-cache-dir).
     :lpy      dev/perf/lpy_snapshot_ensure.clj — a stamped snapshot of the booted core.
     :jank     dev/perf/jank_aot_ensure.clj (-O3 objects of cohost's namespaces) plus the snapshot
               machinery in dev/perf/jank_snapshot_cli.clj.
     :cljs     the compiled body (bin/cohost's ensure-cljs-build!).
     :squint   the compiled body (bin/cohost's ensure-squint-build!).

   Known gap, named rather than hidden: the :cljs and :squint bodies still decide staleness by
   file mtime and keep their artifacts under the checkout's target/. Converting them is mechanical
   — basis-stamp instead of mtime, artifact dir here instead of target/ — and is the next step
   after the :jvm instance; the lpy/jank ensures predate this namespace and register by being
   named here, their internals unchanged."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.java.io :as io]
               :bb   [babashka.fs :as fs])))

(defn cache-root
  "$HOME/.cache/cohost (HOME wins over user.home, as in bin/cohost_clj_body.clj's cohost-cache-root)."
  []
  #?(:clj (str (io/file (or (some-> (System/getenv "HOME") str/trim not-empty)
                            (System/getProperty "user.home"))
                        ".cache" "cohost"))
     :bb  (str (fs/file (or (some-> (System/getenv "HOME") str/trim not-empty)
                            (str (fs/home)))
                        ".cache" "cohost"))))

(defn sha256-hex
  "The sha256 of a byte array, lowercase hex."
  [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md bs)
    (apply str (map (fn [b] (format "%02x" b)) (.digest md)))))

(defn- file-bytes [f]
  #?(:clj (with-open [in (io/input-stream f)]
            (let [out (java.io.ByteArrayOutputStream.)]
              (io/copy in out)
              (.toByteArray out)))
     :bb  (fs/read-all-bytes f)))

(defn basis-stamp
  "A basis map -> its stamp. Every file in :files is content-hashed in sorted path order, with the
   sister, the runtime version and :extra (e.g. the resolved classpath string, which carries the
   jar set's identity) hashed first. Two bases with the same stamp are the same basis."
  [{:keys [sister runtime files extra]}]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        eat (fn [x] (.update md (.getBytes (str x) "UTF-8")))]
    (eat (str "sister:" (name sister) "\nruntime:" runtime "\nextra:" extra "\n"))
    (doseq [f (sort (map str files))]
      (eat (str "\n" f "\n"))
      (.update md (file-bytes f)))
    (apply str (map (fn [b] (format "%02x" b)) (.digest md)))))

(defn artifact-dir
  "The artifact directory of a sister + stamp under the shared cache root."
  [sister stamp]
  (str #?(:clj (io/file (cache-root) "aot" (name sister) stamp)
          :bb  (fs/file (cache-root) "aot" (name sister) stamp))))

(defn entry-marker
  "The relative path whose presence in an artifact proves an entry was compiled into it. For the
   :jvm sister, the entry namespace's __init class (munged: hyphens to underscores, dots to
   slashes); other sisters name their own via :marker."
  [entry]
  (str (-> (str entry) (str/replace "-" "_") (str/replace "." "/")) "__init.class"))

(defn- exists? [f]
  #?(:clj (.exists (io/file f)) :bb (fs/exists? f)))

(defn- mkdirs [f]
  #?(:clj (.mkdirs (io/file f)) :bb (fs/create-dirs f)))

(defn- delete-tree [f]
  #?(:clj (let [f (io/file f)]
            (when (.isDirectory f)
              (doseq [c (.listFiles f)] (delete-tree c)))
            (io/delete-file f true))
     :bb  (fs/delete-tree f)))

(defn- rename [from to]
  #?(:clj (.renameTo (io/file from) (io/file to))
     :bb  (fs/move from to)))

(defn ensure!
  "The artifact dir for a basis, compiling on first run whatever entries it lacks.

   opts: {:sister :stamp :entries [ns-sym ...] :marker (fn [entry] rel-path)
          :build! (fn [dir missing-entries]) :lock-timeout-ms (default 30 min)}
   :build! compiles the missing entries into dir (a plain directory on the consumer's classpath;
   for :jvm, a JVM run of (compile entry) over the basis classpath with *compile-path* bound to
   dir). Publishing is per-entry, not atomic per artifact: a crashed build leaves the entries it
   finished usable and the rest to be compiled by the next ensure!. Builds of one artifact are
   serialized by a lock directory; a lock older than :lock-timeout-ms is treated as abandoned.
   Returns the artifact dir."
  [{:keys [sister stamp entries marker build! lock-timeout-ms]
    :or {marker entry-marker lock-timeout-ms 1800000}}]
  (let [dir (artifact-dir sister stamp)
        missing (fn [] (vec (remove (fn [e] (exists? (str dir "/" (marker e)))) entries)))]
    (mkdirs dir)
    (if (empty? (missing))
      dir
      (let [lock (str dir ".lock")]
        (loop [waited 0]
          (cond
            (empty? (missing)) dir
            #?(:clj (.mkdir (io/file lock)) :bb (.mkdir (java.io.File. lock)))
            (do (try (build! dir (missing))
                     (finally (delete-tree lock)))
                dir)
            (and (exists? lock)
                 (> (- (System/currentTimeMillis)
                       #?(:clj (.lastModified (io/file lock)) :bb (.lastModified (java.io.File. lock))))
                    lock-timeout-ms))
            (do (delete-tree lock) (recur waited))
            (> waited lock-timeout-ms)
            (throw (ex-info (str "cohost.aot: timed out waiting for the " (name sister)
                                 " AOT build lock " lock)
                            {:sister sister :stamp stamp}))
            :else (do (Thread/sleep 500) (recur (+ waited 500)))))))))
