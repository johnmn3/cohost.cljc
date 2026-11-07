# RELEASE AUDIT — `release/eve-in-cohost` (cohost repo)

Independent audit, operator's order 2026-11-07 ~4:28 AM ET. Auditor lane:
`lane-m/cohost-release-audit`, based on the release tip
`60353a0808e440cd798d43c61acf4b6fb966abee` (verified against
`git ls-remote`). Every claim in `RELEASE-MANIFEST.md`,
`COLLATION-STATUS.md`, and `RELEASE-CLEAR-STATUS.md` was checked
firsthand; nothing below is taken from the manifest's word. The audit
was read-only toward the release branch and `main`; the only writes are
this report (on the audit branch) and scratch checkouts.

## 1. CONTENTS — verified

`git diff origin/main...origin/release/eve-in-cohost`: **223 files
changed, 205 added / 18 modified, +25,373 / −136.** Every changed file
is accounted for by a manifest row:

| Group | Files | Owner row |
|---|---|---|
| `src/` | 21 | Units 1–9 code (below) |
| `dev/` | 97 | Unit probes/harness: `dev/u4*`, `dev/u5/`, `dev/u7/`, `dev/u8/`, `dev/eve-cljs/` bridges, `dev/eve_http_*` harness, `dev/perf/` |
| `doc/` | 78 | Unit reports/declarations, guide chapters 12–15, the landing records |
| `logs-093/`, `logs-collation/`, `logs-release-clear/` | 11 | Gate logs cited by the manifest |
| Repo root + config | 16 | `deps.edn` (pin), `bb.edn`, `cljs-cli.edn`, `cljs-node.edn`, `bin/cohost`, `README.md` (index), root probe/check scripts + `LANDING-U4-STATUS.md` (see §5) |

Unit rows, spot-verified in the tree (not just the diff):

- **Unit 1/2** — `src/cohost/thread.cljc` (new, 787 lines) + host leaves
  changed in `src/cohost/hosts/` (`jvm.clj`, `java.clj`, `jank_host.jank`,
  `cljs.cljs`, new `cljs_eve.cljs`). `UNIT1-REPORT.md`, `UNIT2-REPORT.md`
  present.
- **Unit 3** — seam identity in `shim.cljc`/`adapter.cljc`;
  `UNIT3-REPORT.md` present.
- **Unit 4** — Eve-backed collections (`shim.cljc`, `boot.cljc`);
  `UNIT4-REPORT.md` + `UNIT4-REMAINDER-REPORT.md` present.
- **Unit 5** — `src/cohost/atom.cljc` + `src/cohost/atom_eve.clj` (new);
  `UNIT5-REPORT.md` present.
- **Unit 6** — landed via the REBASE-093 collation as claimed:
  `src/cohost/memory.cljc` + `src/cohost/memory_eve.clj` (new) are in the
  tree, and its gate log is on the branch (§3, jank pins 10/108/0/0).
- **Unit 7** — tiered atoms in `atom.cljc`; `doc/eve-cohost/unit7/REPORT.md`
  present.
- **Unit 8** — `src/cohost/hosts/cljs_realm.cljs` (new);
  `doc/eve-cohost/unit8/REPORT.md` present.
- **Unit 9** — `src/cohost/http.cljc`, `http_eve.clj`, `http_eve.cljs`,
  `ring.cljc` (all new).
- **Unit-9 jank AOT fix** — present verbatim at `src/cohost/shim.cljc:2085`:
  `(def -getDefaultPort (http-member "getDefaultPort" {:cohost.http/url (fn [u] (get default-ports (:scheme u) -1))}))`
  — the string `case` replaced by the map lookup, as claimed.

Nothing claimed-but-absent; no changed file without an owning row.

## 2. PIN — verified

`deps.edn` (release tip) names exactly one Eve pin, in the `:cljs` alias:

```clojure
eve/eve {:git/url "https://github.com/SeniorCareMarket/eve"
         :git/sha "76fd94c3dc541a1e915d6759652114c9f6b392a8"}
```

That is the v0.9.3 release commit the manifest names. The old pin
`d241de3` survives **only** as a historical comment in `deps.edn`
("the previous pin was d241de3, the 0.9.1 release line") and in
doc/log prose — it is not an active pin anywhere. No other `:git/sha`
exists in `deps.edn`, `bb.edn`, `cljs-cli.edn`, or `cljs-node.edn`.
The `:eve` sibling-paths mechanism (`../eve/src`) is unchanged, as claimed.

## 3. GATES — reproduced / logs verified

### JVM battery — re-run firsthand on the build box (j.slaa.us)

Fresh checkout of the release branch (tip `60353a0`) at
`~/cohost-release-audit/cohost` on the build box, Eve sibling
`~/cohost-release-audit/eve` cloned fresh and verified clean at
`76fd94c` ("Release Eve 0.9.3"), Babashka 1.13.226, `bb boot:jvm`,
run **twice** (logs: `doc/eve-cohost/audit-logs/boot-jvm-buildbox-run1.log`
and `-run2.log`, committed with this report; originals on the build box
under `~/cohost-release-audit/logs/`). Both runs printed identically:

