;; cohost.tool — the `cohost` command for a Jank-only install: bin/cohost's
;; front-end, ported to raw jank (dev/install/lib/cohost/install/shim.cljc
;; is its only library). The AOT snapshot is built and checked by
;; cohost.install.jank-build (dev/install/jank_build.cljc); the snapshot
;; itself is stock cohost, entered through cohost.cli exactly as
;; bin/cohost enters it.
;;
;; What runs where (the install README says the same):
;;   - the default :jank target and every non-tool command run on the
;;     compiled snapshot (the launcher cohost-jank when it was built,
;;     the snapshot modules via run-main otherwise) — no Clojure CLI,
;;     no Babashka involved, ever;
;;   - -Ttools install / install-latest is native (below): the git and
;;     Maven mechanics of tools.tools, byte-modeled on the reference
;;     Clojure CLI's observable behavior;
;;   - any other -T alias, :jvm, and -Sdescribe delegate to the real
;;     Clojure CLI when one is on the path (as bin/cohost does) and
;;     otherwise stop with the gap named — those seats are the Clojure
;;     CLI's own, and V1 of the jank tool does not grow them;
;;   - other hosts (:bb, :clj-kondo, ...) stop with a pointer to
;;     bin/cohost, which serves them from a checkout with bb.
;;
;; The repo is found via $COHOST_HOME, else :repo in
;; ~/.config/cohost/install.edn (the installer writes it).
(ns cohost.tool
  (:require [cohost.install.shim :as shim]
            [clojure.string :as str]))

;; ------------------------------------------------------------ die / io

(defn die [& parts]
  (binding [*out* *err*] (println (apply str parts)))
  (throw (ex-info "cohost: stopped" {:cohost/exit 1})))

(def ^:dynamic *exit* (fn [code] (throw (ex-info "cohost: exit" {:cohost/exit code}))))

(defn run-child!
  "Run cmd inheriting stdio (the shim's spawns all inherit; env additions
   are exported into this process first, as a launcher would)."
  [env-map cmd]
  (doseq [[k v] env-map] (shim/set-env k v))
  (let [rc (shim/sh! cmd)]
    ((*exit*) rc)))

;; ------------------------------------------------------------ install record

(defn install-edn []
  (let [f (shim/path (shim/home) ".config/cohost/install.edn")]
    (when (shim/regular-file? f) (shim/read-edn-file f))))

(def repo
  (delay
    (let [r (or (shim/env "COHOST_HOME") (:repo (install-edn)))]
      (when-not (and r (shim/regular-file? (shim/path r "src/cohost/cli.cljc")))
        (die "cohost: cannot find the cohost repo.\n"
             "  Set COHOST_HOME to the checkout, or run the installer\n"
             "  (it records the repo in ~/.config/cohost/install.edn)."))
      r)))

(def jank-bin
  (delay (or (:jank (install-edn)) (shim/which "jank") (shim/self-exe) "jank")))

