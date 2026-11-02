#!/usr/bin/env bash
# bash dev/research/upstream/19-gc-defaults.sh : the same program under bdwgc's env knobs.
set -uo pipefail
cat dev/research/upstream/timing.jank dev/research/upstream/19-gc-defaults.jank > target/research/upstream/19-gc-defaults.jank
for e in "NONE=1" "GC_INITIAL_HEAP_SIZE=1G" "GC_FREE_SPACE_DIVISOR=1" "GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1" "GC_ENABLE_INCREMENTAL=1"; do
  echo "== env $e"; env $e jank -O3 run target/research/upstream/19-gc-defaults.jank
done
echo "== collections in the default run (GC_PRINT_STATS=1, lines with 'GC #'):"
GC_PRINT_STATS=1 jank -O3 run target/research/upstream/19-gc-defaults.jank 2>&1 | grep -c "GC #"
