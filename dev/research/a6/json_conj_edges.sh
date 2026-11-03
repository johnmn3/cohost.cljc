#!/usr/bin/env bash
# A6-2: conj's edge cases under cohost on jank, one --eval per row (JANK_BIN picks the binary).
#   clojure -M dev/research/a6/json_conj_edges_jvm.clj > jvm.txt; bash dev/research/a6/json_conj_edges.sh > jank.txt
set -euo pipefail
JANK="${JANK_BIN:-jank}"
dir="$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)"
f=dev/research/a6/json_conj_edges.clj
mapfile -t rows < <(grep '^\[' "$f")
"$JANK" --module-path "$dir:src" -O3 run-main cohost.run -- --eval "$(head -1 "$f")" "${rows[@]}" 2>&1 | grep '^   => ' | tail -n +2 | sed 's/^   => //'
