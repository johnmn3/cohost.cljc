#!/usr/bin/env bash
S=/tmp/claude-0/-home-user-atom-broker/fcd2c088-6a69-5efc-a32d-2f9026fe32a9/scratchpad; D=$S/u6o2; C=$S/wt-u6; E=$S/wt-u6conv/eve
export LC_ALL=en_US.UTF-8; cd $D
EP="$(cd $E && bb classpath jank)"
echo "== state cohost=$(git -C $C rev-parse --short HEAD) eve=$(git -C $E rev-parse --short HEAD) load=$(cut -d' ' -f1-3 /proc/loadavg)"
echo "== JIT $(TZ=America/New_York date +%r) ET"; SECONDS=0
timeout 600 jank --module-path "$D/bsrc:$C/src:$EP" run b_driver.jank -- JIT 2>&1 | grep -E "^MODULE|^BENCH|^DONE|Panic|error"; echo "jit wall=${SECONDS}s"
rm -rf $D/baot $D/bsrc-old; mkdir -p $D/bsrc-old/u6o2
timeout 600 jank --module-path $D/bsrc compile-module u6o2.loops -O3 --target-dir $D/baot > $D/baot.log 2>&1; echo "compile rc=$?"
sed 's/(def marker :compiled)/(def marker :source)/' $D/bsrc/u6o2/loops.jank > $D/bsrc-old/u6o2/loops.jank; touch -d '1 hour ago' $D/bsrc-old/u6o2/loops.jank
cache=$(dirname $(dirname $(find $D/baot -name loops.o | head -1)))
echo "== AOT $(TZ=America/New_York date +%r) ET load=$(cut -d' ' -f1-3 /proc/loadavg)"; SECONDS=0
timeout 600 jank --module-path "$cache:$D/bsrc-old:$C/src:$EP" run b_driver.jank -- AOT 2>&1 | grep -E "^MODULE|^BENCH|^DONE|Panic|error"; echo "aot wall=${SECONDS}s"
echo "== end $(TZ=America/New_York date +%r) ET"
