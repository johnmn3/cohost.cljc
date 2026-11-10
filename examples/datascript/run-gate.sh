#!/usr/bin/env bash
# DataScript-on-cohost example gate: a JVM body and a basilisk body
# over ONE shared Eve domain. The JVM body writes the datascript.cljc
# contract dataset (the lib repo's own harness driver, in-process),
# then asks the basilisk body two DataScript questions through
# cohost.bodies/call — the basilisk body answers from the database IT
# opened on the same domain. Fresh-process readbacks (JVM and
# basilisk) of the lib harness close the gate.
#
# PASS = CONTRACT-WRITE-OK; CALL1-DATOMS = 9; CALL2-NAMES =
# ["Ada" "Edsger" "Grace"]; LPY NAMES/DATOMS agree; CONTRACT-READ-OK
# from both fresh readers; seals equal across all four prints.
#
# Usage: bash run-gate.sh <workdir> <eve-dir> <cohost-dir> <mb-src-dir> <ds-repo-dir> <basilisk-bin>
#   eve-dir     a SeniorCareMarket/eve checkout (the Eve pin)
#   cohost-dir  this repository's checkout
#   mb-src-dir  atom.broker's multi-bodied/src (cohost.bodies + the
#               file hosting) at the cohost-on-Eve release pin
#   ds-repo-dir a johnmn3/datascript.cljc checkout
set -u
WORK="$(mkdir -p "$1" && cd "$1" && pwd)"
EVE="$(cd "$2" && pwd)"
COHOST="$(cd "$3" && pwd)"
MB="$(cd "$4" && pwd)"
DS="$(cd "$5" && pwd)"
BAS="$6"
EX="$COHOST/examples/datascript"
cd "$WORK"

python3 - "$EVE" "$COHOST" "$MB" "$DS" "$EX" <<'PYEOF'
import json, sys
eve, cohost, mb, ds, ex = sys.argv[1:6]
roots = ["src","adapters/slab-io/protocol/src","adapters/http/java/src",
"adapters/http-server/jdk/src","adapters/crypto/java/src",
"adapters/core/clojure/src","adapters/completion/clojure/src",
"adapters/coll/jvm/src","adapters/slab/heap/src",
"adapters/region/jvm-varhandle/src","adapters/encoding/java/src",
"adapters/int32/jvm/src","adapters/codec/byte-array/src",
"adapters/process/java/src","adapters/thread/jvm/src",
"adapters/types/jvm/src","adapters/types/js/src",
"adapters/journal-file/java/src"]
paths = [eve+"/"+r for r in roots] + [cohost+"/src", mb,
  ds+"/lib-ds", ds+"/lib", ds+"/lib-ds-deps", ds+"/harness",
  ex+"/src", ex+"/body"]
edn = "{:paths [" + " ".join(json.dumps(p) for p in paths) + "]\n :deps {org.clojure/clojure {:mvn/version \"1.12.0\"}}}\n"
open("deps.edn","w").write(edn)
PYEOF

LPY_ARGS=""
for r in src adapters/slab-io/protocol/src adapters/http/python/src adapters/crypto/python/src adapters/core/clojure/src adapters/completion/basilisp/src adapters/coll/basilisp/src adapters/slab/heap/src adapters/region/ctypes/src adapters/encoding/python/src adapters/int32/portable/src adapters/codec/byte-array/src adapters/process/python/src adapters/thread/python/src adapters/types/basilisp/src adapters/journal-file/python/src; do
  LPY_ARGS="$LPY_ARGS -p $EVE/$r"
done
LPY_ARGS="$LPY_ARGS -p $COHOST/src -p $MB -p $DS/lib-ds -p $DS/lib -p $DS/lib-ds-deps -p $DS/harness -p $EX/src -p $EX/body"
lpy() { (cd "$EVE" && PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 timeout 2400 "$BAS" run $LPY_ARGS -n "$1" "${@:2}"); }

export MB_DIR="$WORK/shared"
rm -rf "$MB_DIR"
echo "== run: basilisk body serves, JVM body writes + calls =="
lpy ds-example.lpy-body serve peer jvm > lpy.out 2> lpy.err &
LPY=$!
for i in $(seq 1 280); do
  grep -q "LPY READY" lpy.out 2>/dev/null && break
  kill -0 $LPY 2>/dev/null || break
  sleep 5
done
grep -q "LPY READY" lpy.out || { echo "LPY-NEVER-READY"; tail -30 lpy.err; exit 3; }
timeout 900 clojure -M "$EX/body/jvm_body.clj" > call.out 2> call.err
CALL_EXIT=$?
wait $LPY; LPY_EXIT=$?
timeout 900 clojure -M -m ds-harness.contract-check read "$MB_DIR/dsdb" > read.out 2> read.err
READ_EXIT=$?
lpy ds-harness.contract-check read "$MB_DIR/dsdb" > lpyread.out 2> lpyread.err
LPYREAD_EXIT=$?
{
  echo "CALL_EXIT=$CALL_EXIT LPY_EXIT=$LPY_EXIT READ_EXIT=$READ_EXIT LPYREAD_EXIT=$LPYREAD_EXIT"
  for f in lpy call read lpyread; do echo "--- $f.out ---"; cat $f.out; done
  for f in lpy call read lpyread; do echo "--- $f.err (tail) ---"; grep -v "JAVA_TOOL_OPTIONS\|^WARNING: Use of :paths" $f.err | tail -8; done
} > gate-out.txt
cat gate-out.txt
