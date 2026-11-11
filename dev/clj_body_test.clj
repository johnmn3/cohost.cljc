(ns cohost.clj-body-test
  "Detection matrix for the :clj body.

   Each case runs bin/cohost with PATH limited to a temp directory of stub
   binaries. The real java, jolt, and clojure on the machine are not visible.
   Nothing here needs a JVM or a jolt install.

   bb dev/clj_body_test.clj
   bb clj-body:test"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :as t]))

(def root (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(load-file (str (fs/file root "bin/cohost_clj_body.clj")))

(def bb-bin
  (str (or (fs/which "bb")
           (let [p (fs/file (System/getenv "HOME") ".local/bin/bb")]
             (when (fs/exists? p) p)))))

(def cohost (str (fs/file root "bin/cohost")))

(defn- spit-exe [path text]
  (spit (str path) text)
  (.setExecutable (java.io.File. (str path)) true))

(def java-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$JAVA_STUB_LOG\"\n"
       "mode=\"${JAVA_STUB_MODE:-ok}\"\n"
       "case \"$mode\" in\n"
       "  ok) echo 'openjdk version \"21.0.2\" 2024-01-16' >&2 ;;\n"
       "  java8) echo 'java version \"1.8.0_392\"' >&2 ;;\n"
       "  old) echo 'java version \"1.7.0_80\"' >&2 ;;\n"
       "  broken) echo 'Unable to locate a Java Runtime.' >&2; exit 1 ;;\n"
       "  garbage) echo 'not a jvm' ;;\n"
       "  stdout) echo 'openjdk version \"17.0.9\"' ;;\n"
       "  fail-with-version) echo 'openjdk version \"21.0.2\" 2024-01-16' >&2; exit 1 ;;\n"
       "  *) echo \"unknown JAVA_STUB_MODE=$mode\" >&2; exit 1 ;;\n"
       "esac\n"
       "exit 0\n"))

(def home-java-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$JAVA_HOME_LOG\"\n"
       "echo 'openjdk version \"21.0.2\" 2024-01-16' >&2\n"
       "exit 0\n"))

(def jolt-stub
  ;; builtins only: PATH is the stub directory and nothing else, so a real
  ;; java/jolt/grep on the machine cannot be reached.
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$JOLT_STUB_LOG\"\n"
       "if [ \"$1\" = \"--version\" ] || [ \"$1\" = \"-V\" ] || [ \"$1\" = \"version\" ]; then\n"
       "  printf '%s\\n' \"${JOLT_STUB_VERSION:-jolt v0.8.24}\"\n"
       "  if [ \"${JOLT_STUB_FAIL:-0}\" = 1 ]; then exit 1; fi\n"
       "  exit 0\n"
       "fi\n"
       "case \"$*\" in\n"
       "  *champaca.api*)\n"
       "    if [ \"${JOLT_STUB_CHAMPACA:-yes}\" = yes ]; then\n"
       "      echo cohost-champaca-ok\n"
       "    else\n"
       "      echo cohost-champaca-missing\n"
       "    fi\n"
       "    exit 0\n"
       "    ;;\n"
       "esac\n"
       "echo \"unexpected jolt args: $*\" >&2\n"
       "exit 9\n"))

(def clojure-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$CLOJURE_STUB_LOG\"\n"
       "printf '%s\\n' \"$@\" > \"$CLOJURE_STUB_ARGV\"\n"
       "exit \"${CLOJURE_STUB_EXIT:-42}\"\n"))

(defn- slurp-lines [f]
  (when (fs/exists? f) (str/split-lines (slurp (str f)))))

