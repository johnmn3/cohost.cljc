#!/usr/bin/env bash
# D-4: bdwgc environment settings for cohost's jank process, one run each.
# Per setting: the peak resident memory and wall time of bb boot:jank's
# command (fresh snapshot, -O3, both batteries), then lane C's program runner
# (bb bench:programs jank binary-trees,pipeline,json, from the checkout in
# $PROGRAMS_DIR, which inherits the environment).
#   PROGRAMS_DIR=../cohost-c bash dev/perf/jank_gc_scan.sh [label=ENV ...]
set -uo pipefail
cd "$(dirname "$0")/../.."
P=$(bb -e '(load-file "dev/perf/jank_snapshot_key.clj") (println ((resolve (quote fresh-path))))' | tail -1)
case "$P" in *jank-snapshot*) ;; *) echo "no fresh snapshot"; exit 1;; esac
settings=("$@")
[ ${#settings[@]} -gt 0 ] || settings=(default= h256=GC_INITIAL_HEAP_SIZE=256M h512=GC_INITIAL_HEAP_SIZE=512M h1g=GC_INITIAL_HEAP_SIZE=1G
  h512d2="GC_INITIAL_HEAP_SIZE=512M GC_FREE_SPACE_DIVISOR=2" h512d1="GC_INITIAL_HEAP_SIZE=512M GC_FREE_SPACE_DIVISOR=1"
  h1gd2="GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=2" h1gd1="GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1")
for s in "${settings[@]}"; do
  label=${s%%=*}; envs=${s#*=}
  echo "SETTING $label [$envs]"
  echo "  BOOT $(env $envs python3 -I dev/perf/jank_gc_rss.py jank -O3 --module-path "$P" run dev/perf/jank_snapshot_run.jank)"
  (cd "$PROGRAMS_DIR" && env $envs bb bench:programs jank binary-trees,pipeline,json 2>&1 | grep -E "PROGRAM" | sed 's/^/  /')
done
