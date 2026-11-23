# STATUS — cohost.cljc, syntax-formalization Phase 2 lane

Branch: `syntax/formalization-1` on johnmn3/cohost.cljc.
Base: origin main `1db42b213585129c3f4384a30f0fa0bcfffa24ae`
(ls-remote-verified at lane start, Nov 22 2026).
Clone: ~/workspace/syntax-formalization/lanes/cohost (fresh, this lane only).
Eve sibling for the :eve alias: ../eve → ~/workspace/landing-collation/scm-eve
(symlink, environment only, not committed).

Tool pins on PATH: clj-kondo 2026.10.26 · cljfmt 0.16.6 · Splint 1.25.0 ·
Carve (bbin) · babashka 1.13.230 (bbnew-bin leads PATH; stock bb 1.3.190
cannot run Splint) · CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig.

## Scope (stated in the open, per plan §6 and the lane brief)

- **Own sources** = `src/` — 52 clj-family files (.clj/.cljc/.cljs that
  kondo/Splint/cljfmt read; src also carries host files in other
  languages — .cljd/.clje/.jank/.lpy/.lg/.phel/.php/.glj/.js — outside
  every checker's language coverage, same gap shape as Phase 1's .glj).
  The gate (`bb lint`) covers src/.
- **Vendored stock tree** = `resources/cohost/clojure-1.12.0/` — 53
  clj-family files, byte-comparable to upstream Clojure 1.12.0 by
  design. Censused SEPARATELY; NOT fixed and NOT silently excluded —
  the exception proposal is written in CENSUS.md for the coordinator.
- **dev/** = 165 clj-family files (bb load-file scripts, perf/install
  tooling). Censused informationally in CENSUS.md; not in the gate —
  these scripts run inside bb's :init context and need their own
  config treatment; proposal in CENSUS.md.

## Steps

- [x] 0. Fresh clone, origin main verified, branch created.
- [x] 1. Fleet templates installed byte-identical (.cljfmt.edn,
      .clj-kondo/config.edn, .splint.edn, AGENTS.md verbatim); fleet bb
      tasks merged into bb.edn adapted to src/ paths; kondo cache
      generated over the real classpath (clojure -Spath -M:eve).
- [x] 2. Census (kondo / Splint / Carve / cljfmt) — own vs vendored
      counted separately → CENSUS.md (own: kondo 214, Splint 1,546,
      Carve 962, cljfmt 52/52; vendored: kondo 540, Splint 2,502,
      Carve 383, cljfmt 53/53, exclude-with-reason proposal written).
- [x] 3. Baseline gate before formatting: bb boot:jvm GREEN at the
      untouched tree — boot line "595 stock / 181 host / 66 natives /
      69 skipped / 0 errors", battery 221/221, libraries 62/62 across
      12 clojure.* libraries, exit 0 (boot-baseline.log).
- [ ] 4. Format pass over own sources, own commit; gate again.
- [ ] 5. Fix campaign by rule class, pipeline edits, gate per batch.
- [ ] 6. Gates on: bb lint exit 0 (5 legs incl. dprint), pre-commit
      hook, fmt twice no-op.
- [ ] 7. RETRO.md, push, final report.

## Log

- Nov 22 ~18:20 ET — lane start. Templates installed; bb tasks merged
  (first merge landed the tasks at bb.edn root level — caught by
  asking bb itself where the keys live, fixed brace placement,
  re-verified: root keys = (:tasks), all seven fleet tasks listed).
- Cache generation over the classpath launched (background, cache-gen.log).
- Nov 22 ~18:25 ET — box-wide /home/hatch writes hit EDQUOT ("Disk
  quota exceeded") for ~10 min; the step-1 commit failed twice on it.
  Filesystem itself had 27G free; the quota cleared on its own by
  ~18:33 ET. No lane state lost (working tree intact).
- Nov 22 ~18:33 ET — coordinator steer applied: fleet template
  cljfmt.edn corrected (`:sort-ns-references? false` — sorting
  :require lists reorders namespace load order, semantics not
  formatting; proven by the media lane's form-tree diff). Re-copied
  into this repo, byte-identical to the corrected template.
  Media RETRO items 3–5 read: the kondo teaching (:lint-as
  cohost.pool/deftask + analyze-call hook for jolt.ffi/defcfn) is
  adopted HERE only where the same forms re-derive — grep at this
  pin: no jolt.ffi/defcfn anywhere in src, and no (deftask …) call
  sites outside pool.cljc's own defmacro. The census's
  unresolved-symbol breakdown decides which teaching entries this
  config actually needs; each adopted entry lands as a named
  exception in the config header, narcis-style.
