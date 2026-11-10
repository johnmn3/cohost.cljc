#!/bin/bash
# The shootout (the operator's definition): cold process, put one
# datum on a disk-backed DataScript DB, cap 10,000 ms — a run over
# the cap is a failure, recorded "10s+". Runs happen back to back,
# each in a fresh domain, wall-clock per run; the table reports the
# median of the runs and the protocol verdict at the cap. Raw per-run
# times are printed whatever they are — the box and its load are part
# of the row's label, and an over-cap completion time is recorded
# beside its "10s+" verdict, never hidden by it.
# Usage: shootout.sh <label> <command...>   (the command gets the
# fresh domain prefix appended as its last argument)
set -u
LABEL="$1"; shift
CAP_MS=10000
WORK="${SHOOTOUT_WORK:-$HOME/workspace/ds-cohost/shootout-runs}"
mkdir -p "$WORK"
times=()
for i in 1 2 3 4 5; do
  D="$WORK/$LABEL-$i"
  rm -f "$D".root "$D".slab* "$D".rmap "$D".journal 2>/dev/null
  start=$(date +%s%N)
  timeout 1200 "$@" "$D" > "$WORK/$LABEL-$i.out" 2> "$WORK/$LABEL-$i.err"
  rc=$?
  end=$(date +%s%N)
  ms=$(( (end - start) / 1000000 ))
  ok=$(grep -c 'ONE-DONE' "$WORK/$LABEL-$i.out" || true)
  echo "run $i: ${ms} ms rc=$rc one-done=$ok"
  if [ "$ok" -ge 1 ]; then times+=("$ms"); else times+=("FAIL"); fi
done
printf '%s\n' "${times[@]}" | sort -n > "$WORK/$LABEL-times.txt"
median=$(printf '%s\n' "${times[@]}" | grep -E '^[0-9]+$' | sort -n | awk '{a[NR]=$1} END {if (NR) print a[int((NR+1)/2)]; else print "none"}')
echo "SHOOTOUT label=$LABEL runs=[${times[*]}] median-ms=$median cap-ms=$CAP_MS load=$(cat /proc/loadavg | cut -d' ' -f1-3)"