(defn gc-env
  "dev/perf/jank_gc_env.clj, ported: the snapshot heap the launcher and
   run-main boots run with."
  []
  (let [mode (shim/env "COHOST_JANK_GC")]
    (cond (= "none" mode) {}
          (and mode (not (str/blank? mode))) {"GC_INITIAL_HEAP_SIZE" mode}
          :else (let [total (shim/capture (str "getconf _PHYS_PAGES 2>/dev/null; getconf _PAGE_SIZE 2>/dev/null"))]
                  (if (re-find #"\d" (or total ""))
                    (let [[pages size] (mapv parse-long (str/split-lines (str/trim total)))
                          gb (when (and pages size) (quot (* pages size) 1073741824))]
                      (if (and gb (>= gb 2))
                        (do (binding [*out* *err*]
                              (println "cohost: this host has" gb "GB RAM; capping the jank snapshot heap at 1G (COHOST_JANK_GC=<size> or =none overrides)"))
                            {"GC_INITIAL_HEAP_SIZE" "1G"})
                        {"GC_INITIAL_HEAP_SIZE" "512M"}))
                    {"GC_INITIAL_HEAP_SIZE" "512M"})))))

;; ------------------------------------------------------------ build check

(defn build-lines
  "`jank_build.cljc -- fresh` in the repo: its stdout lines."
  []
  (let [out (shim/capture (str "cd " (shim/q @repo)
                               " && " (shim/q @jank-bin) " --module-path dev/install/lib run dev/install/jank_build.cljc -- fresh"))]
    (str/split-lines out)))

(defn ensure-build!
  "`jank_build.cljc -- ensure` in the repo, inheriting stdio."
  []
  (let [rc (shim/sh! (str "cd " (shim/q @repo)
                          " && exec " (shim/q @jank-bin) " --module-path dev/install/lib run dev/install/jank_build.cljc -- ensure"))]
    (when-not (zero? rc) (die "cohost: the snapshot build failed (" rc ")"))))

(defn fresh-build []
  (let [lines (build-lines)]
    (if (some #(str/starts-with? % "STALE") lines)
      nil
      {:path (some #(when (str/starts-with? % "PATH ") (subs % 5)) lines)
       :launcher (some #(when (str/starts-with? % "LAUNCHER ") (subs % 9)) lines)})))

;; ------------------------------------------------------------ deps subset
;;
;; A documented subset of tools.deps, enough for projects whose deps are
;; directories, git checkouts and Maven jars already resolvable from the
;; local caches (anything missing is fetched from Maven Central / Clojars
;; by curl). org.clojure/clojure is always dropped: the booted core IS
;; Clojure 1.12.0.

(def clj-config-dir
  (delay (or (shim/env "CLJ_CONFIG") (shim/path (shim/home) ".config/clojure"))))

(def gitlibs-dir
  (delay (or (shim/env "GITLIBS") (shim/path (shim/home) ".gitlibs"))))

(def m2-dir (delay (shim/path (shim/home) ".m2/repository")))

(def tools-version "0.3.8")
(def tools-sha "fd43f6e9b35cbc45c4615a09a89ea7840a0d461a")

(defn read-edn-or [f default]
  (if (shim/regular-file? f) (or (shim/read-edn-file f) default) default))

(defn alias-kw [s] (keyword (if (str/starts-with? s ":") (subs s 1) s)))

(defn aliases-in [deps]
  (into {} (map (fn [[k v]] [(alias-kw (name k)) v])) (:aliases deps)))

(declare maven-jar! gitlibs-lib-dir)

(defn existing-paths
  "Resolve a deps :paths vector under base: literals that are dirs,
   :paths-alias keywords into the alias's :extra-paths, the :default-deps
   Clojure paths."
  [base deps aliases paths]
  (vec
   (mapcat (fn [p]
             (cond (string? p) (let [d (if (shim/absolute? p) p (shim/path base p))]
                                 (when (shim/directory? d) [d]))
                   (keyword? p) (existing-paths base deps aliases (:extra-paths (get aliases (alias-kw (name p)))))
                   :else []))
           paths)))

(defn dep-dirs
  "The classpath dirs/jars a deps map contributes under base, resolving
   :local/root, :git/url (a gitlibs checkout must exist — the -T
   installer creates them), and Maven coordinates (the jar in ~/.m2,
   fetched by curl when absent)."
  [base deps]
  (vec
   (mapcat
    (fn [[lib coord]]
      (cond (:local/root coord)
            (let [root (let [r (:local/root coord)] (if (shim/absolute? r) r (shim/path base r)))
                  sub (read-edn-or (shim/path root "deps.edn") {})
                  paths (or (:paths sub) ["src"])]
              (concat (existing-paths root sub (aliases-in sub) paths)
                      (dep-dirs root (dissoc (:deps sub) 'org.clojure/clojure))))
            (:git/url coord)
            (let [dir (gitlibs-lib-dir lib coord)]
              (if (shim/directory? dir)
                (let [sub (read-edn-or (shim/path dir "deps.edn") {})
                      paths (or (:paths sub) ["src"])]
                  (concat (existing-paths dir sub (aliases-in sub) paths)
                          (dep-dirs dir (dissoc (:deps sub) 'org.clojure/clojure))))
                (die "cohost: git dep " lib " is not in " @gitlibs-dir " yet.\n"
                     "  Install it first: cohost -Ttools install-latest :lib " lib " :as <name>,"
                     " or clone it into the gitlibs layout.")))
            (:mvn/version coord)
            [(maven-jar! lib (:mvn/version coord) (:exclusions coord))]
            :else []))
    deps)))

(defn maven-jar!
  "The jar for lib/version in ~/.m2, curl-fetching it (and its pom's
   runtime deps are NOT chased in V1 — named in the README's subset)
   from Maven Central, then Clojars."
  [lib version _exclusions]
  (let [g (namespace lib) a (name lib)
        jar (shim/path @m2-dir (str/replace g "." "/") a version (str a "-" version ".jar"))]
    (when-not (shim/regular-file? jar)
      (shim/mkdir-p (shim/parent jar))
      (let [base (str "https://repo1.maven.org/maven2/" (str/replace g "." "/") "/" a "/" version "/" a "-" version ".jar")
            alt (str "https://repo.clojars.org/" g "/" a "/" version "/" a "-" version ".jar")]
        (when-not (or (zero? (shim/sh! (str "curl -fsSL -o " (shim/q jar) " " (shim/q base))))
                      (zero? (shim/sh! (str "curl -fsSL -o " (shim/q jar) " " (shim/q alt)))))
          (die "cohost: cannot fetch Maven jar " lib " " version " from Central or Clojars."))))
    jar))

(defn gitlibs-lib-dir [lib coord]
  (let [url (:git/url coord)
        host-path (str/replace (str/replace url #"^https?://" "") #"\\.git$" "")
        sha (or (:git/sha coord) "")]
    (shim/path @gitlibs-dir "libs" (str lib) sha)))

;; ------------------------------------------------------------ -Ttools

(defn newest-git-tag
  "The newest version tag of the mirror at dir, as [tag full-sha]."
  [dir]
  (let [tag (shim/capture-trim (str "git -C " (shim/q dir) " tag --sort=-v:refname | head -1"))]
    (when (str/blank? tag) (die "cohost: no version tags found in " dir))
    [tag (shim/capture-trim (str "git -C " (shim/q dir) " rev-parse " (shim/q (str tag "^{commit}"))))]))

(defn short-sha [sha] (subs sha 0 (min 7 (count sha))))

(defn git-host-path [url]
  (-> url (str/replace #"^https?://" "") (str/replace #"\\.git$" "") (str/replace #":.*$" "")))

(defn tool-record-path [as] (shim/path @clj-config-dir "tools" (str (name as) ".edn")))

(defn write-tool-record! [as lib tag sha]
  (shim/mkdir-p (shim/path @clj-config-dir "tools"))
  (spit (tool-record-path as)
        (str "{:lib " lib ", :coord {:git/tag \"" tag "\", :git/sha \"" (short-sha sha) "\"}}\n")))

(defn ensure-tools-self-record! []
  (let [f (shim/path @clj-config-dir "tools/tools.edn")]
    (when-not (shim/regular-file? f)
      (shim/mkdir-p (shim/parent f))
      (spit f (str "{:lib io.github.clojure/tools.tools, :coord {:git/tag \"v" tools-version "\", :git/sha \"" tools-sha "\"}}\n")))))

(defn ensure-user-deps! []
  (let [f (shim/path @clj-config-dir "deps.edn")]
    (when-not (shim/regular-file? f)
      (shim/mkdir-p @clj-config-dir)
      (spit f "{:aliases {}}\n"))))

(defn existing-tool-tag [as lib]
  (let [f (tool-record-path as)]
    (when (shim/regular-file? f)
      (let [rec (shim/read-edn-file f)]
        (when (= lib (:lib rec)) (get-in rec [:coord :git/tag]))))))

(defn install-git-tool!
  "tools.tools' install for a github lib, as the reference probe
   recorded it: mirror-clone into $GITLIBS/_repos, check the newest
   tag out into $GITLIBS/libs/<lib>/<full-sha>, write the tool record."
  [lib as latest?]
  (let [slug (str (namespace lib) "/" (name lib))
        url (str "https://github.com/" slug ".git")
        mirror (shim/path @gitlibs-dir "_repos" (git-host-path url))]
    (when-not (shim/which "git") (die "cohost: -Ttools install needs git on the path."))
    (if (shim/directory? (shim/path mirror ".git"))
      (shim/sh-checked! (str "git -C " (shim/q mirror) " fetch --tags --quiet origin"))
      (do (shim/mkdir-p (shim/parent mirror))
          (shim/sh-checked! (str "git clone --quiet --mirror " (shim/q url) " " (shim/q mirror)))))
    (let [[tag sha] (newest-git-tag mirror)
          current (existing-tool-tag as lib)]
      (if (and latest? (= tag current))
        (println (str (name as) ": Skipping, newest installed " lib " " tag))
        (let [dir (shim/path @gitlibs-dir "libs" (str lib) sha)]
          (when-not (shim/directory? dir)
            (shim/mkdir-p dir)
            (shim/sh-checked! (str "git -C " (shim/q mirror) " archive " (shim/q sha) " | tar -x -C " (shim/q dir))))
          (write-tool-record! as lib tag sha)
          (ensure-tools-self-record!)
          (ensure-user-deps!)
          (println (str (name as) ": Installed " lib " " tag " as " as)))))))

(defn install-maven-tool! [lib as]
  (let [meta (shim/capture (str "curl -fsSL https://repo1.maven.org/maven2/"
                                (str/replace (namespace lib) "." "/") "/" (name lib) "/maven-metadata.xml"))
        version (second (re-find #"<release>([^<]+)</release>" (or meta "")))]
    (when-not version (die "cohost: cannot read the latest version of " lib " from Maven Central."))
    (maven-jar! lib version nil)
    (shim/mkdir-p (shim/path @clj-config-dir "tools"))
    (spit (tool-record-path as)
          (str "{:lib " lib ", :coord {:mvn/version \"" version "\"}}\n"))
    (ensure-tools-self-record!)
    (ensure-user-deps!)
    (println (str (name as) ": Installed " lib " " version " as " as))))

(defn tools-command!
  "cohost -Ttools install|install-latest :lib <lib> :as <name> — native."
  [args]
  (let [sub (first args)
        kvs (into {} (map (fn [[k v]] [(keyword (subs k 1)) v]))
                  (partition 2 (rest args)))
        lib (some-> (get kvs :lib) symbol) as (some-> (get kvs :as) keyword)]
    (when-not (and (#{"install" "install-latest"} sub) lib as)
      (die "usage: cohost -Ttools install-latest :lib <lib> :as <name>"))
    (cond (str/starts-with? (str lib) "io.github.")
          (install-git-tool! lib as (= sub "install-latest"))
          (namespace lib)
          (install-maven-tool! lib as)
          :else (die "cohost: -Ttools install: cannot tell how to install " lib
                     " (expected an io.github.* git lib or a Maven lib)."))))

;; ------------------------------------------------------------ classpath

(defn config-chain
  "user deps + project deps (+ the -Sdeps map merged last), bin/cohost's
   chain minus the install-root config (empty on this seat)."
  [projdir sdeps]
  (let [user (read-edn-or (shim/path @clj-config-dir "deps.edn") {})
        proj (read-edn-or (shim/path projdir "deps.edn") {})]
    {:deps (merge (dissoc (:deps user) 'org.clojure/clojure)
                  (dissoc (:deps proj) 'org.clojure/clojure)
                  (dissoc (:deps sdeps) 'org.clojure/clojure))
     :aliases (merge (aliases-in user) (aliases-in proj) (aliases-in sdeps))
     :proj proj}))

(defn parse-main-args
  "bin/cohost's parse-main-args, subset: -Sdeps, -M/-X alias lists, -m,
   file loads, -Spath. Everything else passes through to the booted
   cohost.cli as :args."
  [args]
  (loop [args (seq args) r {:remaining [] :loads [] :aliases []}]
    (if-let [a (first args)]
      (cond (= a "-Sdeps") (recur (nnext args) (assoc r :sdeps (read-string (second args))))
            (= a "-Spath") (recur (next args) (assoc r :spath true))
            (or (str/starts-with? a "-M") (str/starts-with? a "-X"))
            (recur (next args) (-> r (assoc :mode (if (str/starts-with? a "-X") :exec :main))
                                     (update :aliases into (map alias-kw (remove str/blank? (str/split (subs a 2) #":"))))))
            (= a "-m") (recur (nnext args) (assoc r :main-ns (second args)))
            (and (or (str/ends-with? a ".clj") (str/ends-with? a ".cljc"))
                 (shim/regular-file? a))
            (recur (next args) (update r :loads conj (shim/absolutize a)))
            :else (recur (next args) (update r :remaining conj a)))
      r)))

(defn ns->path [ns-name] (str/replace (str/replace (str ns-name) "-" "_") "." "/"))

(defn project-classpath
  "The project's sourcepath + dep dirs under the subset rules."
  [projdir chain alias-names]
  (let [aliases (:aliases chain)
        proj (:proj chain)
        base-paths (existing-paths projdir proj aliases (or (:paths proj) ["src" "resources"]))
        extra-paths (mapcat (fn [al] (existing-paths projdir proj aliases (:extra-paths (get aliases al)))) alias-names)
        extra-deps (apply merge (map (fn [al] (:extra-deps (get aliases al))) alias-names))
        deps (merge (:deps chain) (dissoc extra-deps 'org.clojure/clojure))]
    (vec (concat base-paths extra-paths (dep-dirs projdir deps)))))

(defn default-roots []
  (vec (concat [@gitlibs-dir (shim/path @gitlibs-dir "libs") @m2-dir @repo]
               (when-let [cr (shim/env "COHOST_ROOTS")] (remove str/blank? (str/split cr #":"))))))

(defn cache-file [roots]
  (let [dir (shim/path (or (shim/env "XDG_CACHE_HOME") (shim/path (shim/home) ".cache")) "cohost/cli")]
    (shim/mkdir-p dir)
    (shim/path dir (str (subs (shim/sha256-hex (pr-str roots)) 0 40) ".edn"))))

(defn run-snapshot!
  "Boot the snapshot for a project invocation (bin/cohost's jank path,
   with the build ensured first)."
  [args]
  (let [parsed (parse-main-args args)
        projdir (shim/cwd)
        chain (config-chain projdir (:sdeps parsed))
        aliases (:aliases chain)
        exec-fn (some (fn [al] (:exec-fn (get aliases al))) (:aliases parsed))
        load (vec (concat (:loads parsed)
                          (when (:main-ns parsed) [(ns->path (:main-ns parsed))])
                          (when exec-fn [(ns->path (namespace exec-fn))])))
        cp (project-classpath projdir chain (:aliases parsed))
        roots (default-roots)]
    (if (:spath parsed)
      (do (println (str/join ":" (concat [(shim/path @repo "src") (shim/path @repo "resources")] cp)))
          ((*exit*) 0))
      (let [fresh (fresh-build)]
        (when-not fresh (ensure-build!))
        (let [fresh (or fresh (fresh-build))
              cfg {:roots roots :paths cp :load load :args (:remaining parsed)}
              cfg-file (cache-file roots)]
          (spit cfg-file (pr-str cfg))
          (let [env (merge (gc-env) {"COHOST_HOME" @repo})]
            (if (:launcher fresh)
              (run-child! env (str (shim/q (:launcher fresh)) " " (shim/q (shim/absolutize cfg-file))))
              (run-child! env (str (shim/q @jank-bin) " --module-path " (shim/q (:path fresh))
                                   " run-main cohost.cli -- " (shim/q (shim/absolutize cfg-file)))))))))))

;; ------------------------------------------------------------ delegation

(defn installed-tools []
  (let [dir (shim/path @clj-config-dir "tools")]
    (if (shim/directory? dir)
      (into {}
            (comp (filter #(str/ends-with? % ".edn"))
                  (map (fn [f] [(keyword (subs (shim/file-name f) 0 (- (count (shim/file-name f)) 4)))
                                (shim/read-edn-file f)])))
            (shim/walk-files dir))
      {})))

(defn with-templates
  "bin/cohost's with-templates, inlined: when any tool records exist,
   prepend a -Sdeps whose :aliases carry each tool as a :replace-deps
   alias, so the delegated Clojure CLI resolves them from gitlibs."
  [args]
  (let [tools (installed-tools)]
    (if (empty? tools)
      args
      (let [merged (into {} (map (fn [[as {:keys [lib coord]}]] [as {:replace-deps {lib coord}}])) tools)]
        (into ["-Sdeps" (pr-str {:aliases merged})] args)))))

(defn delegate-clojure!
  "Run the real Clojure CLI with templates merged, as bin/cohost does;
   without one on the path, name the gap and stop."
  [args context]
  (if-let [clj (shim/which "clojure")]
    (let [argv (into [clj "-Sdeps" "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}}}"] (with-templates args))]
      (run-child! {} (str/join " " (map shim/q argv))))
    (die "cohost: " context " runs on the Clojure CLI in this install, and no clojure is on the path.\n"
         "  Native here: the default jank target and -Ttools install.\n"
         "  For the rest, install the Clojure CLI or use bin/cohost from a checkout.")))

(defn tool-main [argv]
  (let [[target args]
        ;; bin/cohost's three selector shapes
        (cond (and (first argv) (str/starts-with? (first argv) ":") (not (str/starts-with? (first argv) "::")))
              [(keyword (subs (first argv) 1)) (rest argv)]
              (and (first argv) (= (first argv) "-H"))
              [(keyword (second argv)) (nnext argv)]
              :else [:default argv])
        args (vec args)]
    (cond
      ;; -Ttools is native; other -T aliases delegate or name the gap
      (some #(str/starts-with? % "-T") args)
      (let [t (first (filter #(str/starts-with? % "-T") args))
            alias (keyword (subs t 2))]
        (if (= alias :tools)
          (tools-command! (vec (rest (drop-while #(not (str/starts-with? % "-T")) args))))
          (delegate-clojure! args (str "-T" (name alias)))))
      (= target :jvm) (delegate-clojure! args "the :jvm target")
      (some #{"-Sdescribe"} args) (delegate-clojure! args "-Sdescribe")
      (and (not= target :default) (not= target :jank))
      (die "cohost: the " (name target) " target is served by bin/cohost from a checkout"
           " (this install's tool is the jank seat). Run: bb bin/cohost " (name target) " ...")
      :else (run-snapshot! args))))

(defn -main [& argv]
  (try (tool-main (vec argv))
       (catch :default e
         ;; ex-data/ex-message are guarded: an exception raised inside
         ;; a delay's body can arrive here in a shape they reject.
         (let [data (try (ex-data e) (catch :default _ nil))
               code (:cohost/exit data)]
           (if code
             (shim/exit! code)
             (do (binding [*out* *err*]
                   (println "cohost:" (or (try (ex-message e) (catch :default _ nil)) "stopped")))
                 (shim/exit! 1)))))))
