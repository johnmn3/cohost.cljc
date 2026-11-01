#!/bin/bash
# Three AOT boots with phase timers. Compiles cohost.run -O3 on the first
# bb boot:jank-aot-profile when target/jank-aot has no cohost/run.o, then
# reuses that cache. The phase table is the median of the three PROFILE-PHASE
# values for each phase.
set -u
cd "$(dirname "$0")/../.."
mkdir -p target
for i in 1 2 3; do
  echo "RUN $i"
  bb boot:jank-aot-profile > "target/jank-aot-profile-$i.txt" 2>"target/jank-aot-profile-$i.err"
  echo "EXIT:$?"
  grep '^PROFILE-' "target/jank-aot-profile-$i.txt" || true
done
