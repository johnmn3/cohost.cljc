;; STM lane (continuation): STATUS 10's smallest probe for the GO
;; target -- boot cohost's shim-side layer on GLOJURE and run
;; probe_tx over HOST cells. The seat is new (src/cohost/hosts/glj.clj,
;; the JVM adapter as template); glojure has no reader-conditional
;; feature of its own in cohost.hosts.auto, so the adapter is required
;; and installed explicitly here.
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(load-file "probe_tx.clj")
(println "INFO probe_tx on glojure (host cells): script completed")
