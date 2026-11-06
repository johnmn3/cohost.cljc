;; runner: install the probe-local Eve factory (see probe_tx_domains.clj
;; header for why cohost.atom-eve is not loaded), then run probe_tx.clj
;; in this JVM -- the 23 semantics checks over per-ref Eve domain cells.
(require 'cohost.hosts.auto)
(require '[eve.atom :as eve-atom]
         '[cohost.atom :as atom-layer])
(def domains-minted (atom 0))
(atom-layer/install-factory!
 {:make-domain-atom
  (fn [slab-init]
    (let [d (eve-atom/atom-domain {})
          n (swap! domains-minted inc)]
      (eve-atom/lookup-or-create-mmap-atom! d (str "cohost.tx/ref" n)
                                            slab-init)))})
(println "INFO probe_tx over Eve domain cells; domains minted:" @domains-minted)
(load-file "probe_tx.clj")
