#!/bin/bash
cd ~/workspace/wt/eve-cohost-u5
export PATH=$HOME/.local/bin:/usr/local/bin:$PATH
export JAVA_HOME=$HOME/opt/jdk-21.0.7+6-jre
export CLJ_CONFIG=$HOME/workspace/eve-env/cljconfig
bb boot:jvm > dev/u5/logs/boot-jvm-full.log 2>&1
echo "exit: $?"
grep -E "battery|errors|libraries:" dev/u5/logs/boot-jvm-full.log | head -6
