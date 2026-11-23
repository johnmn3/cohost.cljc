# RETRO — cohost.cljc, syntax formalization Phase 2

Campaign result: kondo 214 -> 0/0/0, Splint 1,546 -> 0, Carve
962 -> exit 0 (956 kept with reasons, 0 dead), cljfmt 52/52
unformatted -> clean and idempotent, dprint clean, `bb lint`
exit 0, boot gate at the tip identical to the pre-campaign
baseline (595/181/66/69/0, battery 221/221, libraries 62/62).
Every batch was boot-gated before commit. Lessons below are
ordered by how much they should change the fleet templates or
the next lane's brief.

## Template / fleet proposals

1. **Mirror files need a header-law exception shape, not 326
   per-site reasons.** cohost.shim (719 carve vars, 326 lisp-case
   findings) and cohost.js-shim exist to mirror external name
   spaces (clojure.lang members as rewritten by cohost.rewrite;
   JavaScript globals). The names ARE the contract, so the honest
   annotation is one law paragraph in the file header plus a bare
   `#_{:splint/disable [naming/lisp-case]}` at each def citing
   it. Fleet proposal: the templates should bless this shape for
   declared mirror files — per-site prose at 326 sites adds noise,
   not information. Same shape fit the def-fn exceptions (51
   defs whose value is a reader conditional, a let-computed fn,
   or a self-named recursion: one category note, repeated).
2. **Carve on a library repo is a keep-list exercise, and that
   is the correct outcome.** 956 findings, zero dead vars: a
   shim/adapter library's surface is reached by name (rewrite
   tables, boot installer, adapter registries, downstream
   repos). The deliverable is the grouped keep-list with the
   reaching mechanism per group. Future library lanes should
   budget for triage tooling (privacy + repo-wide mention scan),
   not deletions. The two private findings were the known
   reader-conditional false positive (:jank arms).
3. **The vendored-tree proposal (in CENSUS.md) stands:
   exclude-with-reason.** Byte-identity to upstream Clojure
   1.12.0 is load-bearing evidence for cohost's "runs STOCK
   core" claim; the vendored findings (kondo 540, Splint 2,502,
   Carve 383) are upstream's own idiom. Lint-and-fix was
   considered and rejected in writing there. Coordinator to
   decide; the gate covers src/ meanwhile.

## Tool defects proven (banked with repros in STATUS.md)

4. **Splint prefer-clj-math invents var names.** It maps
   `Math/toRadians` to `clojure.math/toRadians`; the real var is
   `to-radians`. Sixteen names in hosts/java.clj were
   hand-corrected against the authoritative list extracted from
   the kondo cache's clojure.math transit. Any lane applying
   prefer-clj-math must verify every suggested name exists.
5. **Splint fn-wrapper suggests bare constructor symbols**
   (`clojure.lang.LazySeq.`) that do not compile as values —
   the first S1 gate failed on exactly this. Method values
   (`Class/.method`) DO compile and run on the plain clojure
   route (proven). The transform engine now refuses constructor
   heads and method heads.
6. **Carve's bundled kondo (2023.01.20) crashes on a
   `#_{:clj-kondo/ignore}` discard used as a map value**
   (transit writer: "Not supported: class clojure.core$vec"),
   bisected form-by-form to that shape; the same discard in a
   fn body is fine. Three discards in jolt's adapter map were
   relocated into their fn bodies. Any repo whose adapter maps
   carry discards will hit this; the fix is relocation, not
   removal.
7. **Stacked `#_{.../disable}` discards do not compose in
   EITHER linter.** Kondo (K8) and Splint (S7) both swallow the
   second of two adjacent discards. Dual-diagnosed forms need
   ONE combined discard. This bit three times; it belongs in
   the fleet README.
8. **`:config-in` does not govern redefined-var** in kondo
   2026.10.26 (it is emitted from ns analysis); a global
   `:linters` entry works, config-in does not. Banked in K4.

## Method lessons

9. **Bulk annotation from stale diagnostics mis-places
   annotations.** The first S7 pass annotated from diags taken
   before an uncommitted header edit; 21 landed on wrong forms.
   The audit that caught it: parse the file, and for every
   top-level form preceded by a discard, check the def it guards
   actually has the diagnosed shape. Reuse this audit after any
   bulk annotation, and always annotate from FRESH diags.
10. **prefer-method-values triage is the expensive rule.**
    330 findings; 129 rewritten where the receiver class is
    provable from local evidence (constructor targets, ^Class
    hints, literal/(str ...) bindings), 202 honestly unprovable
    (host/polyglot/realm/Eve values). Splint prints a literal
    CLASS placeholder, so every site is a small investigation.
    One transform ate a quote prefix on a data template
    (memory_eve defwrapper :ifn) — never transform a node with
    a non-empty reader prefix unless the transform accounts
    for it; kondo caught that one, the gate would have too.
11. **The campaign's real catches were semantic, not style:**
    shim's http-member duplicate defs (disjoint kind tables,
    second def shadowing the first — probe-proven), cohost.build
    uncompilable since its run-js string carried bare quotes
    (the boot gate never loads it; kondo was the only signal),
    squint's qualify arity mismatch, and squint's fn-walk
    passing the outer nested? flag down instead of the inner
    one. A lint campaign on a substrate repo is a bug hunt;
    budget for probe-proving each suspected defect before
    fixing (phantom-defect discipline paid off repeatedly —
    e.g. the K8 unused-private-var findings that were a proven
    kondo false positive on conditional defs, repro banked).
12. **Splint JSON recounts need the pretty stream.**
    `bb splint -o json` wrote an empty file and the parallel
    text output interleaves/undercounts; the canonical recount
    is `-o json-pretty --no-parallel` parsed as a stream of
    JSON objects. Worth one line in the fleet README.
