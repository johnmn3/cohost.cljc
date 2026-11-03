#!/usr/bin/env bash
# Run a probe under cohost's stock core on jank (the bb bench:programs jank
# way): the harness and the probe are passed to cohost.run --eval. Needs
# the cohost AOT cache (bb boot:jank builds it; this script ensures it).
#   bash dev/research/run-cohost-jank.sh dev/research/<probe>.clj
set -euo pipefail
# JANK_BIN=<path> picks the jank binary (default: jank on PATH)
JANK="${JANK_BIN:-jank}"
probe="$1"; name="$(basename "$probe" .clj)"
dir="$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)"
code="$(sed 's/(System\/nanoTime)/(cpp\/jank.runtime.current_time)/g' dev/research/harness.clj; cat "$probe"; echo '(run)')"
echo "== $name under cohost on jank (AOT cache $dir)"
# JANK_O=<0-3> adds -O<n> (bb boot:jank passes none, i.e. 0: cli.cpp:205)
if [ -n "${JANK_O:-}" ]; then O="-O$JANK_O"; else O=""; fi
"$JANK" --module-path "$dir:src" $O run-main cohost.run -- --eval "$code"
