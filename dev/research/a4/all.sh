#!/usr/bin/env bash
# A4 (a) DONE run: granularity repro on both extension columns (builds their AOT), warm boots
# (3 interleaved rounds), then cols.sh. Usage: all.sh <base> <ext> <extp> <pbin> <logdir>
set -u
base=$1 ext=$2 extp=$3 pbin=$4 logdir=$5
here=$(cd "$(dirname "$0")" && pwd)
export LC_ALL=C.UTF-8
echo "=== granularity $(date -u +%T)"
(cd "$ext" && bash "$here/chunk_first.sh" > "$logdir/u2-chunkfirst-apt-ext.log" 2>&1) &
(cd "$extp" && JANK_BIN="$pbin" bash "$here/chunk_first.sh" > "$logdir/u2-chunkfirst-patched-ext.log" 2>&1) &
(cd "$base" && bash "$here/chunk_first.sh" > "$logdir/u2-chunkfirst-apt-base.log" 2>&1) &
wait
for f in apt-base apt-ext patched-ext; do echo "-- $f"; cat "$logdir/u2-chunkfirst-$f.log"; done
echo "=== warm boots $(date -u +%T)"
LOGDIR=$logdir bash "$here/boots.sh" "$base" "$ext" "$extp" "$pbin" warm
echo "=== cols $(date -u +%T)"
bash "$here/cols.sh" "$base" "$ext" "$extp" "$pbin" "$logdir" 3
