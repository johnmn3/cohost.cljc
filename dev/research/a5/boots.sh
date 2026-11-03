#!/usr/bin/env bash
# A5-x: serial boots + probe on apt and the series build, then LANG unset boot:jvm (one jank
# process at a time). Usage: boots.sh <tree> <patched-jank-bin> <logdir> <tag>
set -u
t=$1 pbin=$2 logdir=$3 tag=$4
export LC_ALL=C.UTF-8
for c in "apt|" "series|$pbin"; do
  IFS='|' read -r l bin <<<"$c"
  t0=$(date +%s)
  (cd "$t" && JANK_BIN="$bin" bb boot:jank > "$logdir/u2-$tag-boot-$l.log" 2>&1); rc=$?
  echo "=== boot $l exit $rc wall $(( $(date +%s)-t0 )) s load $(cut -d' ' -f1-3 /proc/loadavg)"
  grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-$tag-boot-$l.log"
  (cd "$t" && JANK_BIN="$bin" timeout 900 bash dev/research/a5/shapes2.sh > "$logdir/u2-$tag-probe-$l.log" 2>&1)
  echo "=== probe $l exit $?"; python3 -I "$t/dev/research/a5/compare.py" "$logdir/u2-a5-2-jvm-each.log" "$logdir/u2-$tag-probe-$l.log"
done
(cd "$t" && env -u LANG -u LC_ALL bb boot:jvm > "$logdir/u2-$tag-boot-jvm.log" 2>&1); echo "=== boot:jvm exit $?"
grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-$tag-boot-jvm.log"