(defn- child-env [bin logs c java-home]
  (let [base (-> (into {} (System/getenv))
                 (dissoc "JAVA_HOME" "COHOST_CLJ_BODY" "JAVA_STUB_MODE"
                         "JOLT_STUB_VERSION" "JOLT_STUB_CHAMPACA" "JOLT_STUB_FAIL"
                         "CLOJURE_STUB_EXIT"))]
    (cond-> (merge base
                   {"PATH" (str bin)
                    "HOME" (or (System/getenv "HOME") (str bin))
                    "JAVA_STUB_LOG" (str (:java logs))
                    "JAVA_HOME_LOG" (str (:home logs))
                    "JOLT_STUB_LOG" (str (:jolt logs))
                    "CLOJURE_STUB_LOG" (str (:clojure logs))
                    "CLOJURE_STUB_ARGV" (str (:argv logs))
                    "JAVA_STUB_MODE" (or (:java-mode c) "ok")
                    "JOLT_STUB_VERSION" (or (:jolt-version c) "jolt v0.8.24")
                    "JOLT_STUB_CHAMPACA" (or (:champaca c) "yes")
                    "CLOJURE_STUB_EXIT" "42"})
      java-home (assoc "JAVA_HOME" java-home)
      (:body c) (assoc "COHOST_CLJ_BODY" (:body c))
      (:jolt-fail c) (assoc "JOLT_STUB_FAIL" "1"))))

(defn- invoke [dir env args]
  @(p/process (into [bb-bin cohost] args)
              {:dir (str dir) :env env :out :string :err :string}))

