#!/usr/bin/env bash
# Run a raw-jank probe two ways: JIT (jank run -O3 --eager, as H0 did) and AOT
# (compile-module -O3, then run-main, as bb bench:programs jank-aot does).
#   bash dev/research/run-jank.sh dev/research/<probe>.jank [jit|aot|both]
# The harness (dev/research/harness.jank) is prepended; the AOT module is
# research.<probe> under target/research/<probe>/.
set -euo pipefail
probe="$1"; mode="${2:-both}"
name="$(basename "$probe" .jank)"
root="target/research/$name"
mkdir -p "$root/src/research"
# companion modules: dev/research/<probe>.d/** is copied under the module path
[ -d "dev/research/$name.d" ] && cp -r "dev/research/$name.d/." "$root/src/"
# a probe whose first lines say ";; requires: a.b c.d" gets those namespaces
# required (the ns form's :require for AOT; a require call for JIT)
reqs="$(grep -m1 '^;; requires:' "$probe" | sed 's/^;; requires://')"
nsreq=""; jitreq=""
for r in $reqs; do nsreq="$nsreq [$r]"; jitreq="$jitreq '$r"; done
[ -n "$nsreq" ] && nsreq=" (:require$nsreq)"
{ echo "(ns research.$name$nsreq)"; cat dev/research/harness.jank "$probe"; echo '(defn -main [& _] (run))'; } > "$root/src/research/$name.jank"
{ [ -n "$jitreq" ] && echo "(require$jitreq)"; cat dev/research/harness.jank "$probe"; echo '(run)'; } > "$root/jit.jank"
if [ "$mode" = jit ] || [ "$mode" = both ]; then
  echo "== $name JIT (jank run -O3 --eager)"
  jank --module-path "$root/src" run -O3 --eager "$root/jit.jank"
fi
if [ "$mode" = aot ] || [ "$mode" = both ]; then
  echo "== $name AOT (compile-module -O3; run-main)"
  rm -rf "$root/_cache"
  jank --module-path "$root/src" compile-module "research.$name" -O3 --target-dir "$root" >"$root/compile.log" 2>&1 || { echo "COMPILE FAILED"; tail -20 "$root/compile.log"; exit 1; }
  cache="$(ls -d "$root"/_cache/* | head -1)"
  jank --module-path "$cache:$root/src" run-main "research.$name"
fi
