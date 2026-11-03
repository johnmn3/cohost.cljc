#!/usr/bin/env bash
# A4 (a) done-bar: three columns, interleaved, 3 rounds.
#   UNPATCHED            = the base tree (max/main, no extension) on apt jank
#   UNPATCHED + ext      = the A4 tree on apt jank
#   PATCHED  (+ ext)     = the A4 tree on the series build (JANK_BIN)
# then the JVM column once (same sentinels), then bench:jank per column, interleaved, 3 rounds.
# Usage: cols.sh <base-tree> <ext-tree> <ext-tree-for-patched> <patched-jank-bin> <logdir> [rounds]
set -u
base=$1 ext=$2 extp=$3 pbin=$4 logdir=$5 rounds=${6:-3}
export LC_ALL=C.UTF-8
progs=sort,lazy-pipeline,keyword-map,binary-trees-14,sum-overflow
cols=("U|$base|" "UX|$ext|" "P|$extp|$pbin")
# The 11:3x PM ET load gate (PROTOCOL.md): a bench round starts only with the 1-minute load
# under 8 and more than 4 GB available; GATE_DEADLINE (epoch s; default 12:30 AM ET 2026-11-03) ends the waiting: rounds not
# started by then are skipped and said so.
gate() {
  while :; do
    local l m; l=$(cut -d' ' -f1 /proc/loadavg); m=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
    # u2 is fifth in the gate's priority: any other lane's bench round running (a bb bench:*
    # process outside /home/user/lanes/u2) keeps the gate shut too
    local others=0 d c
    for d in $(pgrep -f "bb bench:" 2>/dev/null); do
      c=$(readlink "/proc/$d/cwd" 2>/dev/null); case "$c" in /home/user/lanes/u2/*|"") ;; *) others=$((others+1));; esac
    done
    if [ "$others" -eq 0 ] && awk -v l="$l" -v m="$m" 'BEGIN{exit !(l<8 && m>4)}'; then echo "    gate open: load $l, ${m} GB available, no other lane's bench round"; return 0; fi
    if [ "$(date +%s)" -ge "${GATE_DEADLINE:-1793683800}" ]; then echo "    GATE CLOSED at deadline: load $l, ${m} GB"; return 1; fi
    sleep 30
  done
}
for r in $(seq 1 "$rounds"); do
  for c in "${cols[@]}"; do
    IFS='|' read -r tag dir bin <<<"$c"
    gate || continue
    echo "=== programs $tag r$r $(date -u +%T) load $(cut -d' ' -f1-3 /proc/loadavg)"
    (cd "$dir" && JANK_BIN="$bin" bb bench:programs jank "$progs" 2>&1) | tee "$logdir/u2-cols-prog-$tag-r$r.log" | grep -E "MEDIAN-MS|RESULT|rror"
  done
done
gate
echo "=== programs JVM $(date -u +%T)"
(cd "$ext" && bb bench:programs jvm "$progs" 2>&1) | tee "$logdir/u2-cols-prog-JVM.log" | grep -E "MEDIAN-MS|RESULT|rror"
for r in $(seq 1 "$rounds"); do
  for c in "${cols[@]}"; do
    IFS='|' read -r tag dir bin <<<"$c"
    gate || continue
    echo "=== bench:jank $tag r$r $(date -u +%T) load $(cut -d' ' -f1-3 /proc/loadavg)"
    t0=$(date +%s)
    (cd "$dir" && JANK_BIN="$bin" bb bench:jank 2>&1) > "$logdir/u2-cols-bj-$tag-r$r.log"
    echo "    exit $? wall $(( $(date +%s) - t0 )) s"
  done
done
echo "=== done $(date -u +%T)"
