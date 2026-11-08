;; clr_compat.clj -- the ClojureCLR seat's arm for the two JVM
;; statics the snapshot-key chain calls: (System/getenv ...) and
;; (System/nanoTime), at dev/perf/jank_snapshot_key.clj:24,108 and
;; dev/perf/jank_aot_ensure.clj:23. Those files are keyed inputs
;; (any edit moves the snapshot key), and ClojureCLR has no type
;; named System carrying those members -- its compiler rejects
;; the forms outright ("No such namespace: System", verified on
;; the seat).
;;
;; ClojureCLR compiles (Foo/bar ...) as a plain var call when Foo
;; names a namespace rather than a type, so this file arms a
;; namespace named System with the two vars, backed by the real
;; .NET facilities:
;;   getenv  -> System.Environment/GetEnvironmentVariable
;;              (nil for an unset variable, like java's getenv)
;;   nanoTime -> System.Diagnostics.Stopwatch, scaled to
;;              nanoseconds (monotonic; the chain only ever
;;              differences two readings, to print an elapsed ms)
;;
;; This file is loaded ONLY by the ClojureCLR driver (install.cljc's
;; :cljr arm), immediately before jank_snapshot_ensure.clj. No other
;; seat loads it. Dotted type forms (System.Environment/...,
;; System.Diagnostics.Process/...) keep resolving as types with
;; the namespace present -- verified on the seat.
(create-ns 'System)

(intern 'System 'getenv
        (fn [k] (System.Environment/GetEnvironmentVariable k)))

(intern 'System 'nanoTime
        (fn []
          (let [f System.Diagnostics.Stopwatch/Frequency
                ts (System.Diagnostics.Stopwatch/GetTimestamp)]
            (+ (* (quot ts f) 1000000000)
               (quot (* (rem ts f) 1000000000) f)))))

;; ------------------------------------------------------------------
;; Comparator parity. snapshot-key (frozen code) orders its input
;; listing with clojure.core/sort, whose default comparator is
;; clojure.core/compare. On the JVM seats string compare is
;; ordinal (java.lang.String.compareTo). ClojureCLR's string
;; compare delegates to System.String.CompareTo, which is
;; CULTURE-sensitive - it orders letters case-insensitively
;; first - so "resources/.../LICENSE-EPL-v10.html" sorts AFTER
;; "resources/.../clojure/core.clj" here instead of before, and
;; the same input tree hashes to a different snapshot key under
;; a CLR driver (d8f072c3... instead of df88adac...; measured
;; 2026-11-08, listing diff = same (hex, path) pairs, different
;; order). Restore the ordinal semantics the build was written
;; against, for this build process only: strings compare via
;; String.CompareOrdinal ((compare "LICENSE" "clojure") => -23,
;; the JVM sign); everything else delegates to the stock compare.
;; ------------------------------------------------------------------
(let [stock-compare clojure.core/compare]
  (alter-var-root #'clojure.core/compare
                  (fn [_]
                    (fn [x y]
                      (if (and (string? x) (string? y))
                        (System.String/CompareOrdinal x y)
                        (stock-compare x y))))))
