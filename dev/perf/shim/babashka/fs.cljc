;; babashka.fs -- cohost's vendored slice for the snapshot build.
;;
;; The snapshot build chain (dev/perf/jank_snapshot_ensure.clj ->
;; jank_snapshot_key.clj -> jank_inputs.clj, jank_aot_ensure.clj,
;; jank_snapshot_emit.clj) was written against babashka.fs /
;; babashka.process. Those libraries have no :cljr arms in any
;; release, which is what kept the snapshot build from loading
;; under ClojureCLR; on the clj seat they arrived as Maven pins in
;; install.cljc's -Sdeps. This file (and its process.cljc sibling)
;; is the slice of that API the build actually calls -- the census
;; is in INSTALL-DISENTANGLE-STATUS.md -- vendored into the
;; checkout and resolved by path precedence, the same trick
;; dev/install/shim/babashka/fs.jank already plays for the jank
;; seat:
;;
;;   bb seat:   never loaded (bb's built-in babashka.fs resolves).
;;   jolt seat: never loaded (jolt's bundled libraries resolve).
;;   clj seat:  install.cljc puts dev/perf/shim on the ensure
;;              process's -Sdeps :paths instead of the Maven pins.
;;   CLR:       the same directory on CLOJURE_LOAD_PATH.
;;
;; Arms: :clj on java.nio.file; :cljr on System.IO. Path/parent/
;; components are the string operations of the proven jank shim
;; (dev/install/shim/babashka/fs.jank), shared by both arms; the
;; hosts run POSIX paths. Values are strings throughout, which is
;; the form every call site consumes (the originals str them).
;; (The arms are single (do ...) forms under #?: splicing reader
;; conditionals are not allowed at a file's top level.)
;;
;; One known :cljr non-conformance, by design of the call site:
;; last-modified-time returns a System.DateTime under :cljr, and
;; its one reader (jank_snapshot_key.clj's jank-version) calls
;; .toMillis on it -- but only on the JANK_BIN branch, which the
;; default CLR path never takes. size's :cljr arm conforms
;; (FileInfo.Length, a long like Files/size).
(ns babashka.fs
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------ string paths

(defn path
  "Join path segments with / (babashka.fs/path, string form)."
  ([a] (str a))
  ([a b & more]
   (reduce (fn [acc seg]
             (let [seg (str seg)]
               (cond (str/starts-with? seg "/") seg
                     (str/ends-with? acc "/") (str acc seg)
                     :else (str acc "/" seg))))
           (str a) (cons b more))))

(defn parent
  "The parent directory of p, as a string (nil for a bare name)."
  [p]
  (let [p (str p)
        p (if (and (> (count p) 1) (str/ends-with? p "/")) (subs p 0 (dec (count p))) p)
        i (str/last-index-of p "/")]
    (cond (neg? i) nil
          (zero? i) "/"
          :else (subs p 0 i))))

(defn components
  "The name components of p, as a vector of strings."
  [p]
  (vec (remove str/blank? (str/split (str p) #"/"))))

;; ------------------------------------------------------------ glob

(defn- escape-char [c]
  (if (contains? #{\. \+ \? \( \) \[ \] \^ \$ \| \\} c) (str \\ c) (str c)))

(defn- escape-segment
  "Regex for one glob segment alternative: * is [^/]*, the rest literal."
  [s]
  (apply str (map (fn [c] (if (= c \*) "[^/]*" (escape-char c))) s)))

(defn- glob-regex
  "The census patterns: ** (any depth), * (one segment),
   {a,b,c} (alternation inside a segment), literals otherwise.
   Built by string accumulation, not a StringBuilder: this code
   is shared by both arms, and the builder's method spelling
   differs per host (append on the JVM, Append on .NET)."
  [pattern]
  (loop [i 0 acc ""]
    (if (>= i (count pattern))
      (re-pattern (str "^" acc "$"))
      (let [c (nth pattern i)]
        (cond
          (and (= c \*) (< (inc i) (count pattern)) (= (nth pattern (inc i)) \*))
          (if (= (nth pattern (+ i 2) nil) \/)
            (recur (+ i 3) (str acc "(?:.*/)?"))
            (recur (+ i 2) (str acc ".*")))
          (= c \*)
          (recur (inc i) (str acc "[^/]*"))
          (= c \{)
          (let [j (str/index-of pattern "}" i)
                alts (str/split (subs pattern (inc i) j) #",")]
            (recur (inc j) (str acc "(?:" (str/join "|" (map escape-segment alts)) ")")))
          :else
          (recur (inc i) (str acc (escape-char c))))))))

#?(:clj
   (do
     (defn- nio-path [p]
       (java.nio.file.Paths/get (str p) (into-array String [])))

     (defn- nio-opts [] (into-array java.nio.file.LinkOption []))

     (defn absolutize
       "p made absolute against the process cwd (not normalized)."
       [p]
       (str (.toAbsolutePath (nio-path p))))

     (defn real-path
       "p canonicalized, symlinks resolved; throws when missing."
       [p]
       (str (.toRealPath (nio-path p) (nio-opts))))

     (defn regular-file? [p] (java.nio.file.Files/isRegularFile (nio-path p) (nio-opts)))

     (defn exists? [p] (java.nio.file.Files/exists (nio-path p) (nio-opts)))

     (defn executable? [p] (java.nio.file.Files/isExecutable (nio-path p)))

     (defn size
       "The file's length in bytes, as a long."
       [p]
       (java.nio.file.Files/size (nio-path p)))

     (defn last-modified-time
       "java.nio.file.attribute.FileTime (its reader calls .toMillis)."
       [p]
       (java.nio.file.Files/getLastModifiedTime (nio-path p) (nio-opts)))

     (defn create-dirs
       [p]
       (java.nio.file.Files/createDirectories (nio-path p)
                                              (into-array java.nio.file.attribute.FileAttribute []))
       (str p))

     (defn delete-if-exists [p] (java.nio.file.Files/deleteIfExists (nio-path p)))

     (defn delete-tree
       "Delete p and everything under it; a missing p is a no-op."
       [p]
       (let [root (nio-path p)]
         (when (java.nio.file.Files/exists root (nio-opts))
           (let [stream (java.nio.file.Files/walk root (into-array java.nio.file.FileVisitOption []))]
             (try (doseq [x (reverse (iterator-seq (.iterator stream)))]
                    (java.nio.file.Files/delete x))
                  (finally (.close stream))))))
       nil)

     (defn glob
       "Paths under root matching pattern, as strings of the form
        root/relative (the form the key's input listing hashes).
        A missing root answers []. Order is walk order; callers
        that care sort."
       [root pattern]
       (let [root-path (nio-path root)]
         (if-not (java.nio.file.Files/isDirectory root-path (nio-opts))
           []
           (let [re (glob-regex pattern)
                 stream (java.nio.file.Files/walk root-path (into-array java.nio.file.FileVisitOption []))
                 prefix (let [r (str root)] (if (str/ends-with? r "/") r (str r "/")))]
             (try (into []
                        (comp (map (fn [x] (str (.relativize root-path x))))
                              (remove str/blank?)
                              (filter (fn [rel] (re-matches re rel)))
                              (map (fn [rel] (str prefix rel))))
                        (iterator-seq (.iterator stream)))
                  (finally (.close stream))))))))

   :cljr
   (do
     (defn absolutize [p] (System.IO.Path/GetFullPath (str p)))

     (defn real-path
       "GetFullPath's answer; .NET on this host does not resolve a
        final-component symlink the way toRealPath does -- noted,
        not hidden (JANK_BIN branch only)."
       [p]
       (System.IO.Path/GetFullPath (str p)))

     (defn regular-file? [p] (System.IO.File/Exists (str p)))

     (defn exists? [p] (or (System.IO.File/Exists (str p))
                           (System.IO.Directory/Exists (str p))))

     (defn executable?
       [p]
       (and (System.IO.File/Exists (str p))
            (try (let [m (int (System.IO.File/GetUnixFileMode (str p)))]
                   (not (zero? (bit-and m 73))))
                 (catch Exception _ true))))

     (defn size [p] (.Length (System.IO.FileInfo. (str p))))

     (defn last-modified-time
       "System.DateTime (UTC). Its one reader calls .toMillis,
        which DateTime lacks -- but that reader sits on the
        JANK_BIN-only branch; see the header."
       [p]
       (System.IO.File/GetLastWriteTimeUtc (str p)))

     (defn create-dirs
       [p]
       (System.IO.Directory/CreateDirectory (str p))
       (str p))

     (defn delete-if-exists
       [p]
       (cond (System.IO.File/Exists (str p)) (do (System.IO.File/Delete (str p)) true)
             (System.IO.Directory/Exists (str p)) (do (System.IO.Directory/Delete (str p)) true)
             :else false))

     (defn delete-tree
       [p]
       (when (System.IO.Directory/Exists (str p))
         (System.IO.Directory/Delete (str p) true))
       (when (System.IO.File/Exists (str p))
         (System.IO.File/Delete (str p)))
       nil)

     (defn glob
       [root pattern]
       (if-not (System.IO.Directory/Exists (str root))
         []
         (let [re (glob-regex pattern)
               entries (System.IO.Directory/EnumerateFileSystemEntries
                        (str root) "*" System.IO.SearchOption/AllDirectories)
               prefix (let [r (str root)] (if (str/ends-with? r "/") r (str r "/")))]
           (into []
                 (comp (map (fn [x] (subs (str x) (count prefix))))
                       (filter (fn [rel] (re-matches re rel)))
                       (map (fn [rel] (str prefix rel))))
                 entries))))))
