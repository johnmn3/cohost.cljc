#!/usr/bin/env bash
S=/tmp/claude-0/-home-user-atom-broker/fcd2c088-6a69-5efc-a32d-2f9026fe32a9/scratchpad; D=$S/u6o2; E=$S/wt-u6conv/eve
export LC_ALL=en_US.UTF-8; cd $D
EP="$(cd $E && bb classpath jank)"
for arm in "opt2 $S/wt-u6" "opt1 $S/wt-u6box"; do
  set -- $arm
  echo "== $1 cohost=$(git -C $2 rev-parse --short HEAD) eve=$(git -C $E rev-parse --short HEAD) $(TZ=America/New_York date +%r) ET load=$(cut -d' ' -f1-3 /proc/loadavg)"; SECONDS=0
  timeout 600 jank --module-path "$2/src:$EP" run d_driver.jank -- $1 2>&1 | grep -E "^INSTANCE|^EVEVEC|^DBENCH|^DONE|Panic|terminate called|error"; echo "$1 rc=${PIPESTATUS[0]} wall=${SECONDS}s"
done
echo "== end $(TZ=America/New_York date +%r) ET"
