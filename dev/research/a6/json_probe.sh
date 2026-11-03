#!/usr/bin/env bash
# A6-2: the json candidates under cohost and as plain jank AOT, on one binary (JANK_BIN). Prints label median-ns.
set -u
export LC_ALL=C.UTF-8
echo "== cohost $(cut -d' ' -f1 /proc/loadavg)"
JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/a6/json_probe.clj 2>&1 | grep -o "^p[0-9][0-9][^ ]* runs-ns .*median-ns [0-9]*" | awk '{print $1, $NF}'
echo "== jank-aot $(cut -d' ' -f1 /proc/loadavg)"
bash dev/research/run-jank.sh dev/research/a6/json_probe.jank aot 2>&1 | grep -o "^p[0-9][0-9][^ ]* runs-ns .*median-ns [0-9]*\|COMPILE FAILED\|error.*" | awk '{print $1, $NF}'
