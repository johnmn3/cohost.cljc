#!/usr/bin/env bash
# P3 GC environment matrix: the AOT'd p3_gc_alloc module (built by
# run-jank.sh) under bdwgc's environment knobs (bdwgc README.environment:
# GC_INITIAL_HEAP_SIZE, GC_FREE_SPACE_DIVISOR, GC_MARKERS, GC_ENABLE_INCREMENTAL,
# GC_PRINT_STATS). jank calls GC_init() with all-interior-pointers only
# (c_api.cpp jank_init), so the knobs are the environment's. One run each.
#   bash dev/research/run-jank.sh dev/research/p3_gc_alloc.jank aot   # builds the cache
#   bash dev/research/p3_gc_env.sh
set -uo pipefail
root=target/research/p3_gc_alloc
cache="$(ls -d "$root"/_cache/* | head -1)"
run() { echo "== env: $*"; env "$@" jank --module-path "$cache:$root/src" run-main research.p3_gc_alloc 2>&1 | grep -v '^$'; }
run GC_PRINT_STATS=1 2>&1 | grep -c "GC #" | sed 's/^/collections during the default run (GC_PRINT_STATS lines): /'
run NONE=1
run GC_INITIAL_HEAP_SIZE=1G
run GC_FREE_SPACE_DIVISOR=1
run GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1
run GC_MARKERS=1
run GC_ENABLE_INCREMENTAL=1