```
booted as stock: 465   from host: 202   natives: 66   skipped: 58   errors: 0
battery: 220/221 stock behaviors pass
   FAIL reduce over an eduction; a :tag that is data
        got      "ERR class clojure.core.Eduction cannot be cast to class
                  clojure.lang.IReduce (clojure.core.Eduction and
                  clojure.lang.IReduce are in unnamed module of loader 'app')"
        expected [9 {:tag :int}]
libraries: 62/62 cases pass across 12 clojure.* libraries
```

The banked claim of 221/221 is genuine — `logs-collation/boot-jvm.log`
on the branch prints exactly `booted as stock: 465 … errors: 0`,
`battery: 221/221`, `libraries: 62/62`, verified firsthand. The one-row
delta on the build box is the **eduction canary**, and it is the
branch's own documented, dispositioned exception:
`UNIT4-REMAINDER-REPORT.md` (on this branch) records that this exact
row reads **220/221 under the `-M:eve` launch shape and 221/221 under
shapes without it**, that the error above is byte-identical to the one
it quotes, and that the mechanism is stock Clojure 1.12.0's own
`clojure.core.protocols` CollReduce dispatch — a tie-break among three
unrelated protocol impls that rides on JVM hash-iteration order, i.e.
on the box/process environment. It fires in plain stock Clojure with
no cohost and no Eve involved; no cohost or Eve code is implicated,
and the report dispositions it as **not a defect** — the row stands in
the battery as the canary for that stock fragility. Audit position:
the gate is green modulo this one pre-documented, environment-shaped
row; an operator re-running `bb boot:jvm` on the build box will see
220/221 and should not read it as release breakage. **Named exception,
not a blocker.**

### cljs — banked logs verified

- `doc/eve-cohost/logs/cli-check-cljs.log`: 7 lines reading `SAME`
  (7/7 as claimed).
- `logs-093/boot-cljs.log`: `booted as stock: 554 … errors: 0`,
  `battery: 219/221`, `libraries: 62/62`; FAIL pair exactly
  `FAIL numeric tower` and `FAIL defmacro &form &env`. The same pair
  appears in the pre-collation impl-line records on the branch
  (`doc/eve-cohost/logs/boot-cljs-baseline.log`,
  `gates-cljs-after-fix.log`, `LANDING-u2cljs-u5-DECLARATION.md`) —
  the standing pristine pair, **not new breakage**.

### jank — banked logs verified

- `logs-release-clear/boot-jank.log` vs `logs-093/jank-battery.log`:
  the verdict lines (`booted as stock: 544 … errors: 0`,
  `battery: 219/221`, both FAIL lines, `libraries: 62/62`) were
  diffed programmatically — **byte-identical**, as claimed.
  FAIL pair: `FAIL subs count nth first last butlast` and
  `FAIL Character outside ASCII` — the standing released-bindings
  pair, present in the pre-collation rebase line's own record
  (`logs-093/`), **not new breakage**.
- `logs-093/jank-pins.log` (unit 6 refinement pins): `Ran 10 tests
  containing 108 assertions. 0 failures, 0 errors.` — as claimed.
- `logs-release-clear/unit9-jank.log`: contains the jank analyze error
  `invalid-cpp-member-call` — "There is no member function `trim`…"
  at line 187 — as the manifest reports (§7).

## 4. MOVE MECHANICS — verified

- `git rev-list --left-right --count origin/main...origin/release/eve-in-cohost`
  = **0 / 157**: the release branch is 157 commits ahead of `main` and
  **0 behind** — `main`'s tip (`5f58f3a`, the httpbench doc commit) is a
  strict ancestor of the release tip.
- Dry-run performed in a scratch worktree only
  (`~/workspace/wt/cohost-move-scratch`, a checkout of `origin/main`;
  `git merge --no-commit --no-ff origin/release/eve-in-cohost`, then
  `git merge --abort`; scratch left clean; neither `main` nor the
  release branch was written): **automatic merge, zero conflicts**,
  staging exactly the 223-file / +25,373 / −136 change set of §1.
- The move the operator would be approving: point `main` at
  `60353a0` — a **fast-forward** suffices (no merge commit required);
  a merge commit would land the identical tree. Nothing else moves.

## 5. HYGIENE — verified, with recommendations

- **Conflict-marker sweep** on the release branch's *committed* tree,
  whole tree, no extension filter: line-anchored `git grep` for
  `<<<<<<<` / `>>>>>>>` at line start returns **zero hits**. Raw
  substring hits land in 10 files, all under `doc/eve-cohost/`, and
  all are prose *quotations* of the markers in landing records
  (spot-verified: `COLLATE-STATUS.md:41` — "(<<<<<<< / >>>>>>>)." in a
  sentence describing a sweep). No real conflict markers ship.
