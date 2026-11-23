#!/usr/bin/env bash
# Census runner — cohost syntax lane step 2. Raw outputs to census-raw/.
set -u
export PATH=/home/hatch/.local/bbnew-bin:/home/hatch/.local/bin:$PATH
export CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig
cd ~/workspace/syntax-formalization/lanes/cohost
mkdir -p census-raw
run_bucket () {
  local name="$1" path="$2"
  echo "== bucket $name ($path) =="
  clj-kondo --lint "$path" --config '{:output {:format :json}}' > "census-raw/kondo-$name.json" 2>"census-raw/kondo-$name.err"
  echo "kondo-$name exit=$?"
  bb splint "$path" > "census-raw/splint-$name.txt" 2>&1
  echo "splint-$name exit=$?"
  carve --opts "{:paths [\"$path\"] :report {:format :text}}" > "census-raw/carve-$name.txt" 2>&1
  echo "carve-$name exit=$?"
  cljfmt check "$path" > "census-raw/cljfmt-$name.txt" 2>&1
  echo "cljfmt-$name exit=$?"
}
run_bucket own src
run_bucket vendored resources/cohost/clojure-1.12.0
run_bucket dev dev
echo "CENSUS-RUNS-DONE"
