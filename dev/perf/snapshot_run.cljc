;; A host boot from its snapshot (or from source), timed, then the batteries.
;; With --snapshot: from the cached boot (the module path has its modules,
;; dev/perf/lpy_snapshot_ensure.clj); else boot! as cohost.run does.
;; Prints BOOT-MS <ms> (the boot alone).
;;   basilisp run [-p <snapshot src>] -p src dev/perf/lpy_snapshot_run.lpy [--snapshot]
(require 'cohost.run)
(require 'cohost.adapter)
(require 'cohost.boot)
(require 'cohost.battery)
(require 'cohost.lib-battery)
(let [snap? (= "--snapshot" (first *command-line-args*))
      t0 (cohost.adapter/nano-time)
      report (if snap?
               (do (require 'cohost.core) (cohost.boot/snapshot-report))
               (cohost.boot/boot! {}))
      ms (/ (double (- (cohost.adapter/nano-time) t0)) 1000000.0)]
  (when-not report (throw (ex-info "no boot report (a snapshot boot without the snapshot?)" {})))
  (println (str "BOOT-MS " ms (if snap? " snapshot" " source")))
  (cohost.boot/print-report report)
  (cohost.boot/make-user-ns!)
  (cohost.battery/run-battery! cohost.boot/eval-str)
  (cohost.lib-battery/run-lib-battery! cohost.boot/eval-str))
