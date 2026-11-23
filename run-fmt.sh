#!/usr/bin/env bash
export PATH=/home/hatch/.local/bbnew-bin:/home/hatch/.local/bin:$PATH
export CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig
cd ~/workspace/syntax-formalization/lanes/cohost
bb fmt > fmt-pass.log 2>&1
echo "FMT-EXIT=$?" >> fmt-pass.log
cljfmt check src >> fmt-pass.log 2>&1
echo "FMT-CHECK-AFTER=$?" >> fmt-pass.log
