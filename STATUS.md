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
- Nov 22 evening — census committed (494bc78); baseline gate green
  (85aaeb7); format pass committed with token-stream proof
  (1bd088d) and re-gated green (0cf05b5).
- Nov 22–23 — a box-wide EDQUOT ("Disk quota exceeded") episode
  blocked ALL writes under /home/hatch for ~40 min (filesystem had
  27G free; the quota is the overlay's, consumed outside this
  lane; it cleared on its own, as an earlier ~10-min episode did).
  Work continued in a /tmp scratch copy of the clone; verified
  results were synced back file-by-file when the window reopened.
- **K1 (kondo small classes) DONE, gate green.** redundant-let ×4
  (mechanical merge of nested single-body lets — lane/merge_let.py,
  dry-run-verified per site; comments preserved), redundant-do ×2,
  redundant-declare ×2 (the duplicate pair in boot_cljs.clj; the
  load-bearing pair at line 262 stays), redundant-nested-call ×2
  (io.cljc str flattening), redundant-primitive-coercion ×3
  (pool's runtime-alive? verified to always return a strict
  boolean at its Eve definition before removing the coercion),
  unused-namespace ×1 (memory_eve's eve.mem require made bare —
  the namespace is loaded for effect: eve.mem/value+sio->eve-bytes
  is reached by requiring-resolve). Recount: kondo 214 → 200,
  all six classes zero. Boot gate on the batch tree: signature
  identical (595/181/66/69/0, battery 221/221, libraries 62/62,
  exit 0).
- **Phantom banked:** build.clj:332 redundant-str-call — kondo
  claims `(str work)` is redundant, but `work` is bound to
  `(.getCanonicalFile (io/file ...))`, a java.io.File; the str is
  the conversion. Code unchanged; the site gets a reasoned ignore
  in the exceptions batch (info level; does not block exit 0).

## REAL DEFECT FOUND + FIXED — shim http-member duplicate defs (Nov 23, ~12:55 AM ET)
Provenance: tree = this branch at the formatted tree (base origin main
1db42b2); tools = clj-kondo 2026.10.26, JVM boot (clojure 1.12.0,
bb 1.13.230); repro = fabricated layer values driven through the
booted cohost.shim (probe-members.sh/.log in this clone).

`cohost.shim/http-member` returns a NEW dispatch fn per call and a
second `def` of the same name REPLACES the var — three member vars
were defined twice with DISJOINT kind tables, so the first table's
kinds silently lost the member:
- `-getPort`: URI/URL table (http-locator) shadowed by the socket
  table. PROVEN BROKEN pre-fix: -getPort on {:cohost/kind
  :cohost.http/uri :port 8080} and on a :cohost.http/url value both
  threw "cohost.shim: no shim for [:method getPort]"; control
  (-getScheme, single def) answered "https".
- `-getOutputStream`: connection table shadowed by the socket table
  (same shape, found by reading; fixed identically).
- `-getContextClassLoader`: the classloader standin's def shadowed
  by the thread table (same shape; fixed identically).
Fix (this batch): each pair merged into ONE def whose table carries
both sections' kinds — the shape -getInputStream/-getLocalPort
already used in the same file. Post-fix probe: :uri-port 8080,
:url-port 9090, control "https". Boot gate on the batch tree: GREEN — boot-k2.log carries the
baseline-identical signature (595 stock / 181 host / 66 natives /
69 skipped / 0 errors), battery 221/221, libraries 62/62,
BOOT-K2-EXIT=0.

Also in this batch: the dead first URLEncoder/URLDecoder
implementations (shadowed by the live delegating defs) deleted with
their orphaned helpers (bytes-of-unsigned, hex-val def1,
url-safe-char?, hex-digits); the live codec block moved ABOVE its
first use (-getQuery) since deleting the dead def exposed a
use-before-def the dead def had been masking — the boot gate is the
proof the file still loads. NOTE for cohost owners (not changed
here): the live codec IGNORES the charset argument (always UTF-8,
no validation), contradicting the deleted section comment's claim
that an unknown charset throws UnsupportedEncodingException "at
this door"; restoring validation is a behavior decision for the
owners, not a lint fix. region.cljc ns restructured (conditional
require clause) and aot.cljc lock test hoisted into try-mkdir!
(both were reader-conditional form-count phantoms in passes that
never load the files).

Kondo after this batch: 186 total (79E/101W/6I) — redefined-var
shim sites zero, unused-value 14 -> 8, loop-without-recur 2 -> 1.

## K3 + a second REAL DEFECT (Nov 23, ~1:35 AM ET)
- defwrapper analyze-call hook landed (.clj-kondo/hooks/cohost/
  memory_eve.clj, registered as repo teaching in config.edn):
  memory_eve.clj 56 findings -> 0. Total kondo 128.
- REAL DEFECT #2: cohost.build DID NOT COMPILE at this pin —
  (require 'cohost.build) threw "Too many arguments to def"
  (build.clj:86): the run-js JS string held BARE double quotes
  around -m, ending the Clojure string early. Kondo's
  invalid-arity + unresolved-symbol were the only signal; the
  boot gate never loads cohost.build. Escaped the two quotes
  (emitted JS byte-identical); the file now loads and lints
  clean. Provenance: same tree/tools as defect #1; repro was the
  require itself, before (throws) and after (loads).
- K3 boot gate GREEN (BOOT-K3-EXIT=0, baseline signature).

## KONDO CAMPAIGN COMPLETE (Nov 23, ~3:05 AM ET)
clj-kondo over src: 0 errors / 0 warnings / 0 infos (census was
214: 80E/123W/11I). Batches K1-K8, every batch boot-gated green at
the baseline signature (595/181/66/69/0, battery 221/221,
libraries 62/62). Real defects found by the campaign so far:
#1 shim http-member duplicate defs (-getPort/-getOutputStream/
-getContextClassLoader kind tables shadowed; probe-proven),
#2 cohost.build uncompilable (bare quotes in the run-js string),
#3 squint qualify 4-arity call, #4 squint fn-walk nested?' never
passed down. Dead private vars deleted with zero repo-wide uses
(aot/rename, atom/unsupported-type-error?, squint/entry,
squint/chunk-dir, io/append-buf-in-place!, shim/parse-long-strict).

Kondo mechanism banked (minimal repro): a def whose VALUE is a
reader conditional, used only inside reader-conditional arms,
still draws unused-private-var — kondo 2026.10.26 does not credit
the conditional use (repro: 4-line .cljc, def #?(:clj (System/..)
:default "y") + use in a #?(:clj ..) arm -> flagged). io's
system-newline/out-save/fallback-print-method carry ignores
citing this. Also banked: :config-in does not govern
redefined-var (ns-analysis emission); stacked #_ ignores do not
compose — combine linters in ONE discard.

REMAINING: Splint (1,546 own at census), Carve (962), step-6
gates (dprint config+docs pass, .githooks/pre-commit, kondo cache
refresh commit, fmt no-op proof), RETRO.md, push.
