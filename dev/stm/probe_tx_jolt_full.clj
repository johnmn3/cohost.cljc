;; STM lane (continuation): probe_tx (the 23 semantics checks) on the
;; JOLT seat, over HOST cells. Jolt has no Eve hosting joint loaded by
;; this script (residence over domains is probe_tx_domains_jolt.clj's
;; proven ground); refs seat in host atoms, as on the JVM seat's host
;; run. Run: jolt -Scp <src + Eve src + :jolt row> dev/stm/probe_tx_jolt_full.clj
;; (probe_tx.clj's own header says JVM; the script body is
;; seat-agnostic -- it requires cohost.hosts.auto, which installs the
;; jolt adapter here.)
(load-file "probe_tx.clj")
(println "INFO probe_tx on jolt (host cells): script completed")
