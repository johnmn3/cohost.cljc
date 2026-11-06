#!/usr/bin/env bash
D=/tmp/claude-0/-home-user-atom-broker/fcd2c088-6a69-5efc-a32d-2f9026fe32a9/scratchpad/u6o2
f="$1"; safe=$(printf '%s' "$f" | od -An -tx1 | tr -d ' \n')
cd $D; LC_ALL=en_US.UTF-8 timeout 120 jank run probe_fn.jank -- "$f" > out/$safe.log 2>&1; echo "rc=$?" >> out/$safe.log
echo "$f $safe" >> out/index.txt
