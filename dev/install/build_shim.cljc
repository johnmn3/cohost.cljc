;; cohost.install.shim — the raw-jank foundation of the bb-free install
;; build (dev/install/jank_build.cljc) and of the cohost tool
;; (dev/install/cohost_tool.cljc). Raw jank has no babashka.process, no
;; babashka.fs, no clojure.edn and no Java MessageDigest, so: processes
;; and output capture go through cpp helpers (popen / std::system),
;; the filesystem through shell tools (find, stat, sha256sum), EDN
;; through clojure.core/read-string (data only) and pr-str. POSIX only.
(ns cohost.install.shim
  (:require [clojure.string :as str]))

(cpp/raw "
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
namespace cohost_shim {
  std::string capture(char const *cmd) {
    std::string out;
    FILE *p = popen(cmd, \"r\");
    if (!p) { return out; }
    char buf[8192];
    while (true) { std::size_t n = std::fread(buf, 1, sizeof buf, p); if (n == 0) { break; } out.append(buf, n); }
    pclose(p);
    return out;
  }
  long long run(char const *cmd) {
    int rc = std::system(cmd);
    if (rc == -1) { return -1; }
    if (WIFEXITED(rc)) { return WEXITSTATUS(rc); }
    return 128 + (WIFSIGNALED(rc) ? WTERMSIG(rc) : 0);
  }
  std::string get_env(char const *k) {
    char const *v = std::getenv(k);
    return v ? std::string(v) : std::string();
  }
  long long set_env(char const *k, char const *v) { return setenv(k, v, 1); }
  std::string self_exe() {
    char buf[4096];
#if defined(__APPLE__)
    unsigned int n = sizeof buf;
    if (_NSGetExecutablePath(buf, &n) != 0) { return std::string(); }
    char *rp = realpath(buf, nullptr);
    std::string s = rp ? rp : buf;
    if (rp) { free(rp); }
    return s;
#else
    ssize_t n = readlink(\"/proc/self/exe\", buf, sizeof buf - 1);
    if (n <= 0) { return std::string(); }
    buf[n] = 0;
    return std::string(buf);
#endif
  }
  std::string cwd() {
    char buf[4096];
    return getcwd(buf, sizeof buf) ? std::string(buf) : std::string();
  }
}
")

;; ---------------------------------------------------------------- env

(defn env
  "The environment variable k, or nil when unset/empty."
  [k]
  (let [v (cpp/cohost_shim.get_env k)]
    (when-not (str/blank? v) v)))

(defn self-exe
  "This process's executable path (the jank that is running us), or nil."
  []
  (let [p (cpp/cohost_shim.self_exe)]
    (when-not (str/blank? p) p)))

(defn cwd [] (cpp/cohost_shim.cwd))

(defn home [] (or (env "HOME") (throw (ex-info "cohost: HOME is not set" {}))))

;; ---------------------------------------------------------------- shell

(defn q
  "Single-quote s for /bin/sh."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn sh!
  "Run cmd through /bin/sh with inherited stdio; -> its exit code."
  [cmd]
  (cpp/cohost_shim.run cmd))

(defn sh-checked!
  "sh! or throw with the command named."
  [cmd]
  (let [rc (sh! cmd)]
    (when-not (zero? rc)
      (throw (ex-info (str "command failed (" rc "): " cmd) {:cmd cmd :rc rc})))
    rc))

(defn capture
  "Run cmd through /bin/sh; -> its stdout as a string."
  [cmd]
  (cpp/cohost_shim.capture cmd))

(defn capture-trim [cmd] (str/trim (capture cmd)))

(defn which
  "The path of program p on PATH, or nil."
  [p]
  (let [s (capture-trim (str "command -v " (q p)))]
    (when-not (str/blank? s) s)))

;; ---------------------------------------------------------------- fs

(defn path
  "Join segments with / (an absolute segment restarts the join)."
  ([a] (str a))
  ([a b & more]
   (reduce (fn [acc seg]
             (let [seg (str seg)]
               (cond (str/starts-with? seg "/") seg
                     (str/ends-with? acc "/") (str acc seg)
                     :else (str acc "/" seg))))
           (str a) (cons b more))))

(defn parent [p]
  (let [p (str p)
        p (if (and (> (count p) 1) (str/ends-with? p "/")) (subs p 0 (dec (count p))) p)
        i (.lastIndexOf p "/")]
    (cond (neg? i) nil
          (zero? i) "/"
          :else (subs p 0 i))))

(defn file-name [p]
  (let [p (str p) i (.lastIndexOf p "/")]
    (if (neg? i) p (subs p (inc i)))))

(defn absolutize
  "p made absolute against the process cwd (no symlink resolution)."
  [p]
  (let [p (str p)]
    (if (str/starts-with? p "/") p (path (cwd) p))))

(defn exists? [p] (zero? (sh! (str "test -e " (q p)))))
(defn directory? [p] (zero? (sh! (str "test -d " (q p)))))
(defn regular-file? [p] (zero? (sh! (str "test -f " (q p)))))
(defn executable? [p] (zero? (sh! (str "test -x " (q p)))))

(defn mkdir-p [p] (sh-checked! (str "mkdir -p " (q p))) (str p))
(defn rm-rf [p] (sh-checked! (str "rm -rf " (q p))) nil)
(defn delete-file [p] (sh! (str "rm -f " (q p))) nil)
(defn copy-file [from to] (sh-checked! (str "cp " (q from) " " (q to))) (str to))
(defn chmod+x [p] (sh-checked! (str "chmod +x " (q p))) (str p))

(defn walk-files
  "Every regular file under dir (relative to dir's spelling as given),
   sorted — `find` order made deterministic. Hidden files included."
  [dir]
  (let [out (capture (str "find " (q dir) " -type f | LC_ALL=C sort"))]
    (if (str/blank? out) [] (str/split-lines out))))

(defn file-size [p]
  (let [s (capture-trim (str "stat -c %s " (q p) " 2>/dev/null || stat -f %z " (q p)))]
    (when-not (str/blank? s) (parse-long s))))

(defn file-mtime-ms [p]
  (let [s (capture-trim (str "stat -c %Y " (q p) " 2>/dev/null || stat -f %m " (q p)))]
    (when-not (str/blank? s) (* 1000 (parse-long s)))))

;; ---------------------------------------------------------------- sha256
;; Byte-parity with dev/perf/sha256.clj (the bb originals): sha256sum's
;; own output IS the listing format ("<hex>  <path>\n" per file), and
;; sha256-hex of a string is sha256sum over its UTF-8 bytes.

(defn sha256-hex
  "Hex sha256 of the string s (via a temp file: no quoting hazards)."
  [s]
  (let [tmp (path (or (env "TMPDIR") "/tmp") (str "cohost-shim-" (rand-int 1000000000)))]
    (try
      (spit tmp s)
      (first (str/split (capture-trim (str "sha256sum " (q tmp) " 2>/dev/null || shasum -a 256 " (q tmp))) #"\s+"))
      (finally (delete-file tmp)))))

(defn sha256sum-listing
  "sha256sum's output for these files, in this order."
  [files]
  (apply str
         (map (fn [batch]
                (capture (str "sha256sum -- " (str/join " " (map q batch))
                              " 2>/dev/null || shasum -a 256 -- " (str/join " " (map q batch)))))
              (partition-all 100 files))))

;; ---------------------------------------------------------------- misc

(defn now-ms [] (parse-long (capture-trim "date +%s%3N")))

(defn read-edn-file
  "Read one EDN value from f (data only), or nil when f is absent.
   Throws on unreadable content, with the file named."
  [f]
  (when (regular-file? f)
    (try (read-string (slurp f))
         (catch Exception e
           (throw (ex-info (str "cohost: cannot read " f ": " (ex-message e)) {:file f}))))))
