#!/usr/bin/env bash
# Run a probe under cohost's stock core booted from the AOT snapshot (bb bench:jank's and
# bb boot:jank's default when the snapshot is fresh, D-2), the twin of run-cohost-jank.sh
# (cohost's AOT modules). Builds the snapshot first when it is stale (minutes; the apt jank
# only: the snapshot key reads print-binary-version).
#   bash dev/research/run-cohost-jank-snapshot.sh dev/research/<probe>.clj
set -euo pipefail
JANK="${JANK_BIN:-jank}"
probe="$1"; name="$(basename "$probe" .clj)"
mp="$(bb -e '(print (load-file "dev/perf/jank_snapshot_ensure.clj"))' 2>/dev/null | tail -1)"
mkdir -p target/research
code="target/research/$name-snapshot.code"
{ sed 's/(System\/nanoTime)/(cpp\/jank.runtime.current_time)/g' dev/research/harness.clj; cat "$probe"; echo '(run)'; } > "$code"
main="target/research/$name-snapshot.jank"
bb -e "(spit \"$main\" (str \"(require 'cohost.adapter) (require 'cohost.boot) (require 'cohost.core)\n\"
                            \"(cohost.boot/make-user-ns!)\n\"
                            \"(cohost.boot/eval-str \" (pr-str (slurp \"$code\")) \")\n\"))"
echo "== $name under cohost on jank, booted from the snapshot ($mp)"
"$JANK" --module-path "$mp" -O3 run "$main"
