;; Go chase lane: probe_tx_domains (per-ref Eve domain cells) on
;; GLOJURE. Vehicle only: load auto first (its :clj arm installs
;; the JVM adapter at load), install the glj adapter over it,
;; then load the domains probe from the repo root -- its own
;; (require 'cohost.hosts.auto) is a no-op by then and the glj
;; leaves stay in force. Eve comes from the 0.9.3 tree's :glj
;; roots (hosts.edn), the classpath Eve's own test:glj composes;
;; GLJ_CLASSPATH=src:<stub>:<eve glj roots>.
(require 'cohost.hosts.auto)
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
;; Eve's own load order on this seat (eve.runner.glj-main's
;; docstring): eve.host-completion loads FIRST -- a namespace
;; refers the names the completion adds to clojure.core only
;; when it is made after them. Without it eve.atom's load dies
;; inside eve.guard with a nil-pointer in the completion's
;; extend machinery (attempt 1, probe-tx-domains-glj.log).
(require 'eve.host-completion)
(load-file "probe_tx_domains.clj")
(println "INFO probe_tx_domains on glojure (Eve domain cells): script completed")
