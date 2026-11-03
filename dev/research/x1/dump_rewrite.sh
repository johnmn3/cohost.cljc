#!/usr/bin/env bash
# X1-10: the forms cohost evaluates on jank for one program file (JANK_BIN picks the binary).
#   bash dev/research/x1/dump_rewrite.sh bench/programs/fib_hinted.clj [extra source appended]
set -euo pipefail
export LC_ALL=C.UTF-8
aot=$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)
src="$(cat "$1")${2:-}"
"${JANK_BIN:-jank}" -O3 --module-path "$aot:src" run dev/research/x1/dump_rewrite.jank -- "$src" 2>&1 | sed -n '/^=== FORM/,$p'
