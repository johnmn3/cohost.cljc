#!/usr/bin/env bash
# Run probes one at a time (timings on a 4-CPU box overlap otherwise).
#   bash dev/research/queue.sh p1_fib_floor p2_call_path ...
# For each name: the jank probe (JIT then AOT) if dev/research/<name>.jank
# exists, then the JVM twin if dev/research/<name>.clj exists. Logs go to
# target/research/<name>.log and <name>-jvm.log.
set -uo pipefail
mkdir -p target/research
for name in "$@"; do
  if [ -f "dev/research/$name.jank" ]; then
    echo "[$(date +%H:%M:%S)] jank $name" >> target/research/queue.log
    bash dev/research/run-jank.sh "dev/research/$name.jank" both > "target/research/$name.log" 2>&1
    echo "EXIT $?" >> "target/research/$name.log"
  fi
  if [ -f "dev/research/$name.clj" ]; then
    echo "[$(date +%H:%M:%S)] jvm $name" >> target/research/queue.log
    clojure -M dev/research/run-jvm.clj "dev/research/$name.clj" 2>&1 | grep -v JAVA_TOOL_OPTIONS > "target/research/$name-jvm.log"
    echo "EXIT ${PIPESTATUS[0]}" >> "target/research/$name-jvm.log"
  fi
done
echo "[$(date +%H:%M:%S)] QUEUE DONE: $*" >> target/research/queue.log
