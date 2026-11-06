#!/usr/bin/env bash
S=/tmp/claude-0/-home-user-atom-broker/fcd2c088-6a69-5efc-a32d-2f9026fe32a9/scratchpad; D=$S/u6o2; E=$S/wt-u6conv/eve
export LC_ALL=en_US.UTF-8; cd $D; EP="$(cd $E && bb classpath jank)"
echo "== opt2 $(TZ=America/New_York date +%r) ET load=$(cut -d' ' -f1-3 /proc/loadavg)"
timeout 600 jank --module-path "$S/wt-u6/src:$EP" run d2_driver.jank -- opt2 2>&1 | grep -E "^EVEVEC|^DBENCH|^DONE|Panic|terminate called|error"; echo "opt2 rc=${PIPESTATUS[0]}"
for f in count seq first coll?; do
  echo "== opt1 $f $(TZ=America/New_York date +%r) ET"
  timeout 600 jank --module-path "$S/wt-u6box/src:$EP" run d2_driver.jank -- opt1 $f 2>&1 | grep -E "^EVEVEC|^OPT1|^DONE|Panic|terminate called|error"; echo "opt1 $f rc=${PIPESTATUS[0]}"
done
echo "== end $(TZ=America/New_York date +%r) ET"
