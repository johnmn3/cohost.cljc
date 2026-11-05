#!/usr/bin/env bash
# Unit 7 GATE: the two-process probe. A JVM process (cohost layer atom, kind
# chosen by metadata, Eve's JVM hosting) and a jank process (Eve on jank as-is)
# over ONE disk atom (domain <dir>/db, atom "u7/shared"), one Eve tree for both.
#   1. race:    both join, meet at a barrier in the atom, each does N interleaved
#               swaps (count + own side), meet again, print value + digests.
#   2. restart: the JVM process swaps a mark and is killed (halt: no close, no
#               slot release); a fresh jank process rejoins and reads; then the
#               jank process swaps a mark and is killed (_exit); a fresh JVM
#               process rejoins and reads; a last fresh jank read closes it.
# Usage: dev/u7/gate.sh <dir> [N]      (logs in <dir>/*.log, summary on stdout)
set -uo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
dir="$(mkdir -p "$1" && cd "$1" && pwd)"; N="${2:-200}"
db="$dir/db"
J() { "$here/dev/u7/jank-eve.sh" u7.two-proc "$@"; }
V() { "$here/dev/u7/clj-eve.sh" "$here/dev/u7/two_proc.clj" "$@"; }
ts() { TZ=America/New_York date +%H:%M:%S; }

echo "[$(ts)] race: N=$N per side, db=$db"
V race "$db" "$N" > "$dir/race-jvm.log" 2>&1 & pv=$!
J race "$db" "$N" > "$dir/race-jank.log" 2>&1 & pj=$!
wait $pv; ev=$?; wait $pj; ej=$?
echo "[$(ts)] race exits: jvm=$ev jank=$ej"

echo "[$(ts)] restart: jvm mark+halt"
V mark "$db" jvm-killed > "$dir/mark-jvm.log" 2>&1; echo "  exit $?"
echo "[$(ts)] restart: jank rejoin read"
J read "$db" > "$dir/read-jank-1.log" 2>&1; echo "  exit $?"
echo "[$(ts)] restart: jank mark+_exit"
J mark "$db" jank-killed > "$dir/mark-jank.log" 2>&1; echo "  exit $?"
echo "[$(ts)] restart: jvm rejoin read"
V read "$db" > "$dir/read-jvm-2.log" 2>&1; echo "  exit $?"
echo "[$(ts)] restart: jank final read"
J read "$db" > "$dir/read-jank-2.log" 2>&1; echo "  exit $?"

echo "[$(ts)] RESULT lines:"
grep -h "^RESULT\|^MARKED" "$dir"/race-jvm.log "$dir"/race-jank.log "$dir"/mark-jvm.log "$dir"/read-jank-1.log \
  "$dir"/mark-jank.log "$dir"/read-jvm-2.log "$dir"/read-jank-2.log
bb "$here/dev/u7/gate_verdict.clj" "$dir" "$N"
