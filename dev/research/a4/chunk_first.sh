#!/usr/bin/env bash
# A4 (a): the chunk granularity of a lazily built map/filter seq under cohost on jank, against
# JVM Clojure. Run from a cohost tree whose AOT modules are built (bb boot:jank once).
# Usage: chunk_first.sh [jvm]
set -u
exprs=(
  '(count (chunk-first (seq (map inc (range 64)))))'
  '(count (chunk-first (seq (filter odd? (range 64)))))'
  '(count (chunk-first (seq (range 64))))'
  '(let [a (atom 0)] (first (map (fn [x] (swap! a inc) x) (map inc (range 100)))) @a)'
  '(let [a (atom 0)] (first (map (fn [x] (swap! a inc) x) (range 100))) @a)'
  '(= (vec (map inc (filter odd? (map inc (range 100))))) (vec (range 2 102 2)))'
)
export LC_ALL=C.UTF-8
if [ "${1:-}" = jvm ]; then
  for e in "${exprs[@]}"; do printf '%s => ' "$e"; clojure -M -e "(prn $e)" 2>/dev/null; done
else
  aot=$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)
  "${JANK_BIN:-jank}" -O3 --module-path "$aot:src" run-main cohost.run -- --no-libs --eval "${exprs[@]}" 2>&1 | grep '=>'
fi
