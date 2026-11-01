#!/usr/bin/env bash
# jank-FAST Step 6 stage 1. Load time from process start until the stamp
# printed just before cohost.run/-main. Five runs after one warmup, source
# module path and AOT-objects-first module path.
#
#   bash dev/perf/jank_aot_time.sh
# Requires target/jank-aot/_cache/*/cohost/run.o (bb boot:jank-aot compiles it).
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
aot=$(find target/jank-aot/_cache -mindepth 1 -maxdepth 1 -type d | head -1)
if [[ -z "${aot}" || ! -f "$aot/cohost/run.o" ]]; then
  echo "missing $root/target/jank-aot/_cache/*/cohost/run.o" >&2
  exit 1
fi
echo "aot-dir $aot"

one() {
  local mp="$1"
  python3 - "$mp" "$root" << 'PY'
import subprocess, sys, time
mp, root = sys.argv[1], sys.argv[2]
start = time.time_ns()
p = subprocess.run(
    ["jank", "--module-path", mp, "run", "dev/perf/jank_aot_load.jank", "--", "--load-only"],
    cwd=root, capture_output=True, text=True)
stamp = None
for line in p.stdout.splitlines():
    if line.startswith("LOAD-BEFORE-BOOT-NS "):
        stamp = int(line.split()[1])
if p.returncode != 0 or stamp is None:
    sys.stderr.write(p.stderr)
    sys.stderr.write(p.stdout)
    sys.exit(p.returncode or 1)
# jank's current_time and time.time_ns are both CLOCK_REALTIME nanoseconds.
print(stamp - start)
PY
}

echo "warmup jit"
one src >/dev/null
echo "warmup aot"
one "$aot:src" >/dev/null

measure() {
  local label="$1" mp="$2"
  local ns=()
  local i v
  for i in 1 2 3 4 5; do
    v=$(one "$mp")
    ns+=("$v")
    echo "$label run $i load-ns $v"
  done
  python3 - "${ns[@]}" << 'PY'
import sys
xs = sorted(int(x) for x in sys.argv[1:])
print("median-ns", xs[len(xs)//2])
PY
}

measure jit src
measure aot "$aot:src"
