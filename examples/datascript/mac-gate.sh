#!/bin/bash
# DataScript-on-cohost two-body gate on the Mac (basilisk 0.5.1 seat).
# Stages: Eve (seed lib source), cohost.cljc@datascript-example (the
# gate), atom.broker (multi-bodied/src), harness embedded below.
# Leg 1: D1 repro attempt on the SEED lib (basilisk require of
# datascript.db). Leg 2: the one-arm :lpy fix applied, full gate.
set -u
export PATH="$HOME/.local/bin:$PATH"
# prefer a JDK 21 if one is installed; otherwise the default java
JH=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
if [ -n "$JH" ]; then export JAVA_HOME="$JH" PATH="$JH/bin:$PATH"; fi
SELFDIR="$(cd "$(dirname "$0")" && pwd)"
R="$HOME/ds-cohost-gate"
rm -rf "$R"; mkdir -p "$R"; cd "$R"
echo "== versions =="; java -version 2>&1 | head -1; clojure --version 2>&1 | head -1; basilisk version 2>&1 | head -1; uptime
git clone -q --depth 1 https://github.com/SeniorCareMarket/eve eve || echo CLONE-EVE-FAILED
cp -r "$SELFDIR/../../.." cohost-tmp && mv cohost-tmp cohost
git clone -q --depth 1 -b lane-m/cohost-on-eve-0931 https://github.com/johnmn3/atom.broker atom || echo CLONE-ATOM-FAILED
MB="$R/atom/multi-bodied/src"; [ -d "$MB" ] || MB="$R/atom/docs/clojure-runtime/multi-bodied/src"
echo "MB=$MB"; ls "$MB/cohost" | head
mkdir -p ds/lib-ds ds/lib ds/lib-ds-deps ds/harness/ds_harness
cp -r eve/contract-test/lib-ds/datascript ds/lib-ds/ && cp eve/contract-test/lib-ds/data_readers.cljc ds/lib-ds/
cp -r eve/contract-test/lib/me ds/lib/
cp -r eve/contract-test/lib-ds-deps/extend_clj ds/lib-ds-deps/
cat > ds/harness/ds_harness/contract_check.cljc <<'HARNESS_EOF'
(ns ds-harness.contract-check
  "The lib's contract proof driver (datascript.cljc repo, harness/).

   One fixed dataset in ONE persistent Eve atom (a disk domain, journaled
   by default — the runner verifies the journal artifact exists in the
   domain directory). Two modes, meant to run as two processes:

     write <dir>   create/open the domain, transact the dataset inside
                   swap!s, run the check battery, print verdicts + seal
     read  <dir>   fresh process: reopen, deref, run the same battery,
                   print verdicts + seal (the runner compares seals)

   Battery: create (empty-db in the atom) -> transact -> datalog query
   -> pull -> entity -> datom count. Every check prints PASS/FAIL with
   its expected and actual values; the mode line ends CONTRACT-*-OK
   only when every check passed."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [datascript.core :as d]))

(def ^:private atom-name "ds-harness/contract-db")

(def ^:private schema
  {:friend {:db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}})

(def ^:private tx-data
  [{:db/id 1 :name "Ada" :age 36 :friend [2 3]}
   {:db/id 2 :name "Grace" :age 30 :friend [3]}
   {:db/id 3 :name "Edsger" :age 34}])

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    (when (nil? @a)
      (swap! a (fn [_] (d/empty-db schema))))
    a))

