# REPORT — cohost on Eve 0.9.3 (release assembly, Lane M)

**Branch:** `lane-m/cohost-on-eve-093`, off `muse/eve-cohost-impl` @
`5c50680fa9f9f68801239b8877aab438ecba7bcc`. Assembly only — nothing
moved to cohost main; the move is the operator's word.

## The rebase

- **Pin:** Eve `76fd94c3dc541a1e915d6759652114c9f6b392a8` = tag `v0.9.3`,
  SeniorCareMarket/eve main ("Release Eve 0.9.3 — the Jank release").
- `deps.edn :cljs` — `eve/eve` git sha `d241de3fbc27…` (0.9.1 line) →
  `76fd94c3dc54…`; stale comment updated. `dev/eve-cljs/eve_paths.clj`
  reads the pin from deps.edn, so the cljs seat follows automatically.
- `deps.edn :eve` — sibling-paths mechanism unchanged (no version lives
  in the repo). **Required sibling state: an Eve checkout at
  `76fd94c3dc541a1e915d6759652114c9f6b392a8`.** Proofs used a dedicated
  worktree of `~/src/eve` at the tag
  (`~/workspace/wt/cohost-093-pair/eve`, rev-parse verified); no other
  Eve worktree was disturbed.
- One nuance the assembly surfaced: the tag's own tree predates the
  identity-mechanism collation, which landed on the release line the
  same evening (PR #27 lineage). The jank-seat gates that exercise the
  mechanism therefore ran against the release line at the bb-fix commit
  `c9c10aea1baf…` — **one commit past the tag**, with the jank
  completion byte-identical between the two (the delta was the
  Babashka adapter sites). Every other gate ran on the tag itself.
  One mechanism throughout: the release's own, never a second one.

## Binding census

Moved: the `:cljs` git pin. Mechanism-only (recorded, unchanged): the
`:eve` sibling paths. No version named: `cljs-node.edn`, `cljs-cli.edn`,
`cljs-browser.edn`, `bb.edn`, `dev/u8/worker_probe.edn`,
`dev/eve_http_node.edn`, `dev/cli-demo/deps.edn`, `templates/**`.
Historical: `doc/perf/**` outputs and the older unit docs naming the
0.9.1 pin (records of their time, left as written).

## Gate table (logs in `logs-093/`)

| Gate | Result |
|---|---|
| JVM battery (`bb boot:jvm`, sibling @ 0.9.3) | **boot errors 0; battery 221/221; libraries 62/62** |
| Seat probe unit 1 (`eve_cohost_check.clj`, `:eve` sibling) | **21/21 PASS** (the probe's own summary line) |
| Seat probe unit 5 (`eve_cohost_check_u5.clj`, full `:jvm` hosts.edn row @ 0.9.3) | **31 checks, 0 failures** |
| Seat probe unit 7 (`eve_cohost_check_u7.clj`, same runner) | **17 checks, 0 failures** |
| cljs pin fetch @ `76fd94c3dc…` | checkout materialized; classpath carries its src + adapter roots |
| cljs seat battery (`bb boot:cljs`, Node) | **boot errors 0; battery 219/221; libraries 62/62** |
| jank refinement pins (release-line mechanism tree, j.slaa.us) | **10 tests / 108 assertions / 0 failures / 0 errors** (identity 5, identity-table 1, host-completion 4) |
| jank seat battery (`bb boot:jank-aot`, j.slaa.us, release-line sibling) | **boot errors 0; battery 219/221; libraries 62/62** |

## Standing-pair dispositions

- **Jank seat — 219/221, the standing pair, reproduced verbatim on the
  new bindings. Known-standing, NOT new-on-0.9.3:**
  1. `subs count nth first last butlast` — got `"ERR Unterminated
     vector. (outer: Unterminated vector.)"`, expected
     `["el" 5 2 :d \a 3 (1 2)]`.
  2. `Character outside ASCII` — got `"ERR Unexpected end of file.
     (outer: Unexpected end of file.)"`, expected
     `[true true true false]`.
  Both are jank's reader (unit 9's known non-ASCII / reader gap
  family), identical in shape and text to every prior gate on the
  pre-release bindings.
- **cljs seat — also 219/221, but a DIFFERENT pair** (do not conflate):
  `numeric tower` and `defmacro &form &env`, the documented
  pristine-base pair for that seat (CLJS-DEP-REPORT.md; verified
  identical on an unmodified base worktree when first recorded).
  Known-standing, NOT new-on-0.9.3.

## For the fleet

This branch's tip (see REBASE-093-STATUS.md for the live tip) is the
base cohost Agent 3's step 3 (`agent3/eve-unit6-step3`) re-bases onto.
Agent 3's branch was not touched. Nothing here moves to cohost main.