- **Lane debris that would ship to `main`:**
  - *Release record (recommend SHIP):* the ~17 `*-STATUS.md` /
    declaration files under `doc/eve-cohost/`, `RELEASE-MANIFEST.md`,
    `COLLATION-STATUS.md`, `RELEASE-CLEAR-STATUS.md`, and the gate-log
    directories (`logs-093/`, `logs-collation/`, `logs-release-clear/`,
    `doc/eve-cohost/logs/`, `doc/eve-cohost/node-headers-logs/` —
    ≈794 KB total). The manifest cites these as the release's evidence
    trail; they are documentation, inert at runtime.
  - *Root scratch (recommend STRIP before the move; not a blocker):*
    ten files at the repo root — `probe_atom.clj`, `probe_collections.clj`,
    `probe_memory.clj`, `probe_thread.clj`, `probe_tier.clj`,
    `eve_cohost_check.clj`, `eve_cohost_check_u5.clj`,
    `eve_cohost_check_u7.clj`, `eve_http_check.clj`,
    `LANDING-U4-STATUS.md`. Verified by grep: **no shipped config or
    code references them** (`bb.edn`, `deps.edn`, `cljs-*.edn`, `bin/`,
    `src/` — zero hits). They are unit-probe scripts and one lane
    status file; inert, but they would land at `main`'s root.
  - The `dev/` tree (97 files) also ships; it is the project's
    established probe/harness area (present in kind on `main` already)
    and two build wirings reference parts of it (`bb.edn` loads
    `dev/perf/*` and `dev/eve-cljs/*`; `cljs-*.edn` foreign-libs point
    at `dev/eve-cljs/bridges/*.js`), so it is load-bearing where it
    matters and not debris.

## 6. DOCS — verified

- Chapters present: `doc/guide/12-threading-with-eve.md`,
  `13-memory-with-eve-atoms.md`, `14-tiered-atoms.md`,
  `15-eve-coverage-and-status.md`.
- README index entries 12–15 present (README lines 189–192), pointing
  at exactly those files.
- States match the collated tree: ch. 14 (line 58) states unit 6 is
  "landed on the [mainline]"; ch. 15's "Not yet, anywhere" section
  lists **"Slab residence on the jank seat (unit 6) — LANDED"** with
  the pin numbers, and the worker-realms/shared-heap coverage row
  (unit 8) reads **LANDED**. No chapter still describes unit 6 or
  unit 8 as in-progress. (Ch. 15 also defines its GATE-GREEN state as
  "landing in flight; not yet on the mainline" — that is the state
  *definition*, not a claim about these units; no unit-6/8 cell uses it.)

## 7. THE ONE OPEN ITEM — `.trim` is harness-only, verified

- The form is at `dev/eve_http_programs.cljc:187` on the release branch:
  `(let [trim (fn [s] (.trim ^String s)) …` inside a Content-Type
  header-parse helper in the shared *gate programs* file.
- Reachability, checked firsthand: `git grep` for
  `eve_http_programs` / `eve-http-programs` / `eve_http_seats` across
  `src/`, `bb.edn`, `deps.edn`, `cljs-cli.edn`, `cljs-node.edn`, and
  `bin/` returns **zero hits**. `deps.edn` carries no `dev` path.
  The only files referencing the eve_http harness are the harness's
  own: `dev/eve_http_seats.clj` (the 36-check seat runner) and
  `dev/eve_http_node.cljs`. The file is loaded only when a developer
  runs the unit-9 seat harness by hand; **no shipped code path —
  library, CLI, boot, or build — reaches it.**
- Conclusion: the 0/36 jank harness leg is a harness-side defect on
  Agent 3's line (one JVM member call, `String.trim`, that jank's
  analyzer rejects), exactly as the manifest dispositions it.
  **Not a release blocker.**

## VERDICT

**READY WITH NAMED EXCEPTIONS** — (1) the JVM battery's eduction
canary row prints 220/221 on the build box (221/221 in the banked
collation log; the delta is the branch-documented stock Clojure
1.12.0 protocol-dispatch tie-break, no cohost/Eve code implicated);
(2) the standing cljs pair (numeric tower; defmacro &form &env) and
jank pair (subs count nth first last butlast; Character outside
ASCII) ship as-is, reproduced identically from pre-release records;
(3) unit 9's jank harness leg stands at 0/36 on one harness-side
`.trim` call in `dev/` (Agent 3's fix; no shipped path reaches it);
(4) ten unreferenced probe/check files at the repo root would land
on `main` unless stripped first (recommendation, not a blocker).
Contents, the 0.9.3 pin, the cljs/jank gate records, the docs, and
the move itself (fast-forward, 157 commits, zero conflicts in
dry-run) all verified firsthand. The branch moves to `main` only on
the operator's word; this audit did not move it.
