(doseq [cn ["clojure.core.Eduction" "clojure.lang.IReduce" "clojure.lang.IReduceInit" "clojure.core.protocols"]]
  (let [c (Class/forName cn)]
    (println cn "->" (some-> c .getProtectionDomain .getCodeSource .getLocation))))
(println "IReduceInit supers:" (vec (.getInterfaces clojure.lang.IReduceInit)))
(println "user.dir:" (System/getProperty "user.dir"))
