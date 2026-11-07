# STATUS — cohost on Eve 0.9.3 (release assembly lane, Lane M)

- **Branch:** `lane-m/cohost-on-eve-093`
- **Base:** `muse/eve-cohost-impl` tip `5c50680fa9f9f68801239b8877aab438ecba7bcc`
  ("unit 9 Node-seat HttpHeaders fix", 2026-11-06 01:22 UTC).
- **Worktree:** `~/workspace/wt/cohost-093-pair/cohost` (pair dir holds the
  Eve sibling below; `../eve/src` resolves inside the pair).
- **Eve release commit (the pin):** `76fd94c3dc541a1e915d6759652114c9f6b392a8`
  = tag `v0.9.3` in SeniorCareMarket/eve ("Release Eve 0.9.3 — the Jank
  release", 2026-11-06 17:09 ET), resolved from the tag in `~/src/eve`.
- **Fleet note:** this branch's tip is the base cohost Agent 3's step 3
  (`agent3/eve-unit6-step3`) re-bases onto when this lane declares. Agent
  3's branch is not touched by this lane.

## The rebase (done, this bank)

1. **deps.edn `:cljs` alias** — `eve/eve` git dep pin moved
   `d241de3fbc27e5dd80d5f1e17661103b0680b9c0` (0.9.1 line) →
   `76fd94c3dc541a1e915d6759652114c9f6b392a8` (0.9.3 release); the stale
   comment beside it updated to name the 0.9.3 release and this lane.
   `dev/eve-cljs/eve_paths.clj` reads the pin from deps.edn, so the cljs
   seat's Eve `hosts.edn` row follows the pin with no code change.
2. **deps.edn `:eve` alias** — `{:extra-paths ["../eve/src"]}` unchanged
   (the mechanism is a sibling checkout; there is no version to move in
   the repo). **Required sibling state: the Eve clone checked out at
   `76fd94c3dc541a1e915d6759652114c9f6b392a8` (v0.9.3).** This lane's
   proofs use a dedicated worktree of `~/src/eve` at the tag:
   `~/workspace/wt/cohost-093-pair/eve` (detached @ 76fd94c3dc, verified
   by rev-parse). No other Eve worktree was disturbed.

## Binding census (whole tree)

- `deps.edn` `:cljs` git pin — MOVED (above). `:eve` sibling paths —
  mechanism unchanged, required state recorded (above).
- `cljs-node.edn`, `cljs-cli.edn` — name no Eve version; they consume Eve
  through the `:cljs` pin via `dev/eve-cljs/eve_paths.clj` (-Sdeps from
  bb.edn / bin/cohost). Follow the pin automatically.
- `cljs-browser.edn` — no Eve reference at all.
- `bb.edn` — no Eve version named; jank tasks consume Eve through Eve's
  own `hosts.edn` `:jank` row from the sibling/pinned checkout.
- `dev/u8/worker_probe.edn`, `dev/eve_http_node.edn`,
  `dev/cli-demo/deps.edn`, `templates/cohost-app/**` — no Eve version
  named.
- `doc/perf/**` *.edn — historical run outputs, not bindings; untouched.
- Older docs (`CLJS-DEP-STATUS.md`, `CLJS-DEP-REPORT.md`, unit STATUSes)
  name d241de3 as the state *at their writing*; they are records, left
  as-is. The live pin is deps.edn.

## Toolchain on this box (for the gates)

- `bb` / `clojure` in `/home/hatch/.local/bin` (PATH export needed in
  non-login shells).
- Maven Central is unreachable directly (egress proxy); the `:eve`
  sibling route needs no Maven. The `:cljs` git-dep fetch and the
  ClojureScript mvn dep ride the curl-seeded local repo / gitlibs —
  gates below use the sibling route (the impl branch's own discipline).

## Gates owed (run once, at the end)

- JVM: `bb boot:jvm` (+ the entry gate) — battery 221/221, libraries
  62/62 expected; seat probes `eve_cohost_check.clj` (22),
  `eve_cohost_check_u5.clj` (27), `eve_cohost_check_u7.clj` (16) via the
  `:eve` sibling at 0.9.3.
- cljs seat: pin fetch + cljs-node build/run smoke against the 0.9.3
  checkout's hosts.edn row.
- jank seat: battery on j.slaa.us; the standing pair
  (`subs count nth first last butlast` — "ERR Unterminated vector.";
  `Character outside ASCII` — "ERR Unexpected end of file.") gets its
  disposition vs the new bindings; unit-6 refinement pins carried or
  re-run with evidence.
