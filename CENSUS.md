# CENSUS — cohost.cljc, syntax-formalization Phase 2

Measured Nov 22 2026 at branch base origin main
`1db42b213585129c3f4384a30f0fa0bcfffa24ae`, BEFORE any fixing, with
the fleet templates v2 installed (step 1). Raw outputs: census-raw/.

Toolchain at the pin: clj-kondo 2026.10.26 · cljfmt 0.16.6 ·
Splint 1.25.0 · Carve 0.3.5 (bbin) · babashka 1.13.230 ·
CLJ_CONFIG pinned to /home/hatch/m2repo. Kondo cache generated over
the real classpath first (src + resources + ../eve/src + clojure
1.12.0 jars), so dependency resolution is real, not noise.

## Buckets

| Bucket | Paths | Files | Role |
|---|---|---|---|
| own | `src/` | 52 clj-family (kondo reads 55 incl. .cljd/.clje) | the campaign: full no-cheats fix to zero |
| vendored | `resources/cohost/clojure-1.12.0/` | 53 | stock Clojure 1.12.0, byte-comparable to upstream — proposal below |
| dev (info) | `dev/` | 165–170 | bb load-file tooling; counted, proposal below, NOT in this gate |

src/ also carries host files in languages no checker reads
(.cljd/.clje are read by kondo 2026; .jank/.lpy/.lg/.phel/.php/
.glj/.js are not) — the same coverage-gap shape as Phase 1's .glj.

## Own sources (src/) — the baseline the campaign is audited against

### clj-kondo: 214 findings — 80 errors · 123 warnings · 11 info

| rule | level | n |
|---|---|---|
| unresolved-symbol | error | 61 |
| invalid-arity | error | 15 |
| syntax | error | 2 |
| private-call | error | 1 |
| type-mismatch | error | 1 |
| unused-binding | warning | 48 |
| redefined-var | warning | 21 |
| unused-value | warning | 14 |
| unused-private-var | warning | 11 |
| unresolved-namespace | warning | 8 |
| constant-condition | warning | 5 |
| redundant-let | warning | 4 |
| locking-suspicious-lock | warning | 4 |
| redundant-declare | warning | 2 |
| redundant-do | warning | 2 |
| loop-without-recur | warning | 2 |
| unresolved-var | warning | 1 |
| unused-namespace | warning | 1 |
| unresolved-excluded-var | info | 3 |
| redundant-primitive-coercion | info | 3 |
| unused-excluded-var | info | 2 |
| redundant-nested-call | info | 2 |
| redundant-str-call | info | 1 |

Worst files: memory_eve.clj 57 · shim.cljc 39 · io.cljc 31 ·
aot.cljc 15 · hosts/clojuredart.cljd 10 · hosts/squint.cljs 9.

