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

;; A1-3: a GC_INITIAL_HEAP_SIZE of 2G or more breaks every cohost program on jank, apt and the
;; series alike: "JIT session error: In graph .../cohost/hosts/jank_host.o, section .eh_frame:
;; relocation target ... (DW.ref.__gxx_personality_v0) is out of range of Delta32 fixup" (A1-2,
;; doc/PERF.md, all 3 rounds of both binaries). So the launcher clamps a user's value at or above
;; 2G to 1G (the largest measured working value) with one line on stderr. bdwgc's spelling is
;; parsed: digits, then an optional k, m or g suffix (any case), else bytes. COHOST_JANK_GC=none
;; passes the user's value through untouched.
(def jank-gc-heap-limit (* 2 1024 1024 1024))
(def jank-gc-heap-clamp "1G")

(defn jank-gc-parse-size
  "bdwgc's size spelling -> bytes, or nil when it is not one."
  [s]
  (when-let [[_ n u] (re-matches #"\s*(\d+)\s*([kKmMgG]?)\s*" (or s ""))]
    (* (parse-long n)
       (case (clojure.string/lower-case u) "" 1 "k" 1024 "m" (* 1024 1024) "g" (* 1024 1024 1024)))))

(defn jank-gc-env []
  (if (= "none" (System/getenv "COHOST_JANK_GC"))
    {}
    (let [env (into {} (remove (fn [[k _]] (System/getenv k)) jank-gc-defaults))
          user (System/getenv "GC_INITIAL_HEAP_SIZE")
          bytes (jank-gc-parse-size user)]
      (if (and bytes (>= bytes jank-gc-heap-limit))
        (do (binding [*out* *err*]
              (println (str "cohost: GC_INITIAL_HEAP_SIZE=" user " clamped to " jank-gc-heap-clamp
                            ": with an initial heap of 2G or more jank's JIT cannot link cohost's objects"
                            " (\"relocation ... out of range of Delta32 fixup\"; doc/PERF.md A1-2)."
                            " COHOST_JANK_GC=none passes it through.")))
            (assoc env "GC_INITIAL_HEAP_SIZE" jank-gc-heap-clamp))
        env))))
