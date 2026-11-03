#!/usr/bin/env bash
# A4 (a): bb boot:jank on the extension tree with the apt binary and with the series build
# (PATCHED), plus the base tree on apt (UNPATCHED, no extension); each boot timed.
# Usage: boots.sh <base-tree> <ext-tree> <ext-tree-for-patched> <patched-jank-bin>
set -u
base=$1 ext=$2 extp=$3 pbin=$4
export LC_ALL=C.UTF-8
run() { # label dir JANK_BIN
  local label=$1 dir=$2 bin=$3 t0 t1
  echo "=== $label ($dir, JANK_BIN=${bin:-apt jank}) $(date -u +%T)"
  t0=$(date +%s.%N)
  local log=${LOGDIR:-/tmp}/u2-boot-$(echo "$label" | tr -c 'A-Za-z0-9\n' '-').log
  (cd "$dir" && JANK_BIN="$bin" bb boot:jank >"$log" 2>&1; echo "exit $?" >>"$log")
  grep -E "stock|battery|librar|errors|FAIL|exit " "$log" | head -20
  t1=$(date +%s.%N)
  echo "=== $label wall $(echo "$t1 - $t0" | bc) s"
}
mode=${5:-all}
case $mode in
  all) run "UNPATCHED + extension" "$ext" "" & run "PATCHED + extension" "$extp" "$pbin" & run "UNPATCHED (base)" "$base" "" & wait ;;
  warm) for i in 1 2 3; do run "UNPATCHED (base) r$i" "$base" ""; run "UNPATCHED + extension r$i" "$ext" ""; run "PATCHED + extension r$i" "$extp" "$pbin"; done ;;
esac
