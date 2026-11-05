;; u4r probe: eduction launch-shape diagnostic.
;; Boots with the Eve collections fill OFF (the failure occurs in both
;; fill modes), then evals the battery row + class-identity diagnostics
;; inside the booted context via boot/eval-str.
;; Run:  clojure -M:eve dev/u4r_probe.clj     (fails)
;;       clojure       dev/u4r_probe.clj     (passes)
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter)
(alias 'boot 'cohost.boot)

(println "=== classpath (first 6 entries) ===")
(doseq [e (take 6 (.split (System/getProperty "java.class.path") ":"))]
  (println " CP:" e))
(println "=== host clojure.core.Eduction ===")
(println " loader:" (.getClassLoader clojure.core.Eduction))
(println " source:" (.getCodeSource (.getProtectionDomain clojure.core.Eduction)))

(def report (boot/boot! {:eve-collections false}))
(println "BOOT errors:" (clojure.core/count (:errors report)) "fill:" (:eve-collections report))
(boot/make-user-ns!)

(defn ev [label s]
  (println "\n---" label "---")
  (println " form:" s)
  (try
    (let [v (boot/eval-str s)]
      (println " =>" (pr-str v)))
    (catch Throwable t
      (println " HOST-CAUGHT:" (.getName (class t)) (.getMessage t)))))

(def diag-forms
  [["row"        "(reduce + (eduction (filter odd?) [1 2 3 4 5]))"]
   ["row-stack"  "(try (reduce + (eduction (filter odd?) [1 2 3 4 5])) (catch Throwable t (str \"THREW \" (.getName (class t)) \": \" (.getMessage t) \"\\n\" (apply str (interpose \"\\n\" (map str (take 12 (.getStackTrace t))))))))"]
   ["e-class"    "(str (class (eduction (filter odd?) [1 2 3])))"]
   ["e-loader"   "(str (.getClassLoader (class (eduction (filter odd?) [1 2 3]))))"]
   ["e-source"   "(str (.getCodeSource (.getProtectionDomain (class (eduction (filter odd?) [1 2 3])))))"]
   ["lit-loader" "(str (.getClassLoader clojure.core.Eduction))"]
   ["lit-source" "(str (.getCodeSource (.getProtectionDomain clojure.core.Eduction)))"]
   ["identical"  "(identical? (class (eduction (filter odd?) [1 2 3])) clojure.core.Eduction)"]
   ["supers"     "(str (supers (class (eduction (filter odd?) [1 2 3]))))"]
   ["inst-init"  "(instance? clojure.lang.IReduceInit (eduction (filter odd?) [1 2 3]))"]
   ["inst-red"   "(instance? clojure.lang.IReduce (eduction (filter odd?) [1 2 3]))"]
   ["iri-loader" "(str (.getClassLoader clojure.lang.IReduceInit))"]
   ["ir-loader"  "(str (.getClassLoader clojure.lang.IReduce))"]
   ["direct"     "(.reduce (eduction (filter odd?) [1 2 3 4 5]) +)"]
   ["ctrl-filter" "(reduce + (filter odd?) [1 2 3 4 5])"]
   ["ctrl-seq"   "(reduce + [1 2 3 4 5])"]
   ["meta-part"  "(meta ^{:tag :int} [1 2])"]])

(doseq [[label form] diag-forms] (ev label form))
(println "\n=== probe done ===")
(shutdown-agents)
(System/exit 0)
