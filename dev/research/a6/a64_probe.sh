#!/usr/bin/env bash
# A6-4: pipeline candidates (cohost vs plain jank AOT) and dispatch candidates (cohost; the JVM once) on one binary (JANK_BIN)
set -u
export LC_ALL=C.UTF-8
pick() { grep -o "^[qd][0-9][0-9][^ ]* runs-ns .*median-ns [0-9]*\|COMPILE FAILED" | awk '{print $1, $NF}'; }
echo "== pipeline cohost $(cut -d' ' -f1 /proc/loadavg)"; JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/a6/pipeline_probe.clj 2>&1 | pick
echo "== pipeline jank-aot $(cut -d' ' -f1 /proc/loadavg)"; bash dev/research/run-jank.sh dev/research/a6/pipeline_probe.jank aot 2>&1 | pick
echo "== dispatch cohost $(cut -d' ' -f1 /proc/loadavg)"; JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/a6/dispatch_probe.clj 2>&1 | pick
if [ "${JVM:-0}" = 1 ]; then echo "== dispatch jvm"; clojure -M dev/research/run-jvm.clj dev/research/a6/dispatch_probe.clj 2>&1 | pick; echo "== pipeline jvm"; clojure -M dev/research/run-jvm.clj dev/research/a6/pipeline_probe.clj 2>&1 | pick; fi
