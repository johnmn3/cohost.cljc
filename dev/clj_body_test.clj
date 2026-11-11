(ns cohost.clj-body-test
  "The :clj leg: `clojure` on PATH keeps the existing launch; otherwise jolt
   on PATH runs lib/champaca. Each case runs bin/cohost with PATH limited to
   a temp directory of stub binaries.

   bb dev/clj_body_test.clj
   bb clj-body:test"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :as t]))

(def root (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(load-file (str (fs/file root "bin/cohost_clj_body.clj")))
(load-file (str (fs/file root "src/cohost/scan.cljc")))
(def bb-bin
  (str (or (fs/which "bb")
           (let [p (fs/file (System/getenv "HOME") ".local/bin/bb")]
             (when (fs/exists? p) p)))))
(def cohost (str (fs/file root "bin/cohost")))
(def path-sep java.io.File/pathSeparator)
(def fake-cp "/proj/src:/m2/clojure-1.12.0.jar")

(defn- spit-exe [path text]
  (spit (str path) text)
  (.setExecutable (java.io.File. (str path)) true))

(def clojure-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$CLOJURE_STUB_LOG\"\n"
       "printf '%s\\n' \"$@\" > \"$CLOJURE_STUB_ARGV\"\n"
       "if [ \"${CLOJURE_STUB_RESOLVE:-}\" = 1 ]; then\n"
       "  echo \"cp_file = /tmp/none.cp\"\n"
       "  printf '%s\\n' \"${CLOJURE_STUB_CP:-" fake-cp "}\"\n"
       "  exit 0\n"
       "fi\n"
       "exit 42\n"))

(def jolt-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$JOLT_STUB_LOG\"\n"
       "if [ \"$1\" = \"-Srepro\" ] && [ \"$2\" = \"-Spath\" ]; then\n"
       "  printf '%s\\n' \"${JOLT_STUB_CP:-" fake-cp "}\"\n"
       "  exit 0\n"
       "fi\n"
       "if [ \"$1\" = \"-Scp\" ]; then\n"
       "  exit 0\n"
       "fi\n"
       "echo \"unexpected jolt: $*\" >&2\n"
       "exit 9\n"))

(def champaca-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$@\" > \"$CHAMPACA_ARGV\"\n"
       "printf '%s\\n' \"$JAVA_HOME\" > \"$CHAMPACA_JAVA_HOME\"\n"
       "printf '%s\\n' \"$CHAMPACA_BUNDLE\" > \"$CHAMPACA_BUNDLE_FILE\"\n"
       "printf '%s\\n' \"$CHAMPACA_CACHE_DIR\" > \"$CHAMPACA_CACHE_FILE\"\n"
       "exit \"${CHAMPACA_STUB_EXIT:-0}\"\n"))

(defn- slurp-lines [f]
  (when (fs/exists? f) (str/split-lines (slurp (str f)))))

(defn- slurp-1 [f]
  (when (fs/exists? f) (str/trim (slurp (str f)))))

(defn- child-env [dir bin logs c bundle]
  (let [base (-> (into {} (System/getenv))
                 (dissoc "COHOST_CLJ_BODY" "COHOST_CLJ_BUNDLE" "JOLT_STUB_CP" "CHAMPACA_STUB_EXIT"))]
    (cond-> (merge base
                   {"PATH" (str bin)
                    "HOME" (str dir)
                    "CLOJURE_STUB_LOG" (str (:clojure logs))
                    "CLOJURE_STUB_ARGV" (str (:argv logs))
                    "JOLT_STUB_LOG" (str (:jolt logs))
                    "JOLT_STUB_CP" fake-cp
                    "CHAMPACA_ARGV" (str (:champaca-argv logs))
                    "CHAMPACA_JAVA_HOME" (str (:java-home logs))
                    "CHAMPACA_BUNDLE_FILE" (str (:bundle-file logs))
                    "CHAMPACA_CACHE_FILE" (str (:cache-file logs))})
      (:body c) (assoc "COHOST_CLJ_BODY" (:body c))
      bundle (assoc "COHOST_CLJ_BUNDLE" (str bundle))
      (:missing-bundle c) (assoc "COHOST_CLJ_BUNDLE" (str (fs/file dir "missing-bundle")))
      (:exit-code c) (assoc "CHAMPACA_STUB_EXIT" (str (:exit-code c)))
      (:clojure-resolves c) (assoc "CLOJURE_STUB_RESOLVE" "1"))))

