#!/bin/bash
# Calm re-measurement runner (cohost-jvm-speed lane).
# usage: jvm_speed_run.sh <tag> <cases-csv> <results-dir>
# Runs each side in its own process, ABBA order (stock host host stock),
# starting every process only when the 1-minute load is below 3.0.
set -u
TAG=$1; CASES=$2; OUT=$3
cd "$(dirname "$0")/../.." || exit 9
mkdir -p "$OUT"

wait_calm() {
  local waited=0
  while :; do
    L=$(awk '{print $1}' /proc/loadavg)
    if awk "BEGIN{exit !($L < 3.0)}"; then break; fi
    sleep 20; waited=$((waited + 20))
    if [ $waited -ge 1800 ]; then echo "CALM-WAIT-TIMEOUT load1=$L" >> "$OUT/runs.log"; break; fi
  done
}

run_side() { # side idx
  wait_calm
  L=$(awk '{print $1}' /proc/loadavg)
  echo "RUN side=$1 idx=$2 load1=$L $(date -Is)" >> "$OUT/runs.log"
  clojure -Sdeps '{:paths ["src" "resources" "dev"]}' -M -m perf.jvm-speed "$1" "$CASES" "$OUT/$1-$2.edn" "$L" >> "$OUT/driver.log" 2>&1
  echo "EXIT side=$1 idx=$2 rc=$?" >> "$OUT/runs.log"
}

run_side stock 1
run_side host 1
run_side host 2
run_side stock 2
echo "JVM-SPEED-$TAG-DONE"
