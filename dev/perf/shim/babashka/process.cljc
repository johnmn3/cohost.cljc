;; babashka.process -- cohost's vendored slice for the snapshot
;; build: the one function, shell, in the shapes its call sites
;; use. See dev/perf/shim/babashka/fs.cljc for why this exists and
;; INSTALL-DISENTANGLE-STATUS.md for the census.
;;
;; Call-site shapes (dev/perf/jank_snapshot_key.clj,
;; jank_aot_ensure.clj):
;;   (shell argv...)                       stdio inherited;
;;                                         throws on nonzero exit
;;   (shell {:out :string} argv...)       stdout captured;
;;                                         (:out result) is read
;;   (shell {:out :string :in s} argv...) s (a string) fed to stdin
;;   (shell {:continue true} argv...)     never throws;
;;                                         (:exit result) is read
;; Like babashka.process/shell, the command runs directly (argv,
;; no sh -c) and stderr is inherited unless an option says
;; otherwise. The result is the map {:out <string or nil> :exit n}.
;; (The arms are (do ...) forms under #?: splicing reader
;; conditionals are not allowed at a file's top level.)
(ns babashka.process
  (:require [clojure.string :as str]))

(defn- fail! [opts argv out exit]
  (when (and (not (:continue opts)) (not (zero? exit)))
    (throw (ex-info (str "shell failed (" exit "): " (str/join " " argv))
                    {:exit exit :out out :argv argv}))))

#?(:clj
   (do
     (defn shell
       [& args]
       (let [[opts argv] (if (map? (first args)) [(first args) (rest args)] [{} args])
             argv (mapv str argv)
             pb (java.lang.ProcessBuilder. ^java.util.List argv)]
         (when-not (= :string (:out opts))
           (.redirectOutput pb java.lang.ProcessBuilder$Redirect/INHERIT))
         (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
         (when-not (contains? opts :in)
           (.redirectInput pb java.lang.ProcessBuilder$Redirect/INHERIT))
         (let [proc (.start pb)]
           (when (string? (:in opts))
             (with-open [w (.getOutputStream proc)]
               (.write w (.getBytes ^String (:in opts) "UTF-8"))))
           (let [out (when (= :string (:out opts)) (slurp (.getInputStream proc)))
                 exit (.waitFor proc)]
             (fail! opts argv out exit)
             {:out out :exit exit})))))

   :cljr
   (do
     (defn- quote-arg [a]
       (if (or (str/includes? a " ") (str/includes? a "\""))
         (str "\"" (str/replace a "\"" "\\\"") "\"")
         a))

     (defn shell
       [& args]
       (let [[opts argv] (if (map? (first args)) [(first args) (rest args)] [{} args])
             argv (mapv str argv)
             psi (System.Diagnostics.ProcessStartInfo.)]
         (set! (.FileName psi) (first argv))
         (set! (.Arguments psi) (str/join " " (map quote-arg (rest argv))))
         (set! (.UseShellExecute psi) false)
         (when (= :string (:out opts))
           (set! (.RedirectStandardOutput psi) true))
         (when (string? (:in opts))
           (set! (.RedirectStandardInput psi) true))
         (let [proc (System.Diagnostics.Process/Start psi)]
           (when (string? (:in opts))
             (let [w (.StandardInput proc)]
               (.Write w ^String (:in opts))
               (.Close w)))
           (let [out (when (= :string (:out opts))
                       (.ReadToEnd (.StandardOutput proc)))
                 _ (.WaitForExit proc)
                 exit (.ExitCode proc)]
             (fail! opts argv out exit)
             {:out out :exit exit}))))))
