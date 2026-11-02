#!/usr/bin/env bash
# P6b: cohost's boot on jank with and without -O3 on run-main (wall seconds of
# the whole process, three runs each; the AOT cache must exist: bb boot:jank).
#   bash dev/research/p6b_boot_O.sh
set -uo pipefail
dir="$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)"
for o in "" "-O3"; do
  for i in 1 2 3; do
    s=$(date +%s.%N)
    jank --module-path "$dir:src" $o run-main cohost.run > /dev/null 2>&1
    e=$(date +%s.%N)
    echo "boot opt='${o:-none}' run $i wall-s $(echo "$e - $s" | bc)"
  done
done
