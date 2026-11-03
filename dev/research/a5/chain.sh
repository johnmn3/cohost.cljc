#!/usr/bin/env bash
# A5-2's measurement as ONE serial chain (one jank process at a
# time; bench rounds behind the 11:3x load gate, which cols.sh carries).
# Usage: chain.sh <a5> <base> <ext> <extp> <pbin> <logdir>
set -u
a5=$1 base=$2 ext=$3 extp=$4 pbin=$5 logdir=$6
export LC_ALL=C.UTF-8
source <(sed -n '/^gate() {/,/^}/p' "$ext/dev/research/a4/cols.sh")
boot() { # label dir bin
  local label=$1 dir=$2 bin=$3 log="$logdir/u2-a5c-boot-$1.log" try t0 rc
  for try in 1 2; do
    t0=$(date +%s.%N)
    (cd "$dir" && JANK_BIN="$bin" bb boot:jank > "$log" 2>&1); rc=$?
    echo "=== boot $label try $try exit $rc wall $(echo "$(date +%s.%N) - $t0" | bc) s load $(cut -d' ' -f1-3 /proc/loadavg)"
    grep -E "booted|battery:|FAIL|libraries" "$log"
    if grep -q "exitValue=137\|:exit 137" "$log" || [ $rc -eq 137 ]; then
      echo "    killed: $(dmesg 2>/dev/null | grep -i 'killed process' | tail -1)"; continue
    fi
    return 0
  done
}
probe() { # label dir bin
  (cd "$2" && JANK_BIN="$3" timeout 900 bash "$a5/dev/research/a5/shapes2.sh" > "$logdir/u2-a5c-probe-$1.log" 2>&1)
  echo "=== probe $1 exit $?"; python3 -I "$a5/dev/research/a5/compare.py" "$logdir/u2-a5-2-jvm-each.log" "$logdir/u2-a5c-probe-$1.log"
}
echo "=== start $(date -u +%T)"
boot a5-apt "$a5" ""; probe a5-apt "$a5" ""
boot a5-series "$a5" "$pbin"; probe a5-series "$a5" "$pbin"
boot base-series "$base" "$pbin"; probe base-series "$base" "$pbin"
boot base-apt "$base" ""; probe base-apt "$base" ""
(cd "$a5" && env -u LANG -u LC_ALL bb boot:jvm > "$logdir/u2-a5c-boot-jvm.log" 2>&1); echo "=== boot:jvm (LANG unset) exit $?"
grep -E "booted|battery:|FAIL|libraries" "$logdir/u2-a5c-boot-jvm.log"
echo "=== A5-2 cost rounds $(date -u +%T)"
for r in 1 2 3; do
  for c in "B|$base" "A|$a5"; do
    IFS='|' read -r tag dir <<<"$c"
    GATE_DEADLINE=9999999999 gate
    echo "=== cost $tag r$r $(date -u +%T) load $(cut -d' ' -f1-3 /proc/loadavg)"
    (cd "$dir" && bb bench:programs jank fib,tak,nbody,spectral-norm 2>&1) | tee "$logdir/u2-a5c-cost-$tag-r$r.log" | grep -E "MEDIAN-MS|rror"
  done
done
echo "=== done $(date -u +%T)"
