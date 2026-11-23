#!/usr/bin/env bash
export PATH=/home/hatch/.local/bbnew-bin:/home/hatch/.local/bin:$PATH
export CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig
export TMPDIR=~/workspace/syntax-formalization/lanes/cohost/target/tmp
cd ~/workspace/syntax-formalization/lanes/cohost
bb boot:jvm > boot-baseline.log 2>&1
echo "BOOT-BASELINE-EXIT=$?" >> boot-baseline.log