(defn- battery [db]
  (let [names (sort (d/q '[:find [?n ...] :where [?e :name ?n]] db))
        age34 (d/q '[:find [?e ...] :where [?e :age 34]] db)
        pulled (d/pull db [:name {:friend [:name]}] 1)
        friend-names (sort (map :name (:friend pulled)))]
    {:datoms {:expect 9 :actual (e/count (d/datoms db :eavt))}
     :names {:expect ["Ada" "Edsger" "Grace"] :actual (vec names)}
     :age34 {:expect [3] :actual (vec (sort age34))}
     :entity1-name {:expect "Ada" :actual (:name (d/entity db 1))}
     :pull1-name {:expect "Ada" :actual (:name pulled)}
     :pull1-friends {:expect ["Edsger" "Grace"] :actual (vec friend-names)}}))

(defn- verdicts [checks]
  (into {}
        (map (fn [[k {:keys [expect actual]}]]
               [k {:pass (= expect actual) :expect expect :actual actual}])
             checks)))

(defn -main [& [mode dir]]
  (try
    (let [a (open-atom dir)]
      (when (= mode "write")
        (swap! a (fn [db] (d/db-with db tx-data))))
      (let [checks (verdicts (battery @a))
            ok (every? :pass (vals checks))]
        (println (pr-str {:mode (keyword mode)
                          :checks checks
                          :seal (atom/seal a)
                          :verdict (if ok
                                     (if (= mode "write") :CONTRACT-WRITE-OK :CONTRACT-READ-OK)
                                     :CONTRACT-FAIL)}))))
    (catch #?(:cljs :default :lpy python/Exception :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))

HARNESS_EOF
echo "== staged ds tree =="; find ds -type f | wc -l
EVE_JVM_ROOTS="src adapters/slab-io/protocol/src adapters/http/java/src adapters/http-server/jdk/src adapters/crypto/java/src adapters/core/clojure/src adapters/completion/clojure/src adapters/coll/jvm/src adapters/slab/heap/src adapters/region/jvm-varhandle/src adapters/encoding/java/src adapters/int32/jvm/src adapters/codec/byte-array/src adapters/process/java/src adapters/thread/jvm/src adapters/types/jvm/src adapters/types/js/src adapters/journal-file/java/src"
LPY=""
for r in src adapters/slab-io/protocol/src adapters/http/python/src adapters/crypto/python/src adapters/core/clojure/src adapters/completion/basilisp/src adapters/coll/basilisp/src adapters/slab/heap/src adapters/region/ctypes/src adapters/encoding/python/src adapters/int32/portable/src adapters/codec/byte-array/src adapters/process/python/src adapters/thread/python/src adapters/types/basilisp/src adapters/journal-file/python/src; do LPY="$LPY -p $R/eve/$r"; done
LPY="$LPY -p $R/ds/lib-ds -p $R/ds/lib -p $R/ds/lib-ds-deps -p $R/ds/harness"
echo "== D1 REPRO (seed lib, basilisk require datascript.db) =="
(cd "$R/eve" && PYTHONDONTWRITEBYTECODE=1 timeout 1200 basilisk run $LPY -e "(require (quote datascript.db)) (println :DS-DB-LOADED)") > d1-repro.out 2> d1-repro.err
echo "d1 repro rc=$?"; tail -5 d1-repro.out; tail -15 d1-repro.err
echo "== apply the D1 one-arm fix (the lpy-standing arm) =="
python3 - <<'PYEOF'
p = "ds/lib-ds/datascript/db.cljc"
s = open(p).read()
old = """ :lpy  [(defn- ^Boolean tx-id?
     [e]
     (or (identical? :db/current-tx e)
       (.equals ":db/current-tx" e) ;; for datascript.js interop
       (.equals "datomic.tx" e)
       (.equals "datascript.tx" e)))]"""
new = """ :lpy  [(defn- tx-id?
     [e]
     (or (identical? :db/current-tx e)
       (= e ":db/current-tx") ;; for datascript.js interop
       (= e "datomic.tx")
       (= e "datascript.tx")))]"""
assert old in s, "seed arm not found"
open(p, "w").write(s.replace(old, new))
print("ARM-FIX-APPLIED")
PYEOF
echo "== GATE =="
bash cohost/examples/datascript/run-gate.sh "$R/gate" "$R/eve" "$R/cohost" "$MB" "$R/ds" "$(command -v basilisk)" > gate-run.log 2>&1
echo "gate rc=$?"; tail -40 gate-run.log
echo "== BANK =="
mkdir -p cohost/examples/datascript/gate-results/mac-2026-11-10
cp "$R/gate/gate-out.txt" cohost/examples/datascript/gate-results/mac-2026-11-10/ 2>/dev/null || cp gate-run.log cohost/examples/datascript/gate-results/mac-2026-11-10/
cp d1-repro.out d1-repro.err cohost/examples/datascript/gate-results/mac-2026-11-10/ 2>/dev/null || true
cd cohost && git config user.email "mac-runner@localhost" && git config user.name "mac-runner (ds-cohost gate)"
git add examples/datascript/gate-results && git commit -q -m "datascript gate results (Mac, basilisk 0.5.1): two-body gate + D1 repro" && git push -q origin datascript-example && echo BANKED || echo PUSH_FAILED
echo "DS-GATE-MAC-DONE"
