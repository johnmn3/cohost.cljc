#!/bin/bash
# Three timed boots. Each line of output is one run of bb boot:jank-profile.
# The phase table in doc/perf/jank.md is the median of the three PROFILE-PHASE
# values for each phase.
set -u
cd "$(dirname "$0")/../.."
mkdir -p target
for i in 1 2 3; do
  echo "RUN $i"
  bb boot:jank-profile > "target/jank-boot-profile-$i.txt" 2>"target/jank-boot-profile-$i.err"
  echo "EXIT:$?"
  grep '^PROFILE-' "target/jank-boot-profile-$i.txt" || true
done
