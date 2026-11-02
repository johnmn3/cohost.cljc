#!/usr/bin/env bash
# D-4 done-bar: before (COHOST_JANK_GC=none: no GC setting) and after (the
# jank tasks' default, GC_INITIAL_HEAP_SIZE=512M), three runs each,
# interleaved: bb boot:jank's peak RSS, wall and batteries; bb bench:jank
# --quick's boot-ms and libs-ms; and lane C's runner for binary-trees,
# pipeline and json in $PROGRAMS_DIR, given the same environment (it starts
# jank itself). Prints RUN lines; the medians are read from them.
#   PROGRAMS_DIR=../cohost-c bash dev/perf/jank_gc_time.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
read_ms() { bb -e '(let [r (clojure.edn/read-string (slurp "target/bench-jank.edn"))] (println (:boot-ms r) (:libs-ms r) (+ (:boot-ms r) (:libs-ms r))))'; }
for i in 1 2 3; do
  for side in before after; do
    if [ $side = before ]; then gc=none; penv=""; else gc=""; penv="GC_INITIAL_HEAP_SIZE=512M"; fi
    out=$(COHOST_JANK_GC=$gc python3 -I dev/perf/jank_gc_rss.py bb boot:jank)
    bat=$(COHOST_JANK_GC=$gc bb boot:jank 2>&1 | grep -E "^battery|^libraries" | tr '\n' ' ')
    echo "RUN $side $i BOOT $out $bat"
    COHOST_JANK_GC=$gc bb bench:jank --quick >/dev/null 2>&1
    echo "RUN $side $i QUICK $(read_ms)"
    (cd "$PROGRAMS_DIR" && env $penv bb bench:programs jank binary-trees,pipeline,json 2>&1 | grep -E "PROGRAM" | sed "s/^ */RUN $side $i /")
  done
done
