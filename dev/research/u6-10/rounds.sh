#!/usr/bin/env bash
# U6-10: base (max/main) vs this tree, one jank process at a time; each bench round starts only
# under the 11:3x load gate (1-min load < 8, > 4 GB available), named in the log.
# Usage: rounds.sh <base> <a5> <pbin> <logdir>
set -u
base=$1 a5=$2 pbin=$3 logdir=$4
export LC_ALL=C.UTF-8
gate() { while :; do l=$(cut -d' ' -f1 /proc/loadavg); m=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
  awk -v l="$l" -v m="$m" 'BEGIN{exit !(l<8 && m>4)}' && { echo "    gate: load $l, ${m} GB"; return; }; sleep 30; done; }
echo "=== boot:jank apt (this tree) $(date -u +%T)"; (cd "$a5" && bb boot:jank 2>&1) | grep -E "booted|battery:|FAIL|libraries"
echo "=== boot:jvm (LANG unset) $(date -u +%T)"; (cd "$a5" && env -u LANG -u LC_ALL bb boot:jvm 2>&1) | grep -E "booted|battery:|FAIL|libraries"
echo "=== boot:cljs $(date -u +%T)"; (cd "$a5" && bb boot:cljs 2>&1) | tee "$logdir/u2-u610-boot-cljs.log" | grep -E "booted|battery:|FAIL|libraries|rror" | head -20
for r in 1 2 3; do
  for c in "B|$base" "A|$a5"; do
    IFS='|' read -r tag dir <<<"$c"
    gate; echo "=== programs $tag r$r $(date -u +%T)"
    (cd "$dir" && bb bench:programs jank nsieve,fannkuch 2>&1) | tee "$logdir/u2-u610b-prog-$tag-r$r.log" | grep -E "MEDIAN-MS|rror"
    gate; echo "=== leaves $tag r$r $(date -u +%T)"
    (cd "$dir" && JANK_O=3 bash dev/research/run-cohost-jank.sh "$a5/dev/research/a4/leaves.clj" 2>&1) | tee "$logdir/u2-u610b-leaves-$tag-r$r.log" | grep -E "median-ns" | sed 's/runs-ns.*median-ns/median-ns/'
  done
done
for c in "B|$base" "A|$a5"; do
  IFS='|' read -r tag dir <<<"$c"
  gate; echo "=== programs $tag PATCHED $(date -u +%T)"
  (cd "$dir" && JANK_BIN="$pbin" bb bench:programs jank nsieve,fannkuch 2>&1) | tee "$logdir/u2-u610b-prog-$tag-P.log" | grep -E "MEDIAN-MS|rror"
done
echo "=== done $(date -u +%T)"
