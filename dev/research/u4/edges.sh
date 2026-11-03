#!/usr/bin/env bash
# U6-9 (a): the numeric core fns on the battery's edge cases under cohost on jank,
# one row per --eval (a native C++ exception kills only its row: "ERR a native C++ exception").
#   clojure -M dev/research/u4/edges_jvm.clj > jvm.txt; bash dev/research/u4/edges.sh > jank.txt; diff jvm.txt jank.txt
# JANK_BIN picks the binary (bb boot:jank's AOT cache for it is ensured first).
set -euo pipefail
JANK="${JANK_BIN:-jank}"
dir="$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)"
f=dev/research/u4/num_edges_cohost.clj
mapfile -t rows < <(grep '^\[' "$f" | sed 's/^\[/[/')
"$JANK" --module-path "$dir:src" -O3 run-main cohost.run -- --eval "$(head -1 "$f")" "${rows[@]}" 2>&1 \
  | grep '^   => ' | tail -n +2 | sed 's/^   => //'
