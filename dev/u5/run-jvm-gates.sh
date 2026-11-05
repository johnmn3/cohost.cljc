#!/bin/bash
cd ~/workspace/wt/eve-cohost-u5
export PATH=$HOME/.local/bin:/usr/local/bin:$PATH
export JAVA_HOME=$HOME/opt/jdk-21.0.7+6-jre
export CLJ_CONFIG=$HOME/workspace/eve-env/cljconfig
echo "=== bb boot:jvm ==="
bb boot:jvm 2>&1 | tail -15
echo "=== eve_cohost_check (unit 1) ==="
clojure -Sdeps '{:paths ["src"]}' -M eve_cohost_check.clj 2>&1 | tail -3
echo "=== probe_memory (unit 3) ==="
clojure -Sdeps '{:paths ["src" "/home/hatch/src/eve/src"]}' -M probe_memory.clj 2>&1 | tail -3
echo "=== ALL DONE ==="
