#!/usr/bin/env bash
# X1-10: p15_hinted2, A B per round (apt then PATCHED), cohost then plain jank (AOT), 3 rounds, gated
# (load1 < 8, free > 4 GB), one jank process at a time.
set -uo pipefail
cd "$(dirname "$0")/../../.."
export LC_ALL=C.UTF-8 JANK_O=3
P=/home/user/int-a/jank-inst/bin/jank
gate() { while :; do l=$(cut -d' ' -f1 /proc/loadavg); f=$(free -g | awk '/^Mem:/{print $4}')
  awk -v l="$l" -v f="$f" 'BEGIN{exit !(l<8 && f>4)}' && break; sleep 15; done; echo "### $1 load $l free ${f}G $(date -u +%H:%M:%S)"; }
for r in 1 2 3; do
  for leg in cohost jank; do
    for b in UNPATCHED PATCHED; do
      gate "r=$r leg=$leg col=$b"
      if [ $b = PATCHED ]; then export JANK_BIN=$P; else unset JANK_BIN; fi
      if [ $leg = cohost ]; then bash dev/research/run-cohost-jank.sh dev/research/x1/p15_hinted2.clj 2>&1 | grep -E "median-ns|ERR|rror"
      else bash dev/research/run-jank.sh dev/research/x1/p15_hinted2.jank aot 2>&1 | grep -E "median-ns|rror|FAILED"; fi
    done
  done
done
echo "=== END $(date -u +%H:%M:%S)"
