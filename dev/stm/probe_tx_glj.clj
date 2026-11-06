;; STM lane (continuation): STATUS 10's smallest probe for the GO
;; target -- boot cohost's shim-side layer on GLOJURE and run
;; probe_tx over HOST cells. The seat is new (src/cohost/hosts/glj.clj,
;; the JVM adapter as template); glojure has no reader-conditional
;; feature of its own in cohost.hosts.auto, so the adapter is required
;; and installed explicitly here.
;;
;; Go chase lane (STATUS-go-chase.md): ORDER MATTERS. probe_tx.clj
;; requires cohost.hosts.auto, whose :clj arm loads cohost.hosts.jvm,
;; and jvm.clj installs its adapter at namespace load -- clobbering
;; any earlier install. So: load auto FIRST (the JVM adapter lands),
;; then install the glj adapter over it; probe_tx.clj's own require
;; of auto is then a no-op and the glj leaves stay in force for the
;; whole run. (STATUS 16's run had the old order: its pre-thread
;; prefix passed on near-identical caps, and its thread stop was
;; the JVM adapter's Runnable resolution -- the same stop, reached
;; through the wrong adapter. probe_tx_glj_core.clj never required
;; auto, so its 6/6 genuinely ran under the glj adapter.)
(require 'cohost.hosts.auto)
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(load-file "probe_tx.clj")
(println "INFO probe_tx on glojure (host cells): script completed")
