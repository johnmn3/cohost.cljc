# Release collation — release/cohost-on-eve-0932

One line per fix or re-pin brought into this branch: fix, source commit, proof.

- Eve pin 76fd94c3 (v0.9.3) -> 2b1e86f4 (release/eve-0.9.3.2), then 231e565d (clojerl ^long sweep + CHANGELOG), then **73a64110** (clje-dataset-fixes 4dadcf3c, merged --no-ff as 9f503d4; :clje arms only). Source: SeniorCareMarket/eve release/eve-0.9.3.2. Proof for 73a64110: cohost's JVM seat on a fresh Eve sibling clone at 73a641108e52 (java -XX:hashCode=3 ... clojure.main -m cohost.run, Clojure 1.12.0, load1 1.26): boot 791 forms, errors 0; battery 221/221 stock behaviors pass; libraries 62/62 cases across 12 clojure.* libraries. The cohost seats Eve touches (JVM :eve alias, CLJS :cljs git dep) are not :clje.
- Eve pin 73a64110 -> **cae5db96** (ants-demo disk guards in docs/stm-ants + CHANGELOG line; `git diff 73a64110 cae5db96 -- src adapters` empty, no src change). Proof: cohost JVM seat on a fresh Eve clone at cae5db96914b (load1 0.66): boot errors 0; battery 221/221; libraries 62/62.
- basilisp `list*` one-liner in src/cohost/thread.cljc (from lane-m/cohost-on-eve-0931 multi-bodied/patches/cohost-thread-lpy-liststar.patch; RELEASE-PREP ruling Q8). Proof: pending (hatch #22 lpy leg / two-body gate); source-evident producer fix, not yet run on a passing basilisp.
