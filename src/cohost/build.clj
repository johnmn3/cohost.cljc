;; cohost.build: tools.build tasks for a cohost project (JVM side only: the
;; project's build.clj requires it, `cohost -T:build <task>` runs it, exactly
;; as `clojure -T:build <task>` does). doc/CLI.md, "Building".
;;
;;   (ns build (:require [cohost.build :as cb]))
;;   (defn build [opts] (cb/build (merge {:main 'acme.myapp} opts)))
;;
;; The target is :target in opts, else :cohost/target in deps.edn, else :jvm:
;; Every artifact is a directory target/<name>/ whose `run args...` calls the
;; main's -main from any directory; the task prints `build: target/<name>/run`.
;;   :jvm   target/<name>/<name>.jar, an uberjar with clojure.main as its main
;;          (no :gen-class in a namespace every target reads); run: java -jar
;;   :cljs  target/<name>/: cohost's ClojureScript host, the stock core
;;          sources, the project's and its dependencies' source roots, and
;;          run.js (run: node run.js)
;;   :jolt  target/<name>/: cohost.cli compiled by `jolt build` into one native
;;          binary, the stock sources, the source roots (no jolt needed)
;;   :cljw :lg :lpy :phel  target/<name>/: a script bundle -- cohost's
;;          sources, the stock sources, the source roots, and `run`, on the
;;          host's installed binary (their native packagers refuse cohost's
;;          code: doc/CLI.md, "Building")
;;   :jank  target/<name>/run: one native executable (cohost.build-jank, lane
;;          D's D-7): the booted core's snapshot plus the project's namespaces,
;;          compiled -O3, linked by jank's static runtime; no cohost checkout
;;          to run it; rebuilt when stale (its build state: target/<name>/cohost-build)
;;   other  "build: not yet on <target>"
(ns cohost.build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- cohost-home
  "The cohost checkout: COHOST_HOME (bin/cohost exports it), else the
   directory this file was loaded from."
  []
  (or (System/getenv "COHOST_HOME")
      (some-> (io/resource "cohost/build.clj") io/file .getParentFile .getParentFile .getParentFile str)))

(defn- project-target [opts]
  (let [f (io/file "deps.edn")
        t (or (:target opts)
              (when (.exists f) (:cohost/target (edn/read-string {:default tagged-literal} (slurp f))))
              :jvm)]
    (keyword (str/replace (name t) #"^:" ""))))

(defn- build-api [sym] (requiring-resolve (symbol "clojure.tools.build.api" (name sym))))

(declare write-run!)

(defn- jvm-uber [{:keys [main name] :as _opts}]
  (let [basis ((build-api 'create-basis) {:project "deps.edn"})
        class-dir "target/classes"
        jar (str "target/" name "/" name ".jar")]
    ((build-api 'delete) {:path "target"})
    ((build-api 'copy-dir) {:src-dirs (:paths basis) :target-dir class-dir})
    ((build-api 'compile-clj) {:basis basis :class-dir class-dir :ns-compile [main]})
    ((build-api 'uber) {:class-dir class-dir :uber-file jar :basis basis :main 'clojure.main})
    ((build-api 'delete) {:path class-dir})
    (write-run! (str "target/" name)
                (str "#!/bin/sh\n# run [args...]: " main "'s -main (made by cohost.build)\n"
                     "exec java -jar \"$(cd \"$(dirname \"$0\")\" && pwd)/" name ".jar\" -m " main " \"$@\"\n"))))

(defn- sh! [dir & cmd]
  (let [r (apply sh/sh (concat cmd [:dir dir]))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "build: " (str/join " " cmd) " failed") {:out (:out r) :err (:err r)})))
    (:out r)))

(defn- copy-tree! [from to]
  (doseq [f (file-seq (io/file from)) :when (.isFile f)]
    (let [rel (subs (.getPath f) (inc (count (.getPath (io/file from)))))
          out (io/file to rel)]
      (io/make-parents out)
      (io/copy f out))))

(def ^:private run-js
  "// run.js: node run.js args... -> the project's -main on cohost's ClojureScript host
const fs = require('fs'), os = require('os'), path = require('path'), cp = require('child_process');
const here = __dirname;
const meta = JSON.parse(fs.readFileSync(path.join(here, 'bundle.json'), 'utf8'));
const s = x => JSON.stringify(x);
const edn = '{:stock-root ' + s(path.join(here, 'stock')) +
  ' :roots [' + meta.roots.map(r => s(path.join(here, r))).join(' ') + ']' +
  ' :cwd ' + s(process.cwd()) + ' :inits []' +
  ' :main {:type :main :ns ' + s(meta.main) + ' :args [' + process.argv.slice(2).map(s).join(' ') + ']}}';
const cfg = path.join(os.tmpdir(), 'cohost-run-' + process.pid + '.edn');
fs.writeFileSync(cfg, edn);
const r = cp.spawnSync('node', [path.join('target', 'cljs-cli', 'main.js'), cfg], {cwd: here, stdio: 'inherit'});
fs.unlinkSync(cfg);
process.exit(r.status === null ? 1 : r.status);
")

(defn- cljs-bundle [{:keys [main name]}]
  (let [home (cohost-home)
        out (io/file "target" name)
        ;; the source roots, resolved as `cohost :cljs` resolves them (jars extracted)
        roots (str/split (str/trim (sh! "." (str home "/bin/cohost") ":cljs" "-Spath")) #":")
        ;; cohost's ClojureScript host build: `cohost :cljs` builds it when stale
        _ (sh! "." (str home "/bin/cohost") ":cljs" "-e" "nil")]
    (sh! "." "rm" "-rf" (str out))
    (copy-tree! (str home "/target/cljs-cli") (io/file out "target/cljs-cli"))
    (copy-tree! (str home "/resources/cohost/clojure-1.12.0") (io/file out "stock"))
    (let [names (map-indexed (fn [i r] (str "roots/" i "-" (.getName (io/file r)))) roots)]
      (doseq [[r n] (map vector roots names)] (copy-tree! r (io/file out n)))
      (spit (io/file out "bundle.json")
            (str "{\"main\": " (pr-str (str main)) ", \"roots\": [" (str/join ", " (map pr-str names)) "]}")))
    (spit (io/file out "run.js") run-js)
    (write-run! out "#!/bin/sh\n# run [args...] (made by cohost.build)\nexec node \"$(cd \"$(dirname \"$0\")\" && pwd)/run.js\" \"$@\"\n")))

(defn- bundle-sources!
  "Copy the stock sources and the project's source roots (as `cohost :<t>
   -Spath` resolves them: jars extracted) into out; -> the roots' paths
   relative to out."
  [home t out]
  (let [roots (str/split (str/trim (sh! "." (str home "/bin/cohost") (str ":" (name t)) "-Spath")) #":")
        names (map-indexed (fn [i r] (str "roots/" i "-" (.getName (io/file r)))) roots)]
    (copy-tree! (str home "/resources/cohost/clojure-1.12.0") (io/file out "stock"))
    (doseq [[r n] (map vector roots names)] (copy-tree! r (io/file out n)))
    names))

(defn- launcher
  "The bundle's `run`: writes the config cohost.cli reads (absolute paths
   from its own directory, the arguments as EDN strings) and runs launch,
   a shell command line ending in the config path."
  [main roots launch]
  (str "#!/bin/sh\n"
       "# run [args...]: " main "'s -main on cohost (made by cohost.build)\n"
       "here=$(cd \"$(dirname \"$0\")\" && pwd)\n"
       "q() { printf '\"%s\"' \"$(printf '%s' \"$1\" | sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g')\"; }\n"
       "args=''; for a in \"$@\"; do args=\"$args $(q \"$a\")\"; done\n"
       "roots=''; for r in " (str/join " " roots) "; do roots=\"$roots $(q \"$here/$r\")\"; done\n"
       "cfg=$(mktemp \"${TMPDIR:-/tmp}/cohost-run-XXXXXX\")\n"
       "printf '{:stock-root %s :roots [%s] :cwd %s :inits [] :main {:type :main :ns %s :args [%s]}}' "
       "\"$(q \"$here/stock\")\" \"$roots\" \"$(q \"$PWD\")\" \"$(q " (pr-str (str main)) ")\" \"$args\" > \"$cfg\"\n"
       launch " \"$cfg\"\n"
       "code=$?\nrm -f \"$cfg\"\nexit $code\n"))

(defn- write-run! [out text]
  (let [f (io/file out "run")]
    (spit f text)
    (.setExecutable f true false)
    (str f)))

(def ^:private script-launch
  ;; as bin/cohost launches each host, from the bundle's copy of cohost
  {:cljw "cljw -cp \"$here/cohost/src\" -m cohost.cli"
   :lg "lg -source-paths \"$here/cohost/src\" \"$here/cohost/dev/lg_cli.lg\""
   :lpy "basilisp run -p \"$here/cohost/src\" -c \"(require 'cohost.cli) (cohost.cli/-main \\\"$cfg\\\")\" #"
   ;; Phel reads phel-config.php from its working directory; cohost.cli moves to :cwd
   :phel "cd \"$here/cohost\" && phel run dev/phel_cli.phel"})

(defn- script-bundle [t {:keys [main name]}]
  (let [home (cohost-home)
        out (io/file "target" name)]
    (sh! "." "rm" "-rf" (str out))
    (let [roots (bundle-sources! home t out)]
      (copy-tree! (str home "/src") (io/file out "cohost/src"))
      (doseq [f ["dev/lg_cli.lg" "dev/phel_cli.phel" "phel-config.php"]]
        (io/make-parents (io/file out "cohost" f))
        (io/copy (io/file home f) (io/file out "cohost" f)))
      (write-run! out (launcher main roots (script-launch t))))))

(defn- jolt-native [{:keys [main name]}]
  (let [home (cohost-home)
        out (io/file "target" name)
        work (io/file "target" "cohost-jolt-build")]
    (sh! "." "rm" "-rf" (str out) (str work))
    (.mkdirs work)
    ;; jolt build reads deps.edn :paths (its -Scp is not honored by build)
    (spit (io/file work "deps.edn") (pr-str {:paths [(str home "/src")]}))
    (sh! (str work) "jolt" "build" "-m" "cohost.cli" "-o" "cohost-jolt")
    (let [roots (bundle-sources! home :jolt out)]
      (io/make-parents (io/file out "bin" "cohost-jolt"))
      (io/copy (io/file work "cohost-jolt") (io/file out "bin" "cohost-jolt"))
      (.setExecutable (io/file out "bin" "cohost-jolt") true false)
      (write-run! out (launcher main roots "\"$here/bin/cohost-jolt\"")))
    (sh! "." "rm" "-rf" (str work))
    (str out "/run")))

(defn- jank-exe [{:keys [main name]}]
  (let [home (cohost-home)
        ;; the source roots, resolved as `cohost :jank` resolves them (jars extracted)
        roots (str/split (str/trim (sh! "." (str home "/bin/cohost") ":jank" "-Spath")) #":")
        build! (requiring-resolve 'cohost.build-jank/build!)]
    (build! {:repo home :project (.getCanonicalPath (io/file ".")) :roots roots
             :main main :name "run" :target (str "target/" name)})
    (str "target/" name "/run")))

(defn build
  "Build the project for its target. opts: :main (the -main namespace, a
   symbol), :name (default: the directory's name), :target (default:
   :cohost/target in deps.edn, else :jvm)."
  [opts]
  (let [opts (merge {:name (.getName (.getCanonicalFile (io/file ".")))} opts)
        t (project-target opts)]
    (when-not (:main opts) (throw (ex-info "build: :main (the -main namespace) is required" {})))
    (let [run (case t
      :jvm (jvm-uber opts)
      :cljs (cljs-bundle opts)
      :jolt (jolt-native opts)
      (:cljw :lg :lpy :phel) (script-bundle t opts)
      :jank (jank-exe opts)
      (do (println (str "build: not yet on " t))
          (throw (ex-info (str "build: not yet on " t) {:target t}))))]
      ;; every target's artifact: target/<name>/run args... runs -main
      (println (str "build: " run))
      run)))
