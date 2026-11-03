;; jank-MAX A1: the collector environment of every jank process cohost launches
;; (bb.edn's jank! and its other jank tasks, the snapshot and AOT boots, bench:jank,
;; bench:programs' jank targets). Loading this file defines jank-gc-env.
;;
;;   (jank-gc-env) -> {"GC_INITIAL_HEAP_SIZE" "..." ...}  as :extra-env
;;
;; Apt jank 0.1-noble starts bdwgc from its defaults (a tiny heap, collected hundreds of
;; times during a boot); patch 19 (upstream/jank/patches/19-gc-defaults.patch) compiles a
;; default heap into jank_init when GC_INITIAL_HEAP_SIZE is unset. This sets the measured
;; winner from cohost's side for UNPATCHED jank (doc/PERF.md "jank's collector environment
;; (A1)"); on a binary carrying patch 19 it only replaces the compiled-in heap with the
;; same choice.
;;
;; The user's environment wins key by key: a GC_* variable already set is not touched.
;; COHOST_JANK_GC=none sets nothing (bdwgc's or the binary's own defaults).
;; The values stay in this one file: lane D's dev/perf/jank_snapshot_cli.clj (cohost's
;; mainline, D-4) reads it too (jank-cli-launch :env); src/cohost/build_jank.clj (built executables) carries its own 512M.
;; The matrix (A1, apt 0.1-noble, 3 interleaved rounds per cell, 2026-11-02): a 512 MiB initial
;; heap beat bdwgc's defaults on 6 of 8 timed rows; the free-space divisor is left at bdwgc's
;; 3 (divisor 2 won nothing it did not also win at 3). Boot maxrss 1.64x apt's on the
;; snapshot path, 1.22x on the AOT path.
(def jank-gc-defaults {"GC_INITIAL_HEAP_SIZE" "512M"})

(defn jank-gc-env []
  (if (= "none" (System/getenv "COHOST_JANK_GC"))
    {}
    (into {} (remove (fn [[k _]] (System/getenv k)) jank-gc-defaults))))
