#!/usr/bin/env bash
# A6-1: print cohost's rewritten form of each top-level form of a program (default: nbody), on jank.
#   bash dev/research/a6/dump_rewrite.sh [bench/programs/nbody.clj]   (JANK_BIN picks the binary)
set -u
f="${1:-bench/programs/nbody.clj}"
export LC_ALL=C.UTF-8
aot=$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)
src="(let [ctx (cohost.boot/ctx) rdr (clojure.core/read-string (str \"[\" (slurp \"$f\") \"]\"))] (doseq [form rdr] (when (and (seq? form) (#{'defn 'def} (first form)) (#{'advance 'energy 'offset 'gen '-run} (second form))) (println \";; ----\" (second form)) (prn (cohost.rewrite/rewrite ctx form)))))"
"${JANK_BIN:-jank}" --module-path "$aot:src" run-main cohost.run -- --no-libs --eval "$src" 2>&1 | grep -v '^   =>'