(defn- invoke [dir env args]
  @(p/process (into [bb-bin cohost] args)
              {:dir (str dir) :env env :out :string :err :string}))

(defn- expected-champaca-argv [bundle main-args]
  (into ["-cp"
         (str (fs/file bundle "app") "/*" path-sep fake-cp)
         "clojure.main"]
        main-args))

(defn- check [c r logs dir bundle]
  (let [name (:name c)
        err (str (:err r))
        msg (fn [extra] (str name "\n" extra "\n--- stdout ---\n" (:out r) "\n--- stderr ---\n" err))
        argv (slurp-lines (:argv logs))]
    (case (:expect c)
      :clojure
      (do (t/is (= 42 (:exit r)) (msg "exit"))
          (when-let [exact (:argv c)]
            (t/is (= exact argv) (msg "argv")))
          (doseq [piece (:argv-includes c)]
            (t/is (some #(str/includes? % piece) argv) (msg (str "argv includes " piece))))
          (t/is (fs/exists? (:clojure logs)) (msg "clojure was not launched"))
          (t/is (not (fs/exists? (:jolt logs))) (msg "jolt ran on the clojure path"))
          (t/is (not (fs/exists? (:champaca-argv logs))) (msg "champaca ran on the clojure path")))
      :champaca
      (do (t/is (= (or (:exit-code c) 0) (:exit r)) (msg "exit"))
          (t/is (= (expected-champaca-argv bundle (:main-args c))
                   (slurp-lines (:champaca-argv logs)))
                (msg "champaca argv"))
          (t/is (= (str (fs/file bundle "runtime")) (slurp-1 (:java-home logs))) (msg "JAVA_HOME"))
          (t/is (= (str bundle) (slurp-1 (:bundle-file logs))) (msg "CHAMPACA_BUNDLE"))
          (t/is (= (str (fs/file dir ".cache/cohost/champaca")) (slurp-1 (:cache-file logs)))
                (msg "CHAMPACA_CACHE_DIR"))
          (t/is (fs/exists? (fs/file dir ".cache/cohost/champaca")) (msg "cache dir created"))
          (t/is (= [(or (:jolt-args c) "-Srepro -Spath")] (slurp-lines (:jolt logs)))
                (msg "jolt -Spath args"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure launched on the champaca path")))
      :native-jolt
      (let [lines (or (slurp-lines (:jolt logs)) [])]
        (t/is (zero? (:exit r)) (msg "exit"))
        (t/is (some #(str/includes? % "-Scp") lines) (msg "native -Scp"))
        (t/is (some #(str/includes? % "cohost.cli") lines) (msg "cohost.cli"))
        (when (:spath c)
          (t/is (some #(str/includes? % "-Srepro -Spath") lines) (msg "jolt -Spath")))
        (t/is (not (fs/exists? (:champaca-argv logs))) (msg "champaca replaced the host"))
        (if (:clojure c)
          (t/is (fs/exists? (:clojure logs)) (msg "clojure resolver"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure was launched"))))
      :error
      (do (t/is (= 1 (:exit r)) (msg "exit"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure launched"))
          (t/is (not (fs/exists? (:champaca-argv logs))) (msg "champaca launched"))
          (doseq [piece (:stderr-includes c)]
            (t/is (str/includes? err piece) (msg (str "stderr includes " piece))))
          (when-not (:jolt-ran c)
            (t/is (not (fs/exists? (:jolt logs))) (msg "jolt ran")))
          (when (:default-bundle c)
            (t/is (str/includes? err (str (fs/file dir ".cache/cohost/cohost-clj")))
                  (msg "default bundle path")))))
    (when (:jolt-ran c)
      (t/is (fs/exists? (:jolt logs)) (msg "jolt did not run")))))

(defn- install-bundle [dir]
  (let [b (fs/file dir "bundle")]
    (fs/create-dirs (fs/file b "lib"))
    (spit-exe (fs/file b "lib/champaca") champaca-stub)
    b))

(defn- run-case! [c]
  (let [dir (fs/create-temp-dir {:prefix "cohost-clj-body-"})
        bin (fs/file dir "bin")
        logs {:clojure (fs/file dir "clojure.log")
              :argv (fs/file dir "clojure.argv")
              :jolt (fs/file dir "jolt.log")
              :champaca-argv (fs/file dir "champaca.argv")
              :java-home (fs/file dir "champaca.java-home")
              :bundle-file (fs/file dir "champaca.bundle")
              :cache-file (fs/file dir "champaca.cache")}
        bundle (when (:bundle c) (install-bundle dir))]
    (try
      (when (:deps c) (spit (str (fs/file dir "deps.edn")) (pr-str (:deps c))))
      (fs/create-dirs bin)
      (when (:clojure c) (spit-exe (fs/file bin "clojure") clojure-stub))
      (when (:node c) (spit-exe (fs/file bin "node") "#!/bin/sh\nexit 0\n"))
      (when (:jolt c) (spit-exe (fs/file bin "jolt") jolt-stub))
      (check c (invoke dir (child-env dir bin logs c bundle) (:args c)) logs dir bundle)
      (finally (fs/delete-tree dir)))))

(def cases
  [{:name "clojure on PATH keeps the existing launch; implicit -e gains one -M"
    :args [":jvm" "-e" "(+ 1 2)"]
    :clojure true
    :expect :clojure
    :argv ["-M" "-e" "(+ 1 2)"]}
   {:name "explicit -M is not rewritten"
    :args [":jvm" "-M" "-e" "(+ 1 2)"]
    :clojure true
    :expect :clojure
    :argv ["-M" "-e" "(+ 1 2)"]}
   {:name "-T keeps the template dep"
    :args [":jvm" "-Tnew" "create"]
    :clojure true
    :expect :clojure
    :argv-includes ["-Sdeps" "templates/cohost-app" "-Tnew"]}
   {:name "-T merges into an existing -Sdeps"
    :args [":jvm" "-Sdeps" "{:deps {foo/bar {:mvn/version \"1\"}}}" "-Tnew"]
    :clojure true
    :expect :clojure
    :argv-includes ["templates/cohost-app" "foo/bar" "-Tnew"]}
   {:name "-Sdescribe stays the clojure CLI"
    :args [":jvm" "-Sdescribe"]
    :clojure true
    :expect :clojure
    :argv ["-Sdescribe"]}
   {:name "-Sdescribe plus an implicit main keeps the old argument order"
    :args [":jvm" "-Sdescribe" "-e" "1"]
    :clojure true
    :expect :clojure
    :argv ["-Sdescribe" "-M" "-e" "1"]}
   {:name "clojure wins when jolt is also on PATH"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
    :bundle true
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "-T stays on clojure even when the override asks for jolt"
    :args [":jvm" "-Tnew" "create"]
    :clojure true
    :jolt true
    :bundle true
    :body "jolt"
    :expect :clojure
    :argv-includes ["-Tnew" "templates/cohost-app"]}
   {:name "no clojure and jolt present runs champaca with args after -M"
    :args [":jvm" "-e" "(+ 1 2)"]
    :jolt true
    :bundle true
    :expect :champaca
    :main-args ["-e" "(+ 1 2)"]}
   {:name "-m args pass through after the inserted -M"
    :args [":jvm" "-m" "demo.main" "a" "b"]
    :jolt true
    :bundle true
    :expect :champaca
    :main-args ["-m" "demo.main" "a" "b"]}
   {:name "-M:alias prepends :main-opts and passes the alias to jolt"
    :args [":jvm" "-M:main" "-g" "hi" "-n" "2" "jolt-user"]
    :deps {:aliases {:main {:main-opts ["-m" "demo.main"]}}}
    :jolt true
    :bundle true
    :expect :champaca
    :jolt-args "-Srepro -Spath -M:main"
    :main-args ["-m" "demo.main" "-g" "hi" "-n" "2" "jolt-user"]}
   {:name "-M:a:b concatenates :main-opts left to right"
    :args [":jvm" "-M:a:b" "-m" "user.main"]
    :deps {:aliases {:a {:main-opts ["-e" "1"]}
                     :b {:main-opts ["-e" "2"]}}}
    :jolt true
    :bundle true
    :expect :champaca
    :jolt-args "-Srepro -Spath -M:a:b"
    :main-args ["-e" "1" "-e" "2" "-m" "user.main"]}
   {:name "-A then -M keeps both aliases on the jolt classpath and in :main-opts"
    :args [":jvm" "-A:extra" "-M:main" "arg"]
    :deps {:aliases {:extra {:main-opts ["-e" ":extra"]}
                     :main {:main-opts ["-m" "demo.main"]}}}
    :jolt true
    :bundle true
    :expect :champaca
    :jolt-args "-Srepro -Spath -A:extra -M:main"
    :main-args ["-e" ":extra" "-m" "demo.main" "arg"]}
   {:name "-X:alias invokes the exec fn with merged args"
    :args [":jvm" "-X:run" ":user" "\"jolt-user\""]
    :deps {:aliases {:run {:exec-fn 'demo.core/run
                           :exec-args {:user "default"}}}}
    :jolt true
    :bundle true
    :expect :champaca
    :jolt-args "-Srepro -Spath -X:run"
    :main-args ["-e" "(do ((requiring-resolve 'demo.core/run) {:user \"jolt-user\"}) nil)"]}
   {:name "override jolt runs champaca even when clojure is on PATH"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
    :bundle true
    :body "jolt"
    :expect :champaca
    :main-args ["-e" "1"]}
   {:name "override Jolt is case-insensitive"
    :args [":jvm" "-m" "demo.main"]
    :clojure true
    :jolt true
    :bundle true
    :body " Jolt "
    :expect :champaca
    :main-args ["-m" "demo.main"]}
   {:name "a missing bundle is a build instruction"
    :args [":jvm" "-e" "1"]
    :jolt true
    :missing-bundle true
    :expect :error
    :stderr-includes ["missing lib/champaca" "build-clj-bundle.sh" "COHOST_CLJ_BUNDLE" "CHAMPACA_SRC"]}
   {:name "the default bundle path is under the cohost cache"
    :args [":jvm" "-e" "1"]
    :jolt true
    :default-bundle true
    :expect :error
    :stderr-includes ["build-clj-bundle.sh"]}
   {:name "-T with no clojure stays clojure-only"
    :args [":cljs" "-Tnew"]
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["-T runs on the `clojure` CLI only" "champaca :clj leg does not run this command"]}
   {:name "-Sdescribe with no clojure stays clojure-only"
    :args [":jolt" "-Sdescribe"]
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["-Sdescribe runs on the `clojure` CLI only"]}
   {:name "-T with override jolt and no clojure is still clojure-only"
    :args [":jvm" "-Tnew"]
    :jolt true
    :bundle true
    :body "jolt"
    :expect :error
    :stderr-includes ["-T runs on the `clojure` CLI only"]}
   {:name "neither clojure nor jolt is a clear error"
    :args [":jvm" "-e" "1"]
    :expect :error
    :stderr-includes ["`clojure`, which is not on PATH" "champaca backup is unavailable"]}
   {:name "override clojure keeps the existing launch"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
    :bundle true
    :body "clojure"
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "a blank override follows PATH"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
    :body "  "
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "override clojure with no clojure is an error"
    :args [":jvm" "-e" "1"]
    :jolt true
    :bundle true
    :body "clojure"
    :expect :error
    :stderr-includes ["COHOST_CLJ_BODY=clojure" "`clojure` is not on PATH"]}
   {:name "override jolt with no jolt is an error"
    :args [":jvm" "-e" "1"]
    :clojure true
    :body "jolt"
    :expect :error
    :stderr-includes ["COHOST_CLJ_BODY=jolt" "`jolt` is not on PATH"]}
   {:name "an unknown override is a diagnostic"
    :args [":jvm" "-e" "1"]
    :clojure true
    :body "auto"
    :expect :error
    :stderr-includes ["COHOST_CLJ_BODY must be clojure or jolt"]}
   {:name "a native host is not the :clj leg"
    :args [":jank" "-e" "1"]
    :clojure true
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}
   {:name "lone :jolt with no clojure boots the native adapter via jolt -Spath"
    :args [":jolt" "-e" "1"]
    :jolt true
    :bundle true
    :spath true
    :expect :native-jolt}
   {:name ":jolt:lpy boots the native jolt adapter, not a champaca process"
    :args [":jolt:lpy" "-e" "1"]
    :jolt true
    :bundle true
    :spath true
    :expect :native-jolt}
   {:name ":jolt:lpy uses the clojure resolver when clojure is on PATH"
    :args [":jolt:lpy" "-e" "1"]
    :clojure true
    :clojure-resolves true
    :jolt true
    :bundle true
    :expect :native-jolt}
   {:name ":lpy:jolt boots basilisp; champaca stays a recorded sibling"
    :args [":lpy:jolt" "-e" "1"]
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["basilisp" "not on PATH"]}
   {:name ":jank:jolt boots jank, not champaca"
    :args [":jank:jolt" "-e" "1"]
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}
   {:name ":clj:jolt is the official clojure CLI because :clj is first"
    :args [":clj:jolt" "-e" "1"]
    :clojure true
    :jolt true
    :bundle true
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name ":jolt:clj keeps jolt as the process"
    :args [":jolt:clj" "-e" "1"]
    :jolt true
    :bundle true
    :spath true
    :expect :native-jolt}
   {:name ":lpy:clj boots basilisp; :clj is recorded, not launched"
    :args [":lpy:clj" "-e" "1"]
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["basilisp" "not on PATH"]}
   {:name "a missing clojure on a non-jolt host is a clear die"
    :args [":cljs" "-e" "1"]
    :node true
    :expect :error
    :stderr-includes ["`clojure` failed to start" "not on PATH"]}
   {:name "a lone :lpy keeps basilisp and does not take champaca"
    :args [":lpy" "-e" "1"]
    :clojure true
    :jolt true
    :bundle true
    :expect :error
    :stderr-includes ["basilisp" "not on PATH"]}
   {:name "the default host stays jank"
    :args []
    :clojure true
    :jolt true
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}
   {:name "champaca's exit code is the cohost exit code"
    :args [":jvm" "-e" "1"]
    :jolt true
    :bundle true
    :expect :champaca
    :main-args ["-e" "1"]
    :exit-code 7}])

(def clj-leg-rows
  [{:name ":jolt" :hosts ["jolt"] :provider :champaca :owner "jolt"}
   {:name ":jolt:lpy" :hosts ["jolt" "lpy"] :provider :champaca :owner "jolt"}
   {:name ":lpy:jolt" :hosts ["lpy" "jolt"] :provider :champaca :owner "jolt"}
   {:name ":lpy" :hosts ["lpy"] :provider nil :owner "lpy"}
   {:name ":clj:jolt" :hosts ["clj" "jolt"] :provider :clojure-cli :owner "clj"}
   {:name ":jolt:clj" :hosts ["jolt" "clj"] :provider :clojure-cli :owner "clj"}
   {:name ":lpy:clj" :hosts ["lpy" "clj"] :provider :clojure-cli :owner "clj"}
   {:name ":jank:jolt" :hosts ["jank" "jolt"] :provider :champaca :owner "jolt"}
   {:name ":jvm" :hosts ["jvm"] :provider :jvm :owner "jvm"}
   {:name ":jank" :hosts ["jank"] :provider nil :owner "jank"}])

(t/deftest clj-arm-route
  (let [src "#?(:clj (+ 1 2) :jolt :no)"]
    (t/is (str/includes? (cohost.scan/resolve-conditionals src [:clj :default]) "(+ 1 2)"))
    (reset! cohost.scan/clj-arm-route
            (fn [body] (pr-str (list 'cohost.clj-champaca/eval-arm! body))))
    (try
      (let [out (cohost.scan/resolve-conditionals src [:clj :default])]
        (t/is (str/includes? out "cohost.clj-champaca/eval-arm!"))
        (t/is (str/includes? out "(+ 1 2)"))
        (t/is (not (str/includes? out ":no"))))
      (finally (reset! cohost.scan/clj-arm-route nil)))))

(t/deftest clj-leg-resolution
  (doseq [{:keys [name hosts provider owner]} clj-leg-rows]
    (t/testing name
      (let [plan (cohost.clj-body/resolve-clj-leg! hosts)]
        (t/is (= provider (:provider plan)) name)
        (t/is (= owner (:owner plan)) name)
        (t/is (= hosts (:hosts plan)) name)
        (t/is (= plan @cohost.clj-body/clj-leg) name)))))

(t/deftest a-later-host-can-declare-a-clj-leg
  (let [before @cohost.clj-body/clj-providers]
    (try
      (swap! cohost.clj-body/clj-providers assoc "lpy" :lpy-clj)
      (t/is (= :lpy-clj (:provider (cohost.clj-body/resolve-clj-leg! ["lpy" "jolt"]))))
      (t/is (= :clojure-cli (:provider (cohost.clj-body/resolve-clj-leg! ["lpy" "clj"]))))
      (finally
        (reset! cohost.clj-body/clj-providers before)
        (cohost.clj-body/resolve-clj-leg! [])))))

(t/deftest detection-matrix
  (t/is (fs/exists? bb-bin) (str "babashka not found at " bb-bin))
  (doseq [c cases]
    (t/testing (:name c)
      (run-case! c))))

(defn- run-help! []
  (let [dir (fs/create-temp-dir {:prefix "cohost-clj-body-help-"})
        bin (fs/file dir "bin")
        logs {:clojure (fs/file dir "clojure.log")
              :argv (fs/file dir "clojure.argv")
              :jolt (fs/file dir "jolt.log")
              :champaca-argv (fs/file dir "champaca.argv")
              :java-home (fs/file dir "champaca.java-home")
              :bundle-file (fs/file dir "champaca.bundle")
              :cache-file (fs/file dir "champaca.cache")}]
    (try
      (fs/create-dirs bin)
      (spit-exe (fs/file bin "clojure") clojure-stub)
      (spit-exe (fs/file bin "jolt") jolt-stub)
      (let [r (invoke dir (child-env dir bin logs {} nil) ["-h"])
            err (str (:err r))]
        (t/is (= 2 (:exit r)) err)
        (t/is (str/includes? err "usage: cohost") err)
        (t/is (str/includes? err "else :jank") err)
        (t/is (str/includes? err "COHOST_CLJ_BODY=clojure|jolt") err)
        (t/is (not (fs/exists? (:clojure logs))) err)
        (t/is (not (fs/exists? (:jolt logs))) err))
      (finally (fs/delete-tree dir)))))

(t/deftest help-skips-detection
  (t/is (fs/exists? bb-bin))
  (run-help!))

(let [{:keys [fail error]} (t/run-tests 'cohost.clj-body-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