(defn- check [c r logs dir]
  (let [name (:name c)
        err (str (:err r))
        msg (fn [extra] (str name "\n" extra "\n--- stdout ---\n" (:out r) "\n--- stderr ---\n" err))
        argv (slurp-lines (:argv logs))
        jolt-log (if (fs/exists? (:jolt logs)) (slurp (str (:jolt logs))) "")]
    (case (:expect c)
      :clojure
      (do (t/is (= 42 (:exit r)) (msg "exit"))
          (t/is (not (str/includes? err ":boot :stub")) (msg "clojure path must not be the champaca stub"))
          (when-let [exact (:argv c)]
            (t/is (= exact argv) (msg "argv")))
          (doseq [piece (:argv-includes c)]
            (t/is (some #(str/includes? % piece) argv) (msg (str "argv includes " piece))))
          (t/is (fs/exists? (:clojure logs)) (msg "clojure was not launched"))
          (t/is (not (fs/exists? (:jolt logs))) (msg "jolt was probed on the jvm path")))
      :stub
      (do (t/is (= 1 (:exit r)) (msg "exit"))
          (t/is (str/includes? err "jolt's :clj handler on the champaca path") (msg "stub banner"))
          (t/is (str/includes? err ":boot :stub") (msg "plan boot"))
          (t/is (str/includes? err ":runtime :champaca") (msg "plan runtime"))
          (t/is (str/includes? err (pr-str (:plan-args c))) (msg "plan args"))
          (t/is (str/includes? err (str (fs/canonicalize dir))) (msg "plan cwd"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure launched on the stub path"))
          (t/is (str/includes? jolt-log "--version") (msg "jolt version probe"))
          (t/is (str/includes? jolt-log "champaca.api") (msg "champaca probe")))
      :error
      (do (t/is (= 1 (:exit r)) (msg "exit"))
          (t/is (not (str/includes? err ":boot :stub")) (msg "error must not select the stub"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure launched"))
          (doseq [piece (:stderr-includes c)]
            (t/is (str/includes? err piece) (msg (str "stderr includes " piece))))))
    (when (true? (:home c))
      (t/is (not (fs/exists? (:java logs))) (msg "PATH java consulted while JAVA_HOME is set"))
      (when (= :clojure (:expect c))
        (t/is (some #(str/includes? % "-version") (slurp-lines (:home logs)))
              (msg "JAVA_HOME java was not version-checked"))))
    (when (and (= :clojure (:expect c)) (:java c) (not (true? (:home c))))
      (t/is (some #(str/includes? % "-version") (slurp-lines (:java logs)))
            (msg "PATH java was not version-checked")))
    (when (or (:path-java-not-called c) (= :missing (:home c)) (:no-java-probe c))
      (t/is (not (fs/exists? (:java logs))) (msg "PATH java was called")))
    (cond
      (:no-champaca-probe c)
      (do (t/is (fs/exists? (:jolt logs)) (msg "jolt version was not probed"))
          (t/is (not (str/includes? jolt-log "champaca.api")) (msg "champaca probed despite the version gate")))
      (:jolt-probed c)
      (t/is (and (fs/exists? (:jolt logs)) (str/includes? jolt-log "champaca.api"))
            (msg "champaca probe did not run"))
      (not= :stub (:expect c))
      (t/is (not (fs/exists? (:jolt logs))) (msg "jolt was called")))))

(defn- run-case! [c]
  (let [dir (fs/create-temp-dir {:prefix "cohost-clj-body-"})
        bin (fs/file dir "bin")
        logs {:java (fs/file dir "java.log")
              :home (fs/file dir "home-java.log")
              :jolt (fs/file dir "jolt.log")
              :clojure (fs/file dir "clojure.log")
              :argv (fs/file dir "clojure.argv")}
        java-home (case (:home c)
                    true (let [jdk (fs/file dir "jdk")]
                           (fs/create-dirs (fs/file jdk "bin"))
                           (spit-exe (fs/file jdk "bin/java") home-java-stub)
                           (str jdk))
                    :missing (let [jdk (fs/file dir "empty-jdk")]
                               (fs/create-dirs jdk)
                               (str jdk))
                    nil)]
    (try
      (fs/create-dirs bin)
      (when (:java c) (spit-exe (fs/file bin "java") java-stub))
      (when (:jolt c) (spit-exe (fs/file bin "jolt") jolt-stub))
      (spit-exe (fs/file bin "clojure") clojure-stub)
      (let [r (invoke dir (child-env bin logs c java-home) (:args c))]
        (check c r logs dir))
      (finally
        (fs/delete-tree dir)))))

(def cases
  [{:name "usable jvm stays on clojure; implicit -e gains one -M"
    :args [":jvm" "-e" "(+ 1 2)"]
    :java true
    :expect :clojure
    :argv ["-M" "-e" "(+ 1 2)"]}
   {:name "explicit -M is not rewritten"
    :args [":jvm" "-M" "-e" "(+ 1 2)"]
    :java true
    :expect :clojure
    :argv ["-M" "-e" "(+ 1 2)"]}
   {:name "-T stays on clojure and keeps the template dep"
    :args [":jvm" "-Tnew" "create"]
    :java true
    :expect :clojure
    :argv-includes ["-Sdeps" "templates/cohost-app" "-Tnew"]}
   {:name "-T merges into an existing -Sdeps"
    :args [":jvm" "-Sdeps" "{:deps {foo/bar {:mvn/version \"1\"}}}" "-Tnew"]
    :java true
    :expect :clojure
    :argv-includes ["templates/cohost-app" "foo/bar" "-Tnew"]}
   {:name "-Sdescribe stays on clojure"
    :args [":jvm" "-Sdescribe"]
    :java true
    :expect :clojure
    :argv ["-Sdescribe"]}
   {:name "-Sdescribe plus an implicit main keeps the old argument order"
    :args [":jvm" "-Sdescribe" "-e" "1"]
    :java true
    :expect :clojure
    :argv ["-Sdescribe" "-M" "-e" "1"]}
   {:name "auto prefers a usable jvm over champaca jolt"
    :args [":jvm" "-e" "1"]
    :java true
    :jolt true
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "java 8 is usable"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "java8"
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "version printed on stdout is usable"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "stdout"
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "JAVA_HOME java is the one that is checked"
    :args [":jvm" "-e" "1"]
    :home true
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "a usable JAVA_HOME wins over a broken java on PATH"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "broken"
    :home true
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "a broken JAVA_HOME does not fall back to PATH or jolt"
    :args [":jvm" "-e" "1"]
    :java true
    :home :missing
    :jolt true
    :expect :error
    :stderr-includes ["JAVA_HOME" "does not fall back"]}
   {:name "no jvm and champaca jolt selects the stub"
    :args [":jvm" "-e" "(+ 1 2)"]
    :jolt true
    :expect :stub
    :plan-args ["-e" "(+ 1 2)"]}
   {:name "a nightly past 0.8.24 is new enough"
    :args [":jvm" "-e" "1"]
    :jolt true
    :jolt-version "jolt v0.8.24-82-g706a9573"
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "jolt 0.9.0 is new enough"
    :args [":jvm" "-e" "1"]
    :jolt true
    :jolt-version "jolt v0.9.0"
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "neither jvm nor jolt is a clear error"
    :args [":jvm" "-e" "1"]
    :expect :error
    :stderr-includes ["no usable JVM" "jolt is not on PATH" "0.8.23"]}
   {:name "jolt 0.8.23 is too old to be the body"
    :args [":jvm" "-e" "1"]
    :jolt true
    :jolt-version "jolt v0.8.23"
    :expect :error
    :no-champaca-probe true
    :stderr-includes ["0.8.23" "0.8.24"]}
   {:name "jolt without champaca is not the body"
    :args [":jvm" "-e" "1"]
    :jolt true
    :champaca "no"
    :jolt-probed true
    :expect :error
    :stderr-includes ["champaca is not available"]}
   {:name "unnumbered jolt dev does not meet the floor"
    :args [":jvm" "-e" "1"]
    :jolt true
    :jolt-version "jolt dev"
    :expect :error
    :no-champaca-probe true
    :stderr-includes ["no numeric version"]}
   {:name "a failing jolt --version is not a body"
    :args [":jvm" "-e" "1"]
    :jolt true
    :jolt-fail true
    :expect :error
    :no-champaca-probe true
    :stderr-includes ["jolt --version exited"]}
   {:name "override auto matches the default"
    :args [":jvm" "-e" "1"]
    :java true
    :jolt true
    :body "auto"
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "a blank override is auto"
    :args [":jvm" "-e" "1"]
    :java true
    :jolt true
    :body "  "
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "override jolt wins over a usable jvm"
    :args [":jvm" "-e" "1"]
    :java true
    :jolt true
    :body "jolt"
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "override Jolt is case-insensitive and ignores surrounding space"
    :args [":jvm" "-e" "1"]
    :java true
    :jolt true
    :body " Jolt "
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "override JVM is case-insensitive"
    :args [":jvm" "-e" "1"]
    :java true
    :body "JVM"
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "override jvm with no jvm errors and does not probe jolt"
    :args [":jvm" "-e" "1"]
    :jolt true
    :body "jvm"
    :expect :error
    :stderr-includes ["COHOST_CLJ_BODY=jvm"]}
   {:name "override jolt with no champaca errors and does not use the jvm"
    :args [":jvm" "-e" "1"]
    :java true
    :jolt true
    :champaca "no"
    :body "jolt"
    :jolt-probed true
    :expect :error
    :stderr-includes ["COHOST_CLJ_BODY=jolt" "champaca is not available"]}
   {:name "a broken jvm does not fall back to jolt"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "broken"
    :jolt true
    :expect :error
    :stderr-includes ["not usable" "does not fall back"]}
   {:name "java -version exiting non-zero is broken even when it prints a version"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "fail-with-version"
    :jolt true
    :expect :error
    :stderr-includes ["exited 1" "does not fall back"]}
   {:name "java 7 does not fall back"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "old"
    :jolt true
    :expect :error
    :stderr-includes ["below the minimum" "does not fall back"]}
   {:name "unparseable java output is a broken jvm"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "garbage"
    :jolt true
    :expect :error
    :stderr-includes ["no version" "does not fall back"]}
   {:name "override jolt still selects champaca when the jvm is broken"
    :args [":jvm" "-e" "1"]
    :java true
    :java-mode "broken"
    :jolt true
    :body "jolt"
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "an unknown override is a diagnostic"
    :args [":jvm" "-e" "1"]
    :java true
    :body "champaca"
    :no-java-probe true
    :expect :error
    :stderr-includes ["COHOST_CLJ_BODY must be jvm, jolt, or auto"]}
   {:name "-T with no jvm says it is jvm-only and does not use jolt"
    :args [":cljs" "-Tnew"]
    :jolt true
    :expect :error
    :stderr-includes ["-T runs on the JVM only" "will not use jolt"]}
   {:name "-Sdescribe with no jvm says it is jvm-only"
    :args [":jolt" "-Sdescribe"]
    :jolt true
    :expect :error
    :stderr-includes ["-Sdescribe runs on the JVM only" "will not use jolt"]}
   {:name "-T stays jvm-only when the override asks for jolt"
    :args [":jvm" "-Tnew"]
    :jolt true
    :body "jolt"
    :expect :error
    :stderr-includes ["-T runs on the JVM only"]}
   {:name "-T with a broken jvm is a diagnostic and does not fall back"
    :args [":jvm" "-Tnew"]
    :java true
    :java-mode "broken"
    :jolt true
    :expect :error
    :stderr-includes ["-T runs on the JVM only" "exited 1"]}
   {:name "a native host is not routed through the :clj body"
    :args [":jank" "-e" "1"]
    :jolt true
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}
   {:name "the default host stays jank, not the :clj body"
    :args []
    :jolt true
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}
   {:name ":jvm -Spath with no jvm is the champaca stub"
    :args [":jvm" "-Spath"]
    :jolt true
    :expect :stub
    :plan-args ["-Spath"]}])

(t/deftest version-parsing
  (t/is (= 8 (cohost.clj-body/parse-java-version "java version \"1.8.0_392\"")))
  (t/is (= 21 (cohost.clj-body/parse-java-version "openjdk version \"21.0.2\" 2024-01-16")))
  (t/is (= 17 (cohost.clj-body/parse-java-version "openjdk version \"17.0.9\"")))
  (t/is (= 25 (cohost.clj-body/parse-java-version "openjdk version \"25\" 2025-09-16")))
  (t/is (nil? (cohost.clj-body/parse-java-version "Unable to locate a Java Runtime.")))
  (t/is (= [0 8 24] (cohost.clj-body/parse-jolt-version "jolt v0.8.24")))
  (t/is (= [0 8 24] (cohost.clj-body/parse-jolt-version "jolt v0.8.24-82-g706a9573")))
  (t/is (= [0 8 23] (cohost.clj-body/parse-jolt-version "jolt v0.8.23\n")))
  (t/is (= [0 9 0] (cohost.clj-body/parse-jolt-version "jolt v0.9.0")))
  (t/is (nil? (cohost.clj-body/parse-jolt-version "jolt dev")))
  (t/is (nil? (cohost.clj-body/parse-jolt-version "jolt dev-g706a9573")))
  (t/is (cohost.clj-body/version-at-least? [0 8 24] cohost.clj-body/jolt-min-version))
  (t/is (cohost.clj-body/version-at-least? [0 9 0] cohost.clj-body/jolt-min-version))
  (t/is (not (cohost.clj-body/version-at-least? [0 8 23] cohost.clj-body/jolt-min-version)))
  (t/is (= 8 cohost.clj-body/java-min-feature)))

(t/deftest detection-matrix
  (t/is (fs/exists? bb-bin) (str "babashka not found at " bb-bin))
  (doseq [c cases]
    (t/testing (:name c)
      (run-case! c))))

(defn- run-help! []
  (let [c {:name "help exits before any probe"
           :args ["-h"]
           :stderr-includes ["usage: cohost" "else :jank" "COHOST_CLJ_BODY"]}]
    (let [dir (fs/create-temp-dir {:prefix "cohost-clj-body-help-"})
          bin (fs/file dir "bin")
          logs {:java (fs/file dir "java.log")
                :home (fs/file dir "home-java.log")
                :jolt (fs/file dir "jolt.log")
                :clojure (fs/file dir "clojure.log")
                :argv (fs/file dir "clojure.argv")}]
      (try
        (fs/create-dirs bin)
        (spit-exe (fs/file bin "clojure") clojure-stub)
        (spit-exe (fs/file bin "java") java-stub)
        (spit-exe (fs/file bin "jolt") jolt-stub)
        (let [r (invoke dir (child-env bin logs c nil) (:args c))
              err (str (:err r))]
          (t/is (= 2 (:exit r)) err)
          (doseq [piece (:stderr-includes c)]
            (t/is (str/includes? err piece) err))
          (t/is (not (fs/exists? (:java logs))) err)
          (t/is (not (fs/exists? (:jolt logs))) err)
          (t/is (not (fs/exists? (:clojure logs))) err))
        (finally (fs/delete-tree dir))))))

(t/deftest help-skips-detection
  (run-help!))

(let [{:keys [fail error]} (t/run-tests 'cohost.clj-body-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
