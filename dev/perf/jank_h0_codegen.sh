#!/usr/bin/env bash
# jank-FAST Step 5 H0. Compile dev/perf/h0/codegen.jank and print the lines
# that show unboxing and boxing. Raw jank, no cohost.
#
#   bash dev/perf/jank_h0_codegen.sh
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
jank compile-module h0.codegen --module-path dev/perf -O3 --output-target cpp --target-dir target/jank-h0
python3 - << 'PY'
import pathlib
p = next(pathlib.Path("target/jank-h0").rglob("codegen.cpp"))
text = p.read_text().split("jank_load_h0_codegen")[0]
name = None
buf = []
keys = ("convert<", "runtime::add", "runtime::lt", "runtime::inc",
        "runtime::sub", "static_cast", "object_ref s", "auto s", "auto &&")
def flush():
    if not name or name.startswith("jank::"):
        return
    print(f"\n== {name}")
    for b in buf:
        s = b.strip()
        if any(k in s for k in keys):
            print(s)
for line in text.splitlines():
    if line.startswith('extern "C"'):
        flush()
        name = line.split("(")[0].split()[-1]
        buf = []
    else:
        buf.append(line)
flush()
PY
