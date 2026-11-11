(ns cohost.clj-body-test
  "The :clj leg: `clojure` on PATH keeps the existing launch; otherwise jolt
   on PATH is the champaca backup. Each case runs bin/cohost with PATH
   limited to a temp directory of stub binaries, so a real clojure or jolt
   on the machine is not visible.

   bb dev/clj_body_test.clj
   bb clj-body:test"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :as t]))

(def root (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(def bb-bin
  (str (or (fs/which "bb")
           (let [p (fs/file (System/getenv "HOME") ".local/bin/bb")]
             (when (fs/exists? p) p)))))
(def cohost (str (fs/file root "bin/cohost")))

(defn- spit-exe [path text]
  (spit (str path) text)
  (.setExecutable (java.io.File. (str path)) true))

(def clojure-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$CLOJURE_STUB_LOG\"\n"
       "printf '%s\\n' \"$@\" > \"$CLOJURE_STUB_ARGV\"\n"
       "exit 42\n"))

(def jolt-stub
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$*\" >> \"$JOLT_STUB_LOG\"\n"
       "echo \"jolt stub should not be executed: $*\" >&2\n"
       "exit 9\n"))

(defn- slurp-lines [f]
  (when (fs/exists? f) (str/split-lines (slurp (str f)))))

(defn- child-env [bin logs c]
  (let [base (-> (into {} (System/getenv))
                 (dissoc "COHOST_CLJ_BODY"))]
    (cond-> (merge base
                   {"PATH" (str bin)
                    "HOME" (or (System/getenv "HOME") (str bin))
                    "CLOJURE_STUB_LOG" (str (:clojure logs))
                    "CLOJURE_STUB_ARGV" (str (:argv logs))
                    "JOLT_STUB_LOG" (str (:jolt logs))})
      (:body c) (assoc "COHOST_CLJ_BODY" (:body c)))))

(defn- invoke [dir env args]
  @(p/process (into [bb-bin cohost] args)
              {:dir (str dir) :env env :out :string :err :string}))

(defn- check [c r logs dir]
  (let [name (:name c)
        err (str (:err r))
        msg (fn [extra] (str name "\n" extra "\n--- stdout ---\n" (:out r) "\n--- stderr ---\n" err))
        argv (slurp-lines (:argv logs))]
    (case (:expect c)
      :clojure
      (do (t/is (= 42 (:exit r)) (msg "exit"))
          (t/is (not (str/includes? err ":boot :stub")) (msg "clojure path must stay off the champaca stub"))
          (when-let [exact (:argv c)]
            (t/is (= exact argv) (msg "argv")))
          (doseq [piece (:argv-includes c)]
            (t/is (some #(str/includes? % piece) argv) (msg (str "argv includes " piece))))
          (t/is (fs/exists? (:clojure logs)) (msg "clojure was not launched")))
      :stub
      (do (t/is (= 1 (:exit r)) (msg "exit"))
          (t/is (str/includes? err "jolt's :clj handler on the champaca path") (msg "stub banner"))
          (t/is (str/includes? err ":boot :stub") (msg "plan boot"))
          (t/is (str/includes? err ":runtime :champaca") (msg "plan runtime"))
          (t/is (str/includes? err (pr-str (:plan-args c))) (msg "plan args"))
          (t/is (str/includes? err (str (fs/canonicalize dir))) (msg "plan cwd"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure launched on the stub path")))
      :error
      (do (t/is (= 1 (:exit r)) (msg "exit"))
          (t/is (not (str/includes? err ":boot :stub")) (msg "error must not select the stub"))
          (t/is (not (fs/exists? (:clojure logs))) (msg "clojure launched"))
          (doseq [piece (:stderr-includes c)]
            (t/is (str/includes? err piece) (msg (str "stderr includes " piece))))))
    (t/is (not (fs/exists? (:jolt logs))) (msg "jolt was executed; the backup only checks PATH"))))

(defn- run-case! [c]
  (let [dir (fs/create-temp-dir {:prefix "cohost-clj-body-"})
        bin (fs/file dir "bin")
        logs {:clojure (fs/file dir "clojure.log")
              :argv (fs/file dir "clojure.argv")
              :jolt (fs/file dir "jolt.log")}]
    (try
      (fs/create-dirs bin)
      (when (:clojure c) (spit-exe (fs/file bin "clojure") clojure-stub))
      (when (:jolt c) (spit-exe (fs/file bin "jolt") jolt-stub))
      (check c (invoke dir (child-env bin logs c) (:args c)) logs dir)
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
    :expect :clojure
    :argv ["-M" "-e" "1"]}
   {:name "no clojure, jolt present, selects the champaca stub"
    :args [":jvm" "-e" "(+ 1 2)"]
    :jolt true
    :expect :stub
    :plan-args ["-e" "(+ 1 2)"]}
   {:name "-T with no clojure uses the champaca stub"
    :args [":cljs" "-Tnew"]
    :jolt true
    :expect :stub
    :plan-args ["-Tnew"]}
   {:name "-Sdescribe with no clojure uses the champaca stub"
    :args [":jolt" "-Sdescribe"]
    :jolt true
    :expect :stub
    :plan-args ["-Sdescribe"]}
   {:name ":jvm -Spath with no clojure is the champaca stub"
    :args [":jvm" "-Spath"]
    :jolt true
    :expect :stub
    :plan-args ["-Spath"]}
   {:name "neither clojure nor jolt is a clear error"
    :args [":jvm" "-e" "1"]
    :expect :error
    :stderr-includes ["`clojure`, which is not on PATH" "champaca backup is unavailable"]}
   {:name "override jolt selects the stub even when clojure is on PATH"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
    :body "jolt"
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "override Jolt is case-insensitive"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
    :body " Jolt "
    :expect :stub
    :plan-args ["-e" "1"]}
   {:name "override clojure keeps the existing launch"
    :args [":jvm" "-e" "1"]
    :clojure true
    :jolt true
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
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}
   {:name "the default host stays jank"
    :args []
    :clojure true
    :jolt true
    :expect :error
    :stderr-includes ["jank" "not on PATH"]}])

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
              :jolt (fs/file dir "jolt.log")}]
    (try
      (fs/create-dirs bin)
      (spit-exe (fs/file bin "clojure") clojure-stub)
      (spit-exe (fs/file bin "jolt") jolt-stub)
      (let [r (invoke dir (child-env bin logs {}) ["-h"])
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
