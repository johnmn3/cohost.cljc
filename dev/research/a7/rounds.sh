#!/usr/bin/env bash
# A7-1: binary-trees, -hinted, -14, -14-hinted on apt, B = <base> (emission off) vs A = this tree,
# 3 interleaved rounds under the load gate; then boot:jank series, boot:jvm, boot:cljs (this tree).
#   bash dev/research/a7/rounds.sh <base> <pbin> <logdir>
set -u
base=$1 pbin=$2 logdir=$3 a=$(pwd)
export LC_ALL=C.UTF-8
gate() { while :; do ld=$(cut -d' ' -f1 /proc/loadavg); m=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
  awk -v l="$ld" -v m="$m" 'BEGIN{exit !(l<8 && m>4)}' && { echo "    gate: load $ld, ${m} GB"; return; }; sleep 30; done; }
for r in 1 2 3; do for c in "B|$base" "A|$a"; do IFS='|' read -r tag dir <<<"$c"
  gate; echo "=== $tag r$r $(date -u +%T)"
  (cd "$dir" && bb bench:programs jank binary-trees,binary-trees-hinted,binary-trees-14,binary-trees-14-hinted 2>&1) | tee "$logdir/u2-a71-prog-$tag-r$r.log" | grep -E "MEDIAN-MS|rror"
done; done
JANK_BIN="$pbin" bb boot:jank > "$logdir/u2-a71-boot-series2.log" 2>&1; echo "=== boot series exit $?"; grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-a71-boot-series2.log"
echo "=== boot:jvm"; env -u LANG -u LC_ALL bb boot:jvm 2>&1 | grep -E "booted|battery:|FAIL|libraries"
echo "=== boot:cljs"; bb boot:cljs > "$logdir/u2-a71-boot-cljs2.log" 2>&1; grep -E -A1 "booted|battery:|FAIL|libraries" "$logdir/u2-a71-boot-cljs2.log" | grep -v "^--" | head -20
echo "=== done $(date -u +%T)"
