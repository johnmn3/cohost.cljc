#!/usr/bin/env bash
# A5-8: serial checks (one jank process at a time): boot:jank apt, the emission dump, the
# dloop/iloop pair (dev/research/u6-10's loops are not on this branch: a5-8/loops.clj's), then
# boot:jank on the series build, boot:jvm (LANG unset), boot:cljs.
#   bash dev/research/a5-8/check.sh <patched-jank-bin> <logdir>
set -u
pbin=$1 logdir=$2
export LC_ALL=C.UTF-8
t0=$(date +%s); bb boot:jank > "$logdir/u2-a58-boot-apt.log" 2>&1; echo "=== boot apt exit $? wall $(( $(date +%s)-t0 )) s"
grep -E -A2 "booted|battery:|FAIL|libraries" "$logdir/u2-a58-boot-apt.log" | grep -v "^--"
bash dev/research/a5-8/dump.sh 2>&1 | grep -A1 "^;; ----" | grep -v "^--"
cat dev/research/harness.clj > /dev/null 2>&1 && (printf '(defn run [] (bench "dloop" dloop) (bench "iloop" iloop) (println "checks" (dbl) (kw) (ok) (mixed)))\n' > /tmp/u2-a58-run.clj; cat dev/research/a5-8/loops.clj /tmp/u2-a58-run.clj > /tmp/u2-a58-probe.clj; JANK_O=3 bash dev/research/run-cohost-jank.sh /tmp/u2-a58-probe.clj 2>&1 | grep -E "^checks|median-ns" | sed 's/runs-ns.*median-ns/median-ns/')
t0=$(date +%s); JANK_BIN="$pbin" bb boot:jank > "$logdir/u2-a58-boot-series.log" 2>&1; echo "=== boot series exit $? wall $(( $(date +%s)-t0 )) s"
grep -E -A2 "booted|battery:|FAIL|libraries" "$logdir/u2-a58-boot-series.log" | grep -v "^--"
echo "=== boot:jvm"; env -u LANG -u LC_ALL bb boot:jvm > "$logdir/u2-a58-boot-jvm.log" 2>&1; grep -E -A2 "booted|battery:|FAIL|libraries" "$logdir/u2-a58-boot-jvm.log" | grep -v "^--"
echo "=== boot:cljs"; bb boot:cljs > "$logdir/u2-a58-boot-cljs.log" 2>&1; grep -E -A2 "booted|battery:|FAIL|libraries" "$logdir/u2-a58-boot-cljs.log" | grep -v "^--" | head -30
