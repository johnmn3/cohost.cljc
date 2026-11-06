#!/usr/bin/env bash
D=/tmp/claude-0/-home-user-atom-broker/fcd2c088-6a69-5efc-a32d-2f9026fe32a9/scratchpad/u6o2
export LC_ALL=en_US.UTF-8; cd $D
echo "== JIT (module compiled from source at load, before the re-root) $(TZ=America/New_York date +%r) ET"
timeout 600 jank --module-path $D/csrc run c_driver.jank 2>&1 | grep -E "^MODULE|^INTERCEPT|^DONE|Panic|error"
echo "== AOT compile $(TZ=America/New_York date +%r) ET"; rm -rf $D/caot; SECONDS=0
timeout 600 jank --module-path $D/csrc compile-module u6o2.callsites -O3 --target-dir $D/caot > $D/caot.log 2>&1; echo "compile rc=$? wall=${SECONDS}s"
find $D/caot -name "*.o" | sed "s|$D/||"
cache=$(dirname $(dirname $(find $D/caot -name callsites.o | head -1)))
echo "(source dir left off the AOT run module path)"
echo "== AOT (module loaded from $(basename $cache)) $(TZ=America/New_York date +%r) ET"
timeout 600 jank --module-path "$cache" run c_driver.jank 2>&1 | grep -E "^MODULE|^INTERCEPT|^DONE|Panic|error"

echo "== end $(TZ=America/New_York date +%r) ET"
