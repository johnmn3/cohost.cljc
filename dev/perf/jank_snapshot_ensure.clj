;; jank-FAST Step 6 stage 2. Build the AOT snapshot when it is stale (the key
;; and the build: dev/perf/jank_snapshot_key.clj); return the module path a
;; snapshot boot runs with.
;;   bb -e '(println (load-file "dev/perf/jank_snapshot_ensure.clj"))'
(load-file "dev/perf/jank_snapshot_key.clj")
(ensure!)
