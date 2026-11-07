;; install.cljc — install the cohost tool. The only prerequisite is
;; Jank: this file IS a jank program.
;;
;;   curl -fsSL https://raw.githubusercontent.com/johnmn3/cohost.cljc/main/install.cljc | jank run /dev/stdin
;;   — or from a checkout:  jank run install.cljc [--bin DIR] [--repo DIR]
;;
;; What it does, in the open:
;;   1. finds (or clones) the cohost.cljc checkout — $COHOST_HOME,
;;      --repo, the current directory when it is a checkout, else
;;      ~/.local/share/cohost/cohost.cljc (git clone / pull);
;;   2. builds cohost's compiled core snapshot with jank alone
;;      (dev/install/jank_build.cljc — no Babashka, no Clojure CLI);
;;   3. compiles the `cohost` command from dev/install/lib/cohost/
;;      tool_main.cljc and puts it in --bin (default ~/.local/bin);
;;   4. records {:repo :jank :bin} in ~/.config/cohost/install.edn,
;;      which is how the command finds the checkout afterwards.
;;
;; Self-contained on purpose: a curl'd file can require nothing from
;; the repo it is about to fetch, so the small cpp/shim layer is
;; inlined here (the same code lives in dev/install/lib for the tool).
(ns cohost.install
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------ cpp layer
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
  }")

(defn- capture [cmd] (cpp/cohost_install_capture_impl cmd))
(defn- sh! [cmd] (cpp/cohost_install_run_impl cmd))
(defn- self-exe [] (let [s (cpp/cohost_install_self_exe_impl)] (when-not (empty? s) s)))
(defn- env [k] (let [v (cpp/cohost_install_get_env_impl k)] (when-not (empty? v) v)))

(defn- q [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))
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
  (let [argv (into {} (map (fn [[k v]] [(keyword (subs k 2)) v]))
                   (partition 2 (filter #(str/starts-with? % "--") (vec args))))
        jank (or (self-exe) (die "cannot locate the running jank binary"))
        explicit-repo (:repo argv)
        cwd-repo (let [c (trim (cwd))] (when (file? (path c "src/cohost/cli.cljc")) c))
        default-repo (path (home) ".local/share/cohost/cohost.cljc")
        repo (cond explicit-repo explicit-repo
                   (env "COHOST_HOME") (env "COHOST_HOME")
                   cwd-repo cwd-repo
                   :else default-repo)
        bin-dir (or (:bin argv) (env "COHOST_BIN") (path (home) ".local/bin"))]
    (println "cohost install: jank" (trim (capture (str (q jank) " print-binary-version"))))
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
    ;; 2. the snapshot, built by jank alone
    (println "cohost install: building the core snapshot (jank only; the first build takes minutes)")
    (checked! (str "cd " (q repo) " && " (q jank) " --module-path dev/install/lib run dev/install/jank_build.cljc -- ensure"))
    ;; 3. the command itself
    (checked! (str "cd " (q repo) " && rm -rf target/install/tool && " (q jank)
                   " -O3 --module-path dev/install/lib compile cohost.tool-main --runtime dynamic --name cohost --target-dir target/install/tool"))
    (let [built (path repo "target/install/tool/cohost")]
      (when-not (file? built) (die "the tool compile produced no binary at " built))
      (mkdir-p bin-dir)
      (checked! (str "cp " (q built) " " (q (path bin-dir "cohost")) " && chmod +x " (q (path bin-dir "cohost")))))
    ;; 4. the record the command reads
    (mkdir-p (path (home) ".config/cohost"))
    (spit (path (home) ".config/cohost/install.edn")
          (str "{:repo \"" repo "\", :jank \"" jank "\", :bin \"" bin-dir "\"}\n"))
    (println "cohost install: installed" (path bin-dir "cohost"))
    (when-not (some #{bin-dir} (str/split (or (env "PATH") "") #":"))
      (println (str "cohost install: note — " bin-dir " is not on your PATH; add it:")))
    (println (str "  export PATH=\"" bin-dir ":$PATH\""))
    (println "Next: cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new")))

(let [args *command-line-args*]
  (try (apply -main (remove #{"--"} args))
       (catch :default e
         ;; ex-message is guarded: some wrapped exceptions reject it,
         ;; and the handler must never become the failure.
         (binding [*out* *err*]
           (println "cohost install failed:" (or (try (ex-message e) (catch :default _ nil)) "stopped")))
         (cpp/cohost_install_exit_impl 1))))