Shape of the errors (read from the findings, not assumed): NOT the
Phase 1 Eve-macro pattern. Clusters: memory_eve.clj's Eve-deftype
bodies (field/method names + arity shapes kondo's stock deftype
model half-sees); .cljd Dart host names (Stopwatch, DateTime.now —
unresolvable by design); jolt.clj's jolt-core names (make-lazy-seq,
atom?, defmethod-setup — they exist in jolt's core, not clojure.core);
io.cljc dual-pass phantoms (media RETRO item 5's shape); a private
call into eve.digest from atom_eve_file.cljd; one type-mismatch in
shim.cljc (nil called as a function — verdict owed per site).
Teaching entries are adopted only where a class re-derives here
(coordinator steer, Nov 22): grep at this pin finds no
jolt.ffi/defcfn and no (deftask …) call sites in src, so the media
lane's two teachings are NOT copied blind; what this tree needs is
decided per class during the campaign and named in the config header.

### Splint: 1,546 warnings over 52 files

| rule | n | rule | n |
|---|---|---|---|
| style/def-fn | 391 | lint/let-if | 8 |
| lint/prefer-method-values | 329 | style/redundant-nested-call | 5 |
| naming/lisp-case | 326 | lint/redundant-call | 5 |
| lint/fn-wrapper | 154 | lint/let-when | 5 |
| style/prefer-clj-math | 57 | style/redundant-let | 4 |
| lint/identical-branches | 57 | style/prefer-condp | 4 |
| style/apply-str | 55 | naming/conventional-aliases | 4 |
| style/prefer-clj-string | 43 | lint/locking-object | 4 |
| lint/catch-throwable | 29 | style/minus-one | 3 |
| style/tostring | 10 | style/filter-vec-filterv | 3 |
| style/not-some-pred | 10 | lint/assoc-fn | 3 |
| style/apply-str-interpose | 9 | plus 13 rules at 1–2 each | 21 |
| lint/into-literal | 9 | | |

The three biggest classes are interop-shaped, and each gets a
verdict before any batch runs: naming/lisp-case (the java shim
layer's names mirror host APIs by design), lint/prefer-method-values
(`Class/.member` must parse on EVERY host cohost boots — jolt,
cljw, lg, phel, cljrs — not just the JVM; Phase 1 proved jolt
accepts it, the others are unproven), style/def-fn (the shim's
`(def x (fn …))` shape is how multimethod-style dispatch tables
are built here). Verdicts land in STATUS.md per class; a class
that cannot be fixed without breaking portability becomes a
named exception proposal to the coordinator, never a silent disable.

### Carve: 962 vars reported

Shape: cohost's src is a shim/adapter LIBRARY. Its public vars are
reached by name — the booted stock core calls cohost.shim vars,
host adapters are loaded by name, battery rows name vars as data.
Carve (analyzing src/ alone, with its own bundled kondo 2023.01.20
— media RETRO item 7) cannot see those uses. Triage is per
namespace during the campaign: genuinely dead vars are deleted;
name-reached entry points go to `.carve/ignore` with the reaching
mechanism written as the reason. (Carve also printed a kondo
type-check ClassCastException warning during analysis — its stale
pod, recorded here, not a finding about this tree.)

### cljfmt: 52 of 52 files formatted incorrectly

The tree has never been formatted with the fleet rules. The format
pass is step 4, its own commit, AFTER the baseline gate (step 3).

## Vendored stock tree (resources/cohost/clojure-1.12.0/) — counted separately

### clj-kondo: 540 findings — 73 errors · 457 warnings · 10 info

Top rules: unused-binding 216 · missing-else-branch 95 ·
unresolved-symbol 36 (error) · not-a-function 34 (error) ·
non-arg-vec-return-type-hint 32 · unused-private-var 25 ·
unused-import 19 · cond-else 17 · uninitialized-var 15 ·
redefined-var 8 · redundant-let 8 · redundant-do 6 · rest ≤ 5 each.

### Splint: 2,502 warnings over 53 files

Top rules: lint/prefer-method-values 1,705 · style/prefer-clj-math
92 · lint/if-else-nil 90 · style/new-object 83 ·
lint/misplaced-type-hint 70 · style/prefer-clj-string 54 ·
style/tostring 32 · style/def-fn 30 · style/apply-str 29 ·
lint/catch-throwable 23 · plus 47 more rules.

### Carve: 383 vars · cljfmt: 53 of 53 files formatted incorrectly

## THE VENDORED-TREE PROPOSAL (for the coordinator to decide)

**Proposal: EXCLUDE-WITH-REASON.** The vendored stock tree is
excluded from every leg of the gate, with the reason written in
three places: this census, the bb.edn fleet-task comment (already
there), and — on the coordinator's approval — a named-exception
header block in this repo's `.clj-kondo/config.edn` (the sync
law's provided shape for a repo-local deviation, as narcis's
`:lint-as` entry). The exclusion is enforced by the gate's paths
(the bb tasks lint `src/` only); the kondo CACHE still analyzes
the stock tree as a dependency, which is resolution, not a gate.

Reasons, in so many words:

1. **The tree's byte-identity to upstream Clojure 1.12.0 is a
   load-bearing property, not an accident.** cohost's whole claim
   is that it boots STOCK core.clj; the boot report's file counts
   and the upstream diff are the evidence. Any lint fix — even a
   pure cljfmt pass over 53 files — destroys byte-comparability
   and with it the ability to re-sync against upstream or prove
   "stock" by diff.
2. **The findings are upstream's own idiom.** 1,705 of the Splint
   warnings are one rule (prefer-method-values) against code
   written before the rule existed; the kondo errors are the
   known shapes of core.clj's bootstrap (uninitialized vars,
   not-a-function on bootstrap forward references). Fixing them
   means forking Clojure, permanently.
3. **The alternative was considered and rejected:** lint-and-fix
   would cost a multi-thousand-finding campaign to produce a
   private dialect of Clojure 1.12.0 that no upstream security or
   bug fix could ever be diffed into again. Nobody should want
   that trade, and it is not this lane's to make silently.

What exclusion does NOT mean: the tree is still counted (this
census, re-runnable any time), still byte-diffed against upstream
by the repo's existing practice, and still fully analyzed by the
kondo cache for resolution of cohost's own code. If upstream
publishes a fixed 1.12.x, the tree is replaced wholesale, not
patched.

## dev/ (informational — bb load-file tooling, 165–170 files)

kondo 716 (146E · 561W · 9I — top: duplicate-require 296,
redefined-var 216, invalid-arity 72, type-mismatch 46) · Splint
369 · Carve 27 · cljfmt 127 files unformatted.

Proposal: dev/ gets its own adoption pass AFTER src/ is green.
These files run inside bb's :init context (vars like git-rev and
jank-bin are defined by bb.edn, load-file chains share scope), so
the raw counts overstate real defects by design; the pass needs a
bb-aware config, not this gate's. Recorded here so the exclusion
is a decision on paper, not silence.
