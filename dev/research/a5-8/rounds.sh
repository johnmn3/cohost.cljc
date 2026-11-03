#!/usr/bin/env bash
# A5-8: nsieve, fannkuch, mandelbrot, sum-overflow on apt, B = max/main 6ef471f, A = A5-8, 3
# interleaved rounds; each round starts under the 11:3x load gate (load < 8, > 4 GB available).
#   bash dev/research/a5-8/rounds.sh <base-tree> <a58-tree> <logdir>
set -u
base=$1 a=$2 logdir=$3
export LC_ALL=C.UTF-8
gate() { while :; do l=$(cut -d' ' -f1 /proc/loadavg); m=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
  awk -v l="$l" -v m="$m" 'BEGIN{exit !(l<8 && m>4)}' && { echo "    gate: load $l, ${m} GB"; return; }; sleep 30; done; }
for r in 1 2 3; do
  for c in "B|$base" "A|$a"; do
    IFS='|' read -r tag dir <<<"$c"
    gate; echo "=== $tag r$r $(date -u +%T)"
    (cd "$dir" && bb bench:programs jank nsieve,fannkuch,mandelbrot,sum-overflow 2>&1) | tee "$logdir/u2-a58-prog-$tag-r$r.log" | grep -E "MEDIAN-MS|rror"
  done
done
echo "=== done $(date -u +%T)"
