(ns cohost.clj-body
  "Which runtime serves cohost's :clj body (the :jvm host).

   A usable JVM keeps the real Clojure CLI. With no JVM, jolt >= 0.8.24
   whose champaca runtime is present is the other body. A JVM that is
   present but broken or too old is a diagnostic: auto does not fall
   through to jolt.

   The champaca boot itself is a stub. jolt's native :jolt adapter (the
   \"jolt\" branch of launch!) is a different path and is not selected here.

   John, 00:50 ET: the memory law is waived only for champaca's
   guest-private JVM/Chez internals (weak and phantom ref scans,
   per-object monitor header counts, malloc/Unsafe scratch buffers,
   FFM arenas). Those live inside champaca, which this file does not
   implement. On the cohost side the probes are locals of this decision
   and are not retained. Do not add a scanner, a header counter, a
   journal, or an arena here. State that outlives the call would be an
   atom; a temporary that outlives its let would be that scope's local
   atom."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;; Clojure 1.12 runs on Java 8 or newer. Champaca's own docs ask for JDK 25
;; to read a JDK image; that is the spike's problem, not this gate.
(def java-min-feature 8)

;; First jolt release the champaca path is allowed to target.
(def jolt-min-version [0 8 24])

(def champaca-ok-token "cohost-champaca-ok")

;; Presence only. Loading the namespace is not the champaca boot, and it may
;; itself need a JDK until the spike has a bundle that answers without one.
;; Runs in the project directory so a project dep on champaca is visible.
(def champaca-probe-form
  "(try (require 'champaca.api) (println \"cohost-champaca-ok\") (catch Throwable _ (println \"cohost-champaca-missing\")))")

(defn parse-java-version
  "Feature version from `java -version` text, or nil.
   \"1.8.0_392\" -> 8, \"21.0.2\" -> 21, \"25\" -> 25."
  [text]
  (when-let [[_ raw] (re-find #"version \"([^\"]+)\"" (or text ""))]
    (let [nums (->> (str/split raw #"[^0-9]+")
                    (remove str/blank?)
                    (mapv parse-long))]
      (when (seq nums)
        (if (= 1 (first nums))
          (or (second nums) 0)
          (first nums))))))

(defn parse-jolt-version
  "Numeric [major minor patch] from `jolt --version` text, or nil.
   \"jolt v0.8.24\" and \"jolt v0.8.24-82-gabcdef\" -> [0 8 24].
   \"jolt dev\" and \"jolt dev-g<sha>\" have no number (jolt's own floor
   treats an unnumbered version as not meeting one)."
  [text]
  (when-let [[_ a b c] (re-find #"(\d+)\.(\d+)\.(\d+)" (or text ""))]
    [(parse-long a) (parse-long b) (parse-long c)]))

(defn version-at-least?
  [got floor]
  (and (vector? got) (vector? floor) (<= 0 (compare got floor))))

(defn- version-str [v]
  (if (vector? v) (str/join "." v) (str v)))

(defn- clip [s]
  (let [t (-> (or s "") str/trim (str/replace #"\s+" " "))]
    (if (<= (count t) 240) t (str (subs t 0 240) "..."))))

(defn- run-captured
  "-> {:exit :out :err}. A missing or unstartable program is exit 127."
  [cmd cwd]
  (try
    (let [opts (cond-> {:out :string :err :string}
                 cwd (assoc :dir (str cwd)))
          r @(p/process (vec cmd) opts)]
      {:exit (or (:exit r) 127) :out (str (:out r)) :err (str (:err r))})
    (catch Exception e
      {:exit 127 :out "" :err (or (.getMessage e) "failed to run")})))

(defn- java-home-path [home]
  (let [bin (fs/file home "bin")
        found (first (filter #(and % (fs/exists? %))
                             [(fs/file bin "java") (fs/file bin "java.exe")]))]
    (str (or found (fs/file bin "java")))))

(defn- jvm-location [{:keys [source path]}]
  (case source
    :java-home (str "JAVA_HOME (" path ")")
    :path (str "java on PATH (" path ")")
    "no java on PATH and JAVA_HOME is unset"))

(defn- probe-java-candidate
  [{:keys [path] :as loc}]
  (if-not (and path (fs/exists? path))
    (assoc loc :status :broken :reason (str "no java executable at " path))
    (let [{:keys [exit out err]} (run-captured [path "-version"] nil)
          feature (or (parse-java-version err) (parse-java-version out))
          shown (clip (str err " " out))]
      (cond
        (not (zero? exit))
        (assoc loc :status :broken :exit exit :feature feature
               :reason (str "java -version exited " exit
                            (when (seq shown) (str ": " shown))))
        (nil? feature)
        (assoc loc :status :broken :exit exit
               :reason (str "java -version exited 0 but printed no version"
                            (when (seq shown) (str ": " shown))))
        (< feature java-min-feature)
        (assoc loc :status :unsupported :exit exit :feature feature
               :reason (str "version " feature " is below the minimum "
                            java-min-feature " (Clojure 1.12)"))
        :else
        (assoc loc :status :usable :exit exit :feature feature)))))

(defn probe-jvm
  "JAVA_HOME when it is set (even if that install is broken: no silent
   fallthrough to PATH), else `java` on PATH, else :absent."
  []
  (let [home (System/getenv "JAVA_HOME")]
    (if-not (str/blank? home)
      (probe-java-candidate {:source :java-home :home home :path (java-home-path home)})
      (if-let [p (or (fs/which "java") (fs/which "java.exe"))]
        (probe-java-candidate {:source :path :path (str p)})
        {:status :absent}))))

(defn probe-jolt
  "jolt on PATH, version >= jolt-min-version, and the champaca probe token.
   Does not boot champaca."
  [cwd]
  (if-let [bin (fs/which "jolt")]
    (let [bin (str bin)
          ver (run-captured [bin "--version"] nil)
          parsed (when (zero? (:exit ver))
                   (or (parse-jolt-version (:out ver))
                       (parse-jolt-version (:err ver))))
          shown (clip (str (:out ver) " " (:err ver)))]
      (cond
        (not (zero? (:exit ver)))
        {:status :broken :path bin
         :reason (str "jolt --version exited " (:exit ver)
                      (when (seq shown) (str ": " shown)))}
        (nil? parsed)
        {:status :unnumbered :path bin
         :reason (str "jolt --version has no numeric version ("
                      (if (seq shown) shown "no output")
                      ") and does not meet >= " (version-str jolt-min-version))}
        (not (version-at-least? parsed jolt-min-version))
        {:status :old :path bin :version parsed
         :reason (str "jolt " (version-str parsed) " is older than "
                      (version-str jolt-min-version))}
        :else
        (let [probe (run-captured [bin "-e" champaca-probe-form] cwd)
              text (str (:out probe) "\n" (:err probe))]
          (if (str/includes? text champaca-ok-token)
            {:status :available :path bin :version parsed}
            {:status :no-champaca :path bin :version parsed
             :reason (str "jolt " (version-str parsed)
                          " is on PATH but champaca is not available (probe did not print "
                          champaca-ok-token ")"
                          (when (seq (clip text)) (str ": " (clip text))))}))))
    {:status :absent :reason "jolt is not on PATH"}))

(defn- read-override
  "COHOST_CLJ_BODY -> :auto, :jvm, :jolt, or an error map.
   Unset or blank is :auto. Comparison is case-insensitive."
  []
  (let [raw (System/getenv "COHOST_CLJ_BODY")
        token (some-> raw str/trim)]
    (if (str/blank? token)
      :auto
      (case (str/lower-case token)
        "auto" :auto
        "jvm" :jvm
        "jolt" :jolt
        {:code 1
         :lines [(str "cohost: COHOST_CLJ_BODY must be jvm, jolt, or auto (got "
                      (pr-str raw) ").")]}))))

(def ^:private no-fallback-line
  "  a broken or unsupported JVM does not fall back to jolt. Fix JAVA_HOME or the java on PATH, or remove it, or set COHOST_CLJ_BODY=jolt to select champaca explicitly.")

(defn- broken-jvm-lines [jvm]
  [(str "cohost: the JVM at " (jvm-location jvm) " is not usable (" (:reason jvm) ").")
   no-fallback-line])

(defn- jvm-only-lines [kind jvm]
  (let [what (case kind
               :tool "-T runs on the JVM only (tools are JVM programs)"
               :describe "-Sdescribe runs on the JVM only")
        detail (if (#{:broken :unsupported} (:status jvm))
                 (str (:reason jvm) " (" (jvm-location jvm) ").")
                 (str "no usable JVM: " (jvm-location jvm) "."))]
    [(str "cohost: " what " and will not use jolt's :clj body.")
     (str "  " detail)]))

(defn- neither-lines [jvm jolt]
  [(str "cohost: no usable JVM for the :clj body (" (jvm-location jvm)
        "), and jolt's champaca :clj handler is not available (" (:reason jolt) ").")
   "  install a JVM (JAVA_HOME or java on PATH, version 8 or newer), or jolt >= 0.8.24 with champaca."
   "  dev/install/jolt.sh pins v0.8.23 for the native :jolt adapter; that pin is below this floor."])

(defn- override-jvm-lines [jvm]
  [(str "cohost: COHOST_CLJ_BODY=jvm but the JVM is not usable ("
        (if (#{:broken :unsupported} (:status jvm))
          (str (:reason jvm) "; " (jvm-location jvm))
          (jvm-location jvm))
        ").")])

(defn- override-jolt-lines [jolt]
  [(str "cohost: COHOST_CLJ_BODY=jolt but jolt's champaca :clj handler is not available ("
        (:reason jolt) ").")])

(defn select-clj-body
  "Decide the :clj body.

   opts:
     :cwd       project directory for the champaca presence probe
     :jvm-only  :tool or :describe to refuse jolt (-T, -Sdescribe);
                nil for an ordinary :jvm command

   -> {:body :jvm|:jolt ...} or {:body :error :code n :lines [...]}."
  [{:keys [cwd jvm-only]}]
  (let [override (read-override)]
    (if (map? override)
      {:body :error :code (:code override) :lines (:lines override)}
      (let [jvm (probe-jvm)]
        (cond
          jvm-only
          (if (= :usable (:status jvm))
            {:body :jvm :jvm jvm}
            {:body :error :code 1 :lines (jvm-only-lines jvm-only jvm)})

          (= :jvm override)
          (if (= :usable (:status jvm))
            {:body :jvm :jvm jvm}
            {:body :error :code 1 :lines (override-jvm-lines jvm)})

          (= :jolt override)
          (let [jolt (probe-jolt cwd)]
            (if (= :available (:status jolt))
              {:body :jolt :jolt jolt :jvm jvm}
              {:body :error :code 1 :lines (override-jolt-lines jolt)}))

          (= :usable (:status jvm))
          {:body :jvm :jvm jvm}

          (#{:broken :unsupported} (:status jvm))
          {:body :error :code 1 :lines (broken-jvm-lines jvm)}

          :else
          (let [jolt (probe-jolt cwd)]
            (if (= :available (:status jolt))
              {:body :jolt :jolt jolt}
              {:body :error :code 1 :lines (neither-lines jvm jolt)})))))))

(defn jolt-clj-plan
  "The launch the champaca spike has to make real. :boot :stub means this
   tree does not execute it. Arguments are the CLI args the JVM path would
   have handed to `clojure`, without the leading clojure token."
  [cwd args]
  (array-map :handler :jolt-clj
             :runtime :champaca
             :boot :stub
             :cwd (str cwd)
             :args (vec args)))

(defn launch-jolt-clj!
  "Report the champaca :clj plan on stderr and return 1.
   jolt is not started: the boot is not proven. Callers System/exit the code."
  [cwd args]
  (let [plan (jolt-clj-plan cwd args)]
    (binding [*out* *err*]
      (println "cohost: :clj body selected jolt's :clj handler on the champaca path.")
      (println "  champaca boot is not proven yet (stub). The native :jolt adapter is not this path.")
      (println (str "  plan: " (pr-str plan))))
    1))
