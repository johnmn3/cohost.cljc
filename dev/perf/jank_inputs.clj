;; The files a cache key covers: one function for every key that decides a jank
;; cache is fresh (dev/perf/jank_snapshot_key.clj, the snapshot's;
;; dev/perf/jank_aot_ensure.clj, cohost's AOT objects; dev/perf/
;; jank_snapshot_cli.clj, a project's snapshot).
;;
;; A source file is a regular file under the directory, except what a host
;; writes beside the sources when it runs them: Basilisp's bytecode cache
;; (__pycache__/, *.pyc, *.lpyc), written into src/cohost/ by any basilisp run.
;; Keyed in, it made every key stale after the next basilisp run: the CLI saw
;; the snapshot stale right after bb boot:jank-snapshot built it, whenever
;; another host had run in between (the coordinator's FINDINGS on a1aeb5f).
(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(defn host-cache-file?
  "A file a host's runtime writes beside its sources: not an input."
  [path]
  (let [parts (map str (fs/components (fs/path path)))]
    (or (some #(= "__pycache__" %) parts)
        (str/ends-with? (str path) ".pyc")
        (str/ends-with? (str path) ".lpyc"))))

(defn source-files
  "The input files under dir (matching glob, default every file), sorted."
  ([dir] (source-files dir "**"))
  ([dir glob]
   (->> (fs/glob dir glob)
        (filter fs/regular-file?)
        (remove host-cache-file?)
        (map str)
        sort
        vec)))
