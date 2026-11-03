#!/usr/bin/env bash
# u2's whole remaining measurement as ONE serial chain (the 11:35 PM ET ruling: one jank boot or
# bench process per lane at a time). A step killed by the box (exit 137) is rerun once and its
# dmesg line logged. Usage: chain.sh <base> <ext> <extp> <a5> <pbin> <logdir>
set -u
base=$1 ext=$2 extp=$3 a5=$4 pbin=$5 logdir=$6
here=$(cd "$(dirname "$0")" && pwd)
export LC_ALL=C.UTF-8
boot() { # label dir bin
  local label=$1 dir=$2 bin=$3 log="$logdir/u2-chain-boot-$1.log" try t0 rc
  for try in 1 2; do
    t0=$(date +%s.%N)
    (cd "$dir" && JANK_BIN="$bin" bb boot:jank > "$log" 2>&1); rc=$?
    echo "=== boot $label try $try exit $rc wall $(echo "$(date +%s.%N) - $t0" | bc) s load $(cut -d' ' -f1-3 /proc/loadavg)"
    grep -E "booted|battery:|FAIL|libraries" "$log"
    if grep -q "exitValue=137\|exit 137\|:exit 137" "$log" || [ $rc -eq 137 ]; then
      echo "    killed: $(dmesg 2>/dev/null | grep -i 'killed process' | tail -1)"; continue
    fi
    return 0
  done
}
probe() { # label dir bin
  (cd "$2" && JANK_BIN="$3" timeout 900 bash "$a5/dev/research/a5/shapes2.sh" > "$logdir/u2-chain-probe-$1.log" 2>&1)
  echo "=== probe $1 exit $?"; python3 -I "$a5/dev/research/a5/compare.py" "$logdir/u2-a5-2-jvm-each.log" "$logdir/u2-chain-probe-$1.log"
}
echo "=== chain start $(date -u +%T)"
boot ext-series "$extp" "$pbin"
boot ext-apt "$ext" ""
boot base-apt "$base" ""
echo "=== cols $(date -u +%T)"
bash "$here/cols.sh" "$base" "$ext" "$extp" "$pbin" "$logdir" 3
echo "=== A5-2 $(date -u +%T)"
boot a5-series "$a5" "$pbin"; probe a5-series "$a5" "$pbin"
boot base-series "$base" "$pbin"; probe base-series "$base" "$pbin"
echo "=== chain done $(date -u +%T)"
