;; Go chase lane: probe_tx_domains (per-ref Eve domain cells) on
;; GLOJURE. Vehicle only: load auto first (its :clj arm installs
;; the JVM adapter at load), install the glj adapter over it,
;; then load the domains probe from the repo root -- its own
;; (require 'cohost.hosts.auto) is a no-op by then and the glj
;; leaves stay in force. Eve comes from the 0.9.3 tree's :glj
;; roots (hosts.edn), the classpath Eve's own test:glj composes;
;; GLJ_CLASSPATH=src:<stub>:<eve glj roots>.
;; Eve's own load order on this seat (eve.runner.glj-main's
;; docstring): eve.host-completion loads FIRST -- before any
;; other namespace -- because a namespace refers the names the
;; completion adds to clojure.core only when it is made after
;; them, and its alter-var-root wrappers capture core's roots.
;; Attempts 1-2 (STATUS-go-chase STATUS 3) loaded cohost first
;; and died identically in eve.guard's load; this is the order
;; Eve's own glj runner uses.
(require 'eve.host-completion)
(require 'cohost.hosts.auto)
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(load-file "probe_tx_domains.clj")
(println "INFO probe_tx_domains on glojure (Eve domain cells): script completed")
