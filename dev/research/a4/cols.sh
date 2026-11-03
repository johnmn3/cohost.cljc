#!/usr/bin/env bash
# A4 (a) done-bar: three columns, interleaved, 3 rounds.
#   UNPATCHED            = the base tree (max/main, no extension) on apt jank
#   UNPATCHED + ext      = the A4 tree on apt jank
#   PATCHED  (+ ext)     = the A4 tree on the series build (JANK_BIN)
# then the JVM column once (same sentinels), then bench:jank per column, interleaved, 3 rounds.
# Usage: cols.sh <base-tree> <ext-tree> <ext-tree-for-patched> <patched-jank-bin> <logdir> [rounds]
set -u
base=$1 ext=$2 extp=$3 pbin=$4 logdir=$5 rounds=${6:-3}
export LC_ALL=C.UTF-8
progs=sort,lazy-pipeline,keyword-map,binary-trees-14,sum-overflow
cols=("U|$base|" "UX|$ext|" "P|$extp|$pbin")
for r in $(seq 1 "$rounds"); do
  for c in "${cols[@]}"; do
    IFS='|' read -r tag dir bin <<<"$c"
    echo "=== programs $tag r$r $(date -u +%T) load $(cut -d' ' -f1-3 /proc/loadavg)"
    (cd "$dir" && JANK_BIN="$bin" bb bench:programs jank "$progs" 2>&1) | tee "$logdir/u2-cols-prog-$tag-r$r.log" | grep -E "MEDIAN-MS|RESULT|rror"
  done
done
echo "=== programs JVM $(date -u +%T)"
(cd "$ext" && bb bench:programs jvm "$progs" 2>&1) | tee "$logdir/u2-cols-prog-JVM.log" | grep -E "MEDIAN-MS|RESULT|rror"
for r in $(seq 1 "$rounds"); do
  for c in "${cols[@]}"; do
    IFS='|' read -r tag dir bin <<<"$c"
    echo "=== bench:jank $tag r$r $(date -u +%T) load $(cut -d' ' -f1-3 /proc/loadavg)"
    t0=$(date +%s)
    (cd "$dir" && JANK_BIN="$bin" bb bench:jank 2>&1) > "$logdir/u2-cols-bj-$tag-r$r.log"
    echo "    exit $? wall $(( $(date +%s) - t0 )) s"
  done
done
echo "=== done $(date -u +%T)"
