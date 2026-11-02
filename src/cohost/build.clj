;; cohost.build: tools.build tasks for a cohost project (JVM side only: the
;; project's build.clj requires it, `cohost -T:build <task>` runs it, exactly
;; as `clojure -T:build <task>` does). doc/CLI.md, "Building".
;;
;;   (ns build (:require [cohost.build :as cb]))
;;   (defn build [opts] (cb/build (merge {:main 'acme.myapp} opts)))
;;
;; The target is :target in opts, else :cohost/target in deps.edn, else :jvm:
;;   :jvm   an uberjar, target/<name>.jar, clojure.main as its main:
;;          java -jar target/<name>.jar -m <main> args... (no :gen-class needed
;;          in a namespace every target reads)
;;   :cljs  target/<name>-cljs/: cohost's ClojureScript host, the stock core
;;          sources, the project's and its dependencies' source roots, and
;;          run.js (node target/<name>-cljs/run.js args... runs -main)
;;   other  "build: not yet on <target>" (jank: lane D's D-7)
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

(defn- jvm-uber [{:keys [main name] :as _opts}]
  (let [basis ((build-api 'create-basis) {:project "deps.edn"})
        class-dir "target/classes"
        jar (str "target/" name ".jar")]
    ((build-api 'delete) {:path "target"})
    ((build-api 'copy-dir) {:src-dirs (:paths basis) :target-dir class-dir})
    ((build-api 'compile-clj) {:basis basis :class-dir class-dir :ns-compile [main]})
    ((build-api 'uber) {:class-dir class-dir :uber-file jar :basis basis :main 'clojure.main})
    (println (str "build: " jar " (java -jar " jar " -m " main ")"))
    jar))

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
        out (io/file "target" (str name "-cljs"))
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
    (println (str "build: " out "/ (node " out "/run.js args...)"))
    (str out)))

(defn build
  "Build the project for its target. opts: :main (the -main namespace, a
   symbol), :name (default: the directory's name), :target (default:
   :cohost/target in deps.edn, else :jvm)."
  [opts]
  (let [opts (merge {:name (.getName (.getCanonicalFile (io/file ".")))} opts)
        t (project-target opts)]
    (when-not (:main opts) (throw (ex-info "build: :main (the -main namespace) is required" {})))
    (case t
      :jvm (jvm-uber opts)
      :cljs (cljs-bundle opts)
      (do (println (str "build: not yet on " t
                        (when (= t :jank) " (lane D's D-7: an AOT executable)")))
          (throw (ex-info (str "build: not yet on " t) {:target t}))))))
