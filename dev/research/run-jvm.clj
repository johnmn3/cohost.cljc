;; clojure -M dev/research/run-jvm.clj dev/research/<probe>.clj
;; Loads the JVM harness, then the probe, then calls (run).
(load-file "dev/research/harness.clj")
(load-file (first *command-line-args*))
((resolve 'user/run))
