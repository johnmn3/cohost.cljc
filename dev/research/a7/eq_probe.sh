#!/usr/bin/env bash
# A7-3: = vs == under cohost and plain jank AOT on one binary (JANK_BIN)
set -u
export LC_ALL=C.UTF-8
pick() { grep -o "^r[0-9][0-9][^ ]* runs-ns .*median-ns [0-9]*\|COMPILE FAILED" | awk '{print $1, $NF}'; }
echo "== cohost $(cut -d' ' -f1 /proc/loadavg)"; JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/a7/eq_probe.clj 2>&1 | pick
echo "== jank-aot $(cut -d' ' -f1 /proc/loadavg)"; bash dev/research/run-jank.sh dev/research/a7/eq_probe.jank aot 2>&1 | pick
