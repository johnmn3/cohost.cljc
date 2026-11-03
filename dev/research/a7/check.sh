#!/usr/bin/env bash
# A7-1: serial checks on this tree: boot:jank apt, the binary-trees rewrite, the split probe,
# then boot:jank on the series build, boot:jvm (LANG unset) and boot:cljs. One jank process at a time.
#   bash dev/research/a7/check.sh <patched-jank-bin> <logdir>
set -u
pbin=$1 logdir=$2
export LC_ALL=C.UTF-8
t0=$(date +%s); bb boot:jank > "$logdir/u2-a71-boot-apt.log" 2>&1; echo "=== boot apt exit $? wall $(( $(date +%s)-t0 )) s"
grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-a71-boot-apt.log"
bash dev/research/u6-10/dump.sh bench/programs/binary_trees.clj build 2>&1 | grep -A1 "^;; ----"
JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/a7/bt_split.clj 2>&1 | grep -E "^checks|median-ns" | sed 's/runs-ns.*median-ns/median-ns/'
t0=$(date +%s); JANK_BIN="$pbin" bb boot:jank > "$logdir/u2-a71-boot-series.log" 2>&1; echo "=== boot series exit $? wall $(( $(date +%s)-t0 )) s"
grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-a71-boot-series.log"
echo "=== boot:jvm"; env -u LANG -u LC_ALL bb boot:jvm 2>&1 | grep -E "booted|battery:|FAIL|libraries"
echo "=== boot:cljs"; bb boot:cljs 2>&1 | grep -E "booted|battery:|FAIL|libraries|rror" | head -20
