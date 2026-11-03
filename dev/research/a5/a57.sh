#!/usr/bin/env bash
# A5-7: serial: boot:jank apt + the 99-shape probe, boot:jank series + probe, boot:jvm (this tree);
# then spectral-norm and -hinted, B = <base> vs A = this tree, apt and series, 3 interleaved rounds,
# each under the load gate. Usage: a57.sh <base> <pbin> <logdir>
set -u
base=$1 pbin=$2 logdir=$3 a=$(pwd)
export LC_ALL=C.UTF-8
gate() { while :; do l=$(cut -d' ' -f1 /proc/loadavg); m=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
  awk -v l="$l" -v m="$m" 'BEGIN{exit !(l<8 && m>4)}' && { echo "    gate: load $l, ${m} GB"; return; }; sleep 30; done; }
for c in "apt|" "series|$pbin"; do IFS='|' read -r l bin <<<"$c"
  JANK_BIN="$bin" bb boot:jank > "$logdir/u2-a57-boot-$l.log" 2>&1; echo "=== boot $l exit $?"
  grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-a57-boot-$l.log"
  JANK_BIN="$bin" timeout 900 bash dev/research/a5/shapes2.sh > "$logdir/u2-a57-probe-$l.log" 2>&1
  python3 -I dev/research/a5/compare.py "$logdir/u2-a5-2-jvm-each.log" "$logdir/u2-a57-probe-$l.log" | tail -1
done
echo "=== boot:jvm"; env -u LANG -u LC_ALL bb boot:jvm 2>&1 | grep -E "booted|battery:|FAIL|libraries"
for r in 1 2 3; do for col in "apt|" "series|$pbin"; do IFS='|' read -r l bin <<<"$col"
  for c in "B|$base" "A|$a"; do IFS='|' read -r tag dir <<<"$c"
    gate; echo "=== $tag $l r$r $(date -u +%T)"
    (cd "$dir" && JANK_BIN="$bin" bb bench:programs jank spectral-norm,spectral-norm-hinted 2>&1) | tee "$logdir/u2-a57-prog-$tag-$l-r$r.log" | grep -E "MEDIAN-MS|rror"
  done; done; done
echo "=== done $(date -u +%T)"
