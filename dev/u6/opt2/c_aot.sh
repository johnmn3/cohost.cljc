#!/usr/bin/env bash
D=/tmp/claude-0/-home-user-atom-broker/fcd2c088-6a69-5efc-a32d-2f9026fe32a9/scratchpad/u6o2
export LC_ALL=en_US.UTF-8; cd $D
# the source the run sees: marker :source, mtime an hour back; the object compiled from the :compiled source is newer
rm -rf $D/caot $D/csrc-old; mkdir -p $D/csrc-old/u6o2
timeout 600 jank --module-path $D/csrc compile-module u6o2.callsites -O3 --target-dir $D/caot > $D/caot.log 2>&1; echo "compile rc=$?"
sed 's/(def marker :compiled)/(def marker :source)/' $D/csrc/u6o2/callsites.jank > $D/csrc-old/u6o2/callsites.jank
touch -d '1 hour ago' $D/csrc-old/u6o2/callsites.jank
cache=$(dirname $(dirname $(find $D/caot -name callsites.o | head -1)))
ls -l --time-style=+%H:%M:%S $D/csrc-old/u6o2/callsites.jank $cache/u6o2/callsites.o | awk '{print $6, $7}'
echo "== AOT run $(TZ=America/New_York date +%r) ET"
timeout 600 jank --module-path "$cache:$D/csrc-old" run c_driver.jank 2>&1 | grep -E "^MODULE|^INTERCEPT|^DONE|Panic|error"
