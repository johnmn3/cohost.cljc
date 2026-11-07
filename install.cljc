;; install.cljc — install the cohost tool. The prerequisite is any
;; one Clojure-like to run this file — Jank, Babashka, or the Clojure
;; CLI — plus Jank itself, which compiles the tool on every seat
;; (when the seat is not Jank and no jank is on the path, step 1.5
;; below installs it via the checkout's dev/install/jank.sh).
;;
;;   jank run install.cljc [--bin DIR] [--repo DIR]
;;   bb install.cljc [--bin DIR] [--repo DIR]
;;   clojure -M install.cljc [--bin DIR] [--repo DIR]
;;   — or from a checkout with any of the three; a curl'd copy runs
;;   the same way, since this file requires nothing from the repo it
;;   is about to fetch.
;;
;; What it does, in the open:
;;   1. finds (or clones) the cohost.cljc checkout — $COHOST_HOME,
;;      --repo, the current directory when it is a checkout, else
;;      ~/.local/share/cohost/cohost.cljc (git clone / pull);
;;   2. builds cohost's compiled core snapshot, driven by the seat
;;      that ran this file: jank -> dev/install/jank_build.cljc (jank
;;      alone); bb -> the original bb build (dev/perf/
;;      jank_snapshot_ensure.clj); clj -> the same original build
;;      under the Clojure CLI. The build's record/compile steps are
;;      jank's on every seat, and a snapshot built by any flow is
;;      fresh to the others (byte-identical keys);
;;   3. compiles the `cohost` command from dev/install/lib/cohost/
;;      tool_main.cljc with jank and puts it in --bin (default
;;      ~/.local/bin);
;;   4. records {:repo :jank :bin :seat} in
;;      ~/.config/cohost/install.edn, which is how the command finds
;;      the checkout afterwards (:seat names the Clojure-like that
;;      ran this installer).
;;
;; Self-contained on purpose: a curl'd file can require nothing from
;; the repo it is about to fetch, so the small host shim layer is
;; inlined here — one reader-conditional arm per seat (the same code
;; lives in dev/install/lib for the tool, on the jank seat).
(ns cohost.install
  (:require [clojure.string :as str]
            #?@(:bb [[babashka.process :as bp]])))

(def ^:private seat
  "The Clojure-like running this file. Arm order matters: jank and
   bb also read :clj arms, so their own features come first (the
   house reader-order rule)."
  #?(:jank :jank :bb :bb :clj :clj))

;; Seat dispatch below is if-chains, never `case`: this jank
;; build's JIT codegen for case* on keywords is broken (the errors
;; point into clojure/core.jank's case machinery, line 4531;
;; INSTALL-PORTABLE-STATUS.md has the receipt).

;; ------------------------------------------------------------ host shim
;; The host services with one arm per seat: capture (a shell
;; command's stdout), sh! (a shell command's exit code, stdio
;; inherited), env, self-exe (the seat executable's path); the
;; failure exit at the bottom is armed the same way. Everything
;; else is portable. Regex discipline: jank reads regex literals
;; verbatim, so no pattern whose escapes could differ per seat —
;; the file's only regex literals are the two escape-free patterns
;; inherited from the jank-only installer (#"/+$" and #":"), which
;; mean the same thing on every seat.

#?(:jank
   (cpp/raw
    "std::string cohost_install_capture_impl(std::string const &cmd) {
    std::string out;
    FILE *p = popen(cmd.c_str(), \"r\");
    if(!p) { return out; }
    char buf[4096];
    std::size_t n;
    while((n = fread(buf, 1, sizeof buf, p)) > 0) { out.append(buf, n); }
    pclose(p);
    return out;
  }
  void cohost_install_exit_impl(int code) { std::exit(code); }
  std::string cohost_install_get_env_impl(char const *k) {
    char const *v = std::getenv(k);
    return v ? std::string(v) : std::string();
  }
  long cohost_install_run_impl(std::string const &cmd) {
    int rc = std::system(cmd.c_str());
    if(rc == -1) { return -1; }
    if(WIFEXITED(rc)) { return WEXITSTATUS(rc); }
    return 128;
  }
  std::string cohost_install_self_exe_impl() {
  #ifdef __APPLE__
    char buf[4096];
    unsigned int size = sizeof buf;
    if(_NSGetExecutablePath(buf, &size) == 0) { return std::string(buf); }
    return std::string();
  #else
    char buf[4096];
    ssize_t n = readlink(\"/proc/self/exe\", buf, sizeof buf - 1);
    if(n <= 0) { return std::string(); }
    buf[n] = 0;
    return std::string(buf);
  #endif
  }"))

(defn- q [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

#?(:jank (defn- capture [cmd] (cpp/cohost_install_capture_impl cmd))
   :bb (defn- capture [cmd]
         (:out (bp/shell {:out :string :err :inherit :continue true} "sh" "-c" cmd)))
   :clj (defn- capture [cmd]
          (let [pb (java.lang.ProcessBuilder. ["sh" "-c" cmd])
                _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
                p (.start pb)
                out (slurp (.getInputStream p))]
            (.waitFor p)
            out)))

#?(:jank (defn- sh! [cmd] (cpp/cohost_install_run_impl cmd))
   :bb (defn- sh! [cmd]
         (:exit (bp/shell {:in :inherit :out :inherit :err :inherit :continue true} "sh" "-c" cmd)))
   :clj (defn- sh! [cmd]
          (.waitFor (.start (.inheritIO (java.lang.ProcessBuilder. ["sh" "-c" cmd]))))))

#?(:jank (defn- env [k] (let [v (cpp/cohost_install_get_env_impl k)] (when-not (empty? v) v)))
   :default (defn- env [k] (let [v (System/getenv k)] (when-not (empty? v) v))))

(defn- which
  "The absolute path of cmd on the PATH, else nil."
  [cmd]
  (let [s (str/trim (capture (str "command -v " (q cmd) " 2>/dev/null")))]
    (when-not (empty? s) s)))

#?(:jank (defn- self-exe [] (let [s (cpp/cohost_install_self_exe_impl)] (when-not (empty? s) s)))
   :bb (defn- self-exe []
         (or (try (str (java.nio.file.Files/readSymbolicLink
                        (java.nio.file.Paths/get "/proc/self/exe" (into-array String []))))
                  (catch Exception _ nil))
             (which "bb")))
   :clj (defn- self-exe [] (which "clojure")))

;; ------------------------------------------------------------ portable helpers

(defn- path [a b] (str (str/replace (str a) #"/+$" "") "/" b))
(defn- file? [p] (zero? (sh! (str "test -f " (q p)))))
(defn- dir? [p] (zero? (sh! (str "test -d " (q p)))))
(defn- mkdir-p [p] (sh! (str "mkdir -p " (q p))))
(defn- home [] (or (env "HOME") (throw (ex-info "HOME is not set" {}))))
(defn- cwd [] (capture "pwd"))
(defn- trim [s] (str/trim (str s)))

(defn- die [& parts]
  (binding [*out* *err*] (println (apply str parts)))
  (throw (ex-info "install: stopped" {})))

(defn- checked! [cmd]
  (let [rc (sh! cmd)]
    (when-not (zero? rc) (die "command failed (" rc "): " cmd))))

;; ------------------------------------------------------------ the install

(defn -main [& args]
  (let [argv (loop [xs (seq args) m {}]
               ;; --flag value pairs, consumed two at a time. (The
               ;; jank-only installer's parse filtered to "--" tokens
               ;; before partitioning, which dropped every value:
               ;; --repo only ever worked from inside the checkout,
               ;; where cwd-repo found the same directory.)
               (cond (nil? xs) m
                     (str/starts-with? (first xs) "--")
                     (recur (nnext xs) (assoc m (keyword (subs (first xs) 2)) (second xs)))
                     :else (recur (next xs) m)))
        driver (or (self-exe)
                   (die (if (= seat :jank) "cannot locate the running jank binary"
                            (if (= seat :bb) "cannot locate the running bb binary"
                                "cannot locate the clojure command"))))
        explicit-repo (:repo argv)
        cwd-repo (let [c (trim (cwd))] (when (file? (path c "src/cohost/cli.cljc")) c))
        default-repo (path (home) ".local/share/cohost/cohost.cljc")
        repo (cond explicit-repo explicit-repo
                   (env "COHOST_HOME") (env "COHOST_HOME")
                   cwd-repo cwd-repo
                   :else default-repo)
        bin-dir (or (:bin argv) (env "COHOST_BIN") (path (home) ".local/bin"))]
    (println "cohost install:" (name seat)
             (trim (capture (str (q driver) (if (= seat :jank) " print-binary-version" " --version")))))
    ;; 1. the checkout
    (when-not (file? (path repo "src/cohost/cli.cljc"))
      (when explicit-repo (die "no cohost checkout at " repo))
      (when-not (zero? (sh! "command -v git >/dev/null 2>&1"))
        (die "cohost: git is required to fetch the checkout (or pass --repo DIR)."))
      (if (dir? (path repo ".git"))
        (checked! (str "git -C " (q repo) " pull --ff-only --quiet"))
        (do (mkdir-p (path (home) ".local/share/cohost"))
            (checked! (str "git clone --quiet https://github.com/johnmn3/cohost.cljc " (q repo))))))
    (println "cohost install: repo" repo)
    ;; 1.5. the tool is compiled with jank on every seat: on the jank
    ;; seat the driver IS jank; on the others, jank must be on the
    ;; path — install it from the checkout when it is missing.
    (let [jank (or (when (= seat :jank) driver)
                   (which "jank")
                   (do (println "cohost install: no jank on the path; installing it (dev/install/jank.sh)")
                       (checked! (str "bash " (q (path repo "dev/install/jank.sh"))))
                       (which "jank"))
                   (die "cohost: the cohost tool is compiled with jank, and no jank is on the path. "
                        "Install jank (bash dev/install/jank.sh in the checkout) and re-run this installer."))]
      ;; 2. the snapshot, driven by the seat that ran this file
      (println "cohost install: building the core snapshot"
               (if (= seat :jank) "(jank only; the first build takes minutes)"
                   (if (= seat :bb) "(driven by bb; the first build takes minutes)"
                       "(driven by clj; the first build takes minutes)")))
      (checked!
       (if (= seat :jank)
         (str "cd " (q repo) " && " (q driver) " --module-path dev/install/lib run dev/install/jank_build.cljc -- ensure")
         (if (= seat :bb)
           (str "cd " (q repo) " && " (q driver) " -e " (q "(load-file \"dev/perf/jank_snapshot_ensure.clj\")"))
           (str "cd " (q repo) " && " (q driver) " -Sdeps "
                (q "{:deps {babashka/process {:mvn/version \"0.5.22\"} babashka/fs {:mvn/version \"0.5.24\"}}}")
                " -M -e " (q "(load-file \"dev/perf/jank_snapshot_ensure.clj\")")))))
      ;; 3. the command itself, compiled with jank on every seat
      (checked! (str "cd " (q repo) " && rm -rf target/install/tool && " (q jank)
                     " -O3 --module-path dev/install/lib compile cohost.tool-main --runtime dynamic --name cohost --target-dir target/install/tool"))
      (let [built (path repo "target/install/tool/cohost")]
        (when-not (file? built) (die "the tool compile produced no binary at " built))
        (mkdir-p bin-dir)
        (checked! (str "cp " (q built) " " (q (path bin-dir "cohost")) " && chmod +x " (q (path bin-dir "cohost")))))
      ;; 4. the record the command reads
      (mkdir-p (path (home) ".config/cohost"))
      (spit (path (home) ".config/cohost/install.edn")
            (str "{:repo \"" repo "\", :jank \"" jank "\", :bin \"" bin-dir "\", :seat " seat
                 (when (not= seat :jank) (str ", :" (name seat) " \"" driver "\""))
                 "}\n"))
      (println "cohost install: installed" (path bin-dir "cohost"))
      (when-not (some #{bin-dir} (str/split (or (env "PATH") "") #":"))
        (println (str "cohost install: note — " bin-dir " is not on your PATH; add it:")))
      (println (str "  export PATH=\"" bin-dir ":$PATH\""))
      (println "Next: cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new"))))

(let [args *command-line-args*]
  (try (apply -main (remove #{"--"} args))
       ;; The handler stays inline, in the jank-only installer's
       ;; exact shape: factored into a defn, jank's JIT fails to
       ;; realize it (INSTALL-PORTABLE-STATUS.md has the receipt).
       ;; ex-message is guarded: some wrapped exceptions reject it,
       ;; and the handler must never become the failure.
       #?@(:jank [(catch :default e
                    (binding [*out* *err*]
                      (println "cohost install failed:" (or (try (ex-message e) (catch :default _ nil)) "stopped")))
                    (cpp/cohost_install_exit_impl 1))]
           :bb [(catch Exception e
                  (binding [*out* *err*]
                    (println "cohost install failed:" (or (try (ex-message e) (catch Exception _ nil)) "stopped")))
                  (System/exit 1))]
           :clj [(catch Throwable e
                   (binding [*out* *err*]
                     (println "cohost install failed:" (or (try (ex-message e) (catch Throwable _ nil)) "stopped")))
                   (System/exit 1))])))
