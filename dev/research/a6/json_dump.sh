#!/usr/bin/env bash
# A6-2: cohost's rewritten form of every top-level form of bench/programs/json.clj on jank,
# one per line (JANK_BIN picks the binary).  bash dev/research/a6/json_dump.sh > out.txt
set -eu
export LC_ALL=C.UTF-8
f=$(mktemp --suffix=.clj)
{ echo "(defn run [] (let [ctx (cohost.boot/ctx)] (mapv (fn [form] (cohost.rewrite/rewrite ctx form)) '["; cat bench/programs/json.clj; echo "])))"; } > "$f"
bash dev/research/run-cohost-jank.sh "$f" 2>&1 | grep '^   => ' | tail -1 | sed 's/^   => //' \
  | bb -e '(doseq [x (clojure.edn/read-string {:readers {} :default (fn [t v] v)} (slurp *in*))] (println ";; ----") (clojure.pprint/pprint x))'
rm -f "$f"
