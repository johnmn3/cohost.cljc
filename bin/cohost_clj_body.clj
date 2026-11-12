(ns cohost.clj-body
  "The :clj leg's runtime is the stock `clojure` CLI.

   cohost does not inspect a JVM. `clojure` on PATH keeps the existing
   launch. Otherwise, when `jolt` is on PATH, the leg runs clojure.main
   on a champaca bundle: jolt resolves the project classpath, and
   lib/champaca runs it. -T and -Sdescribe stay on the `clojure` CLI.

   COHOST_CLJ_BODY=clojure|jolt forces one of those. Unset follows PATH.

   The bundle is COHOST_CLJ_BUNDLE, or $HOME/.cache/cohost/cohost-clj.
   Champaca's startup cache is $HOME/.cache/cohost/champaca.

   A host string such as :jolt:lpy names several hosts. Which of them owns
   the :clj leg is resolve-clj-leg!, stored in the clj-leg atom for a later
   cross-body router. This process launches that provider only.

   Champaca's guest-private JVM/Chez internals are waived from the memory
   law (John, 00:50 ET). Cohost-side state for this decision is the
   clj-providers table and the clj-leg atom. No journal, scanner, header
   counter, or arena."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private path-sep java.io.File/pathSeparator)

(def clj-providers
  "Host token -> :clj provider. Missing tokens have no :clj leg.
   :champaca is jolt's bundle launch. :jvm is the :jvm selector
   (`clojure` on PATH, otherwise champaca). :clojure-cli is the official
   JVM `clojure` CLI, with no champaca fallback. A later host registers
   with swap!."
  (atom {"jolt" :champaca
         "jvm" :jvm
         "clj" :clojure-cli}))

(def clj-absolute
  "Tokens that own :clj from any position, ahead of list order."
  #{"clj"})

(def clj-leg
  "Last resolution: {:hosts [...] :provider keyword-or-nil :owner name}.
   A later router reads this. Launch uses the same map."
  (atom {:hosts [] :provider nil :owner nil}))

(defn parse-host-string
  "\"jolt:lpy\" -> [\"jolt\" \"lpy\"]. Blank segments are dropped."
  [s]
  (vec (remove str/blank? (str/split (or s "") #":"))))

(defn launch-resolved-leg?
  "Multi-host strings, and any string that names :clj, launch the resolved
   :clj provider. A single existing host keeps the launch it has today."
  [hosts]
  (or (> (count hosts) 1) (some clj-absolute hosts)))

(defn resolve-clj-leg!
  "John, 09:28 ET. :clj anywhere means the official clojure CLI. Otherwise
   the first listed host that has a :clj provider owns the leg. Otherwise
   there is no provider and :owner is the first host (its own launch)."
  [hosts]
  (let [hosts (vec hosts)
        table @clj-providers
        absolute (first (filter clj-absolute hosts))
        owner (or absolute
                  (first (filter #(contains? table %) hosts))
                  (first hosts))
        provider (when (and owner (contains? table owner))
                   (get table owner))
        plan {:hosts hosts :provider provider :owner owner}]
    (reset! clj-leg plan)
    plan))

(defn- on-path?
  "True when `name` resolves as a command. Does not run it."
  [name]
  (boolean (fs/which name)))

(defn cohost-cache-root
  "$HOME/.cache/cohost, or the account home when HOME is unset.
   HOME wins over user.home so a redirected home stays inside that home."
  []
  (str (fs/file (or (some-> (System/getenv "HOME") str/trim not-empty)
                    (str (fs/home)))
                ".cache" "cohost")))

(defn bundle-dir
  "COHOST_CLJ_BUNDLE, otherwise <cache>/cohost-clj."
  []
  (or (some-> (System/getenv "COHOST_CLJ_BUNDLE") str/trim not-empty)
      (str (fs/file (cohost-cache-root) "cohost-clj"))))

(defn champaca-cache-dir
  "Where champaca keeps its startup cache. Created on launch."
  []
  (str (fs/file (cohost-cache-root) "champaca")))

(defn- bundle-manifest
  "The bundle's manifest.edn, or nil when it is absent or unreadable.
   build-clj-bundle.sh writes {:champaca-src \"champaca-src\"}."
  [b]
  (let [f (fs/file b "manifest.edn")]
    (when (fs/exists? f)
      (try (edn/read-string (slurp (str f)))
           (catch Exception _ nil)))))

(defn- def-vector-strings
  "Strings in the first vector of (def name) or (def ^:meta name).
   A docstring before the vector is not part of it. Only source paths."
  [text def-name]
  (when-let [[_ body] (re-find (re-pattern (str "(?s)\\(def(?:\\s+\\^:\\S+)?\\s+"
                                           def-name "\\b.*?\\[(.*?)\\]"))
                               (or text ""))]
    (->> (re-seq #"\"([^\"]+)\"" body)
         (map second)
         (filter #(re-find #"\.(?:ss|clj|cljc|jolt)$" %)))))

(defn- resource-literals
  "Paths passed as (io/resource \"...\") in text."
  [text]
  (->> (re-seq #"\(io/resource\s+\"([^\"]+)\"" (or text ""))
       (map second)))

(defn champaca-loaded-rels
  "Relative paths this tree loads from a jolt source root, read from the
   tree itself. champaca/api, cachedir.clj's runtime-files and
   translator-sources, and every (io/resource \"...\") literal. nil when
   champaca/api is not in dir. Does not add filenames the sources do not name."
  [dir]
  (let [api (first (filter #(fs/exists? (fs/file dir %))
                           ["champaca/api.jolt" "champaca/api.clj" "champaca/api.cljc"]))
        cachedir (fs/file dir "champaca/cachedir.clj")
        clj-files (when (fs/exists? (fs/file dir "champaca"))
                    (filter #(and (fs/regular-file? %)
                                  (re-find #"\.(?:clj|cljc|jolt)$" (str %)))
                            (fs/list-dir (fs/file dir "champaca"))))]
    (when api
      (vec (distinct
            (concat [api]
                    (when (fs/exists? cachedir)
                      (let [text (slurp (str cachedir))]
                        (concat (def-vector-strings text "runtime-files")
                                (def-vector-strings text "translator-sources"))))
                    (mapcat #(resource-literals (slurp (str %))) clj-files)))))))

(defn champaca-source-gap
  "nil when dir is a jolt source root whose own sources' loads are all
   present. Otherwise the missing relative paths, comma-separated.
   cachedir.clj is required because that is where runtime-files is read."
  [dir]
  (let [api (first (filter #(fs/exists? (fs/file dir %))
                           ["champaca/api.jolt" "champaca/api.clj" "champaca/api.cljc"]))]
    (cond
      (nil? api) "champaca/api.clj"
      (not (fs/exists? (fs/file dir "champaca/cachedir.clj"))) "champaca/cachedir.clj"
      :else (let [missing (remove #(fs/exists? (fs/file dir %)) (champaca-loaded-rels dir))]
              (when (seq missing) (str/join ", " missing))))))

(defn- missing-champaca-src-lines
  [bundle override dir]
  (let [gap (when dir (champaca-source-gap dir))
        rebuild ["  rebuild it: bash dev/build-clj-bundle.sh copies CHAMPACA_SRC's src and resources into the bundle's champaca-src and records that directory in manifest.edn."
                 "  or set COHOST_CHAMPACA_SRC to a directory that contains champaca/api.clj and champaca/cachedir.clj. The files init! loads are the ones that checkout names, not a fixed list."]]
    (cond
      override
      (into [(str "cohost: COHOST_CHAMPACA_SRC is " override
                  ", and it is not champaca's jolt source root"
                  (if gap (str " (missing " gap ")") "") ".")]
            rebuild)
      dir
      (into [(str "cohost: the bundle at " bundle " records champaca sources at " dir
                  (if gap (str ", and it is missing " gap) ", and champaca/api.clj is not there") ".")]
            rebuild)
      :else
      (into [(str "cohost: this jolt process's :clj leg is champaca, and the bundle at " bundle
                  " has no champaca jolt sources.")
             "  manifest.edn has no :champaca-src, and COHOST_CHAMPACA_SRC is unset."
             "  jolt -Scp needs that directory: (require 'champaca.api) looks on the source roots, and init! loads the files named by champaca/cachedir.clj."]
            rebuild))))

(defn resolve-champaca-src
  "The jolt source root for an in-process champaca :clj leg.
   COHOST_CHAMPACA_SRC wins. Otherwise manifest.edn's :champaca-src,
   relative to the bundle unless it is already absolute.
   -> {:dir path} or {:error [lines]}."
  []
  (let [override (some-> (System/getenv "COHOST_CHAMPACA_SRC") str/trim not-empty)
        b (bundle-dir)
        recorded (when-not override
                   (let [rel (:champaca-src (bundle-manifest b))]
                     (when (and (string? rel) (not (str/blank? rel)))
                       (str (if (fs/absolute? rel) (fs/file rel) (fs/file b rel))))))
        dir (or override recorded)]
    (if (and dir (nil? (champaca-source-gap dir)))
      {:dir (str dir)}
      {:error (missing-champaca-src-lines b override dir)})))

(defn- read-override
  "COHOST_CLJ_BODY -> nil, :clojure, :jolt, or an error map.
   Unset or blank is nil. Comparison is case-insensitive."
  []
  (let [raw (System/getenv "COHOST_CLJ_BODY")
        token (some-> raw str/trim)]
    (if (str/blank? token)
      nil
      (case (str/lower-case token)
        "clojure" :clojure
        "jolt" :jolt
        {:code 1
         :lines [(str "cohost: COHOST_CLJ_BODY must be clojure or jolt (got "
                      (pr-str raw) ").")]}))))

(defn select-clj-body
  "-> {:body :clojure|:jolt} or {:body :error :code n :lines [...]}."
  []
  (let [override (read-override)]
    (if (map? override)
      {:body :error :code (:code override) :lines (:lines override)}
      (let [clojure? (on-path? "clojure")
            jolt? (on-path? "jolt")]
        (cond
          (= :clojure override)
          (if clojure?
            {:body :clojure}
            {:body :error :code 1
             :lines ["cohost: COHOST_CLJ_BODY=clojure but `clojure` is not on PATH."]})

          (= :jolt override)
          (if jolt?
            {:body :jolt}
            {:body :error :code 1
             :lines ["cohost: COHOST_CLJ_BODY=jolt but `jolt` is not on PATH."]})

          clojure? {:body :clojure}
          jolt? {:body :jolt}
          :else {:body :error :code 1
                 :lines ["cohost: :clj runs on `clojure`, which is not on PATH."
                         "  jolt is not on PATH either, so the champaca backup is unavailable."]})))))

(defn clojure-only-lines
  "-T and -Sdescribe stay on the stock `clojure` CLI."
  [kind]
  (let [what (case kind
               :tool "-T runs on the `clojure` CLI only"
               :describe "-Sdescribe runs on the `clojure` CLI only")]
    [(str "cohost: " what ".")
     "  the champaca :clj leg does not run this command. Install `clojure` on PATH."]))

(defn- missing-bundle-lines [b repo]
  (let [script (if repo
                 (str (fs/file repo "dev/build-clj-bundle.sh"))
                 "dev/build-clj-bundle.sh")]
    [(str "cohost: the champaca bundle for the :clj leg is not at " b " (missing lib/champaca).")
     (str "  build it with jolt 0.8.26 and JDK 25: bash " script)
     "  CHAMPACA_SRC must be a champaca checkout with the per-loader class identity patch applied (see the script)."
     "  or set COHOST_CLJ_BUNDLE to a bundle directory you already built."]))

(defn- run-captured [cmd cwd]
  (try
    @(p/process (vec cmd) (cond-> {:out :string :err :string}
                            cwd (assoc :dir (str cwd))))
    (catch Exception e
      {:exit 127 :out "" :err (or (.getMessage e) "failed to run")})))

(defn- classpath-text
  "The classpath is the last non-blank line of jolt's stdout."
  [out]
  (str/trim (or (last (remove str/blank? (str/split-lines (or out "")))) "")))

(defn- jolt-spath
  "Project classpath from `jolt -Srepro -Spath`, plus alias flags (`-A`, `-M:`, `-X:`)."
  [cwd jolt-bin extra]
  (let [args (into ["-Srepro" "-Spath"] extra)
        shown (str/join " " (cons "jolt" args))
        {:keys [exit out err]} (run-captured (into [jolt-bin] args) cwd)
        cp (classpath-text out)]
    (cond
      (not (zero? (or exit 1)))
      {:error (into [(str "cohost: " shown " failed (exit " exit ").")]
                    (remove str/blank? [(str/trim (or err ""))]))}
      (str/blank? cp)
      {:error [(str "cohost: " shown " printed an empty classpath.")]}
      :else {:cp cp})))

(defn champaca-command
  "The lib/champaca invocation. -cp is \"$B/app/*\" plus the jolt classpath.
   main-args are what `clojure` would have received after the -M token."
  [bundle cp main-args]
  (let [b (str bundle)]
    (into [(str (fs/file b "lib/champaca"))
           "-cp" (str (fs/file b "app") "/*" path-sep cp)
           "clojure.main"]
          main-args)))

(defn launch-jolt-clj!
  "Run the :clj leg on the champaca bundle. -> {:code n} or {:code 1 :lines [...]}."
  [cwd main-args {:keys [repo jolt-args]}]
  (let [b (bundle-dir)
        champaca (fs/file b "lib/champaca")]
    (if-not (fs/exists? champaca)
      {:code 1 :lines (missing-bundle-lines b repo)}
      (let [jolt-bin (str (fs/which "jolt"))
            resolved (jolt-spath cwd jolt-bin jolt-args)]
        (if-let [err (:error resolved)]
          {:code 1 :lines err}
          (let [cache (champaca-cache-dir)
                cmd (champaca-command b (:cp resolved) main-args)]
            (fs/create-dirs cache)
            {:code (-> (p/process cmd
                                  {:dir (str cwd)
                                   :inherit true
                                   :extra-env {"JAVA_HOME" (str (fs/file b "runtime"))
                                               "CHAMPACA_BUNDLE" b
                                               "CHAMPACA_CACHE_DIR" cache
                                               "COHOST_HOME" (or repo "")}})
                       deref :exit)}))))))
