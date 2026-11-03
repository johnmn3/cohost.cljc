#!/usr/bin/env bash
# A5-2: boot:jank + the per-shape probe on apt and the series build (this tree), the probe on the
# series build at the base tree (unchanged check), and boot:jvm with LANG unset.
# Usage: measure.sh <a5-tree> <base-tree> <patched-jank-bin> <logdir> <tag>
set -u
a5=$1 base=$2 pbin=$3 logdir=$4 tag=$5
export LC_ALL=C.UTF-8
one() { # label dir bin
  local label=$1 dir=$2 bin=$3 t0
  t0=$(date +%s)
  (cd "$dir" && JANK_BIN="$bin" bb boot:jank > "$logdir/u2-a5-2-$tag-boot-$label.log" 2>&1; echo "exit $? wall $(( $(date +%s)-t0 ))" >> "$logdir/u2-a5-2-$tag-boot-$label.log")
  (cd "$dir" && JANK_BIN="$bin" timeout 900 bash "$a5/dev/research/a5/shapes2.sh" > "$logdir/u2-a5-2-$tag-probe-$label.log" 2>&1; echo "exit $?" >> "$logdir/u2-a5-2-$tag-probe-$label.log")
}
one apt "$a5" "" & one patched "$a5" "$pbin" & one patched-base "$base" "$pbin" &
(cd "$a5" && env -u LANG -u LC_ALL bb boot:jvm > "$logdir/u2-a5-2-$tag-boot-jvm.log" 2>&1; echo "exit $?" >> "$logdir/u2-a5-2-$tag-boot-jvm.log") &
wait
for l in apt patched patched-base; do
  echo "== $l"; grep -E "booted|battery:|FAIL|libraries|exit" "$logdir/u2-a5-2-$tag-boot-$l.log"
  python3 -I "$a5/dev/research/a5/compare.py" "$logdir/u2-a5-2-jvm-each.log" "$logdir/u2-a5-2-$tag-probe-$l.log"
done
echo "== jvm"; grep -E "booted|battery:|FAIL|libraries|exit" "$logdir/u2-a5-2-$tag-boot-jvm.log"
