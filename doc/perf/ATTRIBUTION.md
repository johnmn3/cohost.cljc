# Attribution — where cohost-on-JVM beats (and loses to) JVM Clojure, and what else is on the table

Lane: cohost-jvm-speed (John's order, Nov 8 2026: "Find all opportunities to
make Clojure go faster on JVM through cohost than on JVM stock.")
Ground truth: the 2026-11-08 perf refresh, `doc/PERF.md` §"Refresh —
2026-11-08" and its raw data `doc/perf/refresh-2026-11-08/bench-clj-j.edn`
(cohost @ main a956f215, JVM seat, **hot window**: 5/5 job slots busy,
load ~5–6; the refresh itself warns the absolutes are upper bounds and the
ratios are the comparable read).

This document is banked incrementally: §1–§3 the catalog (static, from the
pipeline sources), §4 the calm re-measurement, §5 the per-rewrite
attribution, §6 the new-opportunity hunt, §7 the proposed landing set.

## 0. The headline, read against its own raw data

Refresh geomeans (stock core booted through cohost, against JVM Clojure's
own core): **×1.1 overall**; the cells that prompted this lane:

| case | host median | stock median | ratio | host min | stock min |
|---|---:|---:|---:|---:|---:|
| a defn called in a loop | 1.818 | 0.341 | ×0.19 | 0.276 | 0.256 |
| partition 3 | 24.067 | 15.075 | ×0.63 | 14.889 | 12.618 |
| clojure.string/join | 1.291 | 1.049 | ×0.81 | 1.025 | 0.646 |
| a ^long fn whose loop compares | 0.108 | 0.297 | ×2.75 | 0.093 | 0.266 |
| multimethod call | 2.582 | 4.715 | ×1.83 | 2.025 | 4.592 |
| pr-str of small maps | 3.039 | 6.540 | ×2.15 | 2.937 | 4.761 |
| loop/recur arithmetic | 0.101 | 0.103 | ×1.02 | 0.089 | 0.090 |

Three facts discipline everything below:

1. **The case code compiles identically on both sides.** The bench's stock
   side evaluates each case through `boot/eval-str` — rewritten by the
   codemod, then compiled by the host's own `clojure.lang.Compiler`, the
   same compiler that compiles the host side (see §2, C9–C11: every
   call-shape lever the codemod owns is inert on the JVM). Whatever
   explains a cell lives in the *core fns the case calls*, not in the
   case's own bytecode.
2. **Medians and mins disagree in both directions.** fn-call's mins are at
   parity (0.276 vs 0.256) while its medians are ×0.19 apart; pr-str's two
   sides call the *same function object* (pr-str is a policy :host take,
   C1) yet its medians sit ×2.15 apart. A same-fn cell cannot have a
   codegen gap; its spread is the window's noise floor, and it is large.
3. **Two cells show min-gaps in the stock-slower direction** (hinted-loop
   ×2.9 at min, multimethod ×2.3 at min). Mins are the steadier statistic
   here; if anything in the table is a real codegen effect, it is likelier
   these losses than the ×0.19 win. Both get attribution benches (§5).

## 1. What the pipeline does on the JVM, mechanically

Booting stock core through cohost on the JVM (`src/cohost/boot.cljc`,
`policy.cljc`, `rewrite.cljc`, `adapter.cljc`, `hosts/jvm.clj`,
`hosts/java.clj`):

- core.clj is read form by form. `policy/decide` dispositions each
  top-level form: `:host` (refer the host's own var — it IS clojure.core's),
  `:skip` (JVM machinery; the type/protocol/multimethod companions are
  taken from the host at the end of the boot), `:replace` (a handful of
  shim-based definitions), or `:eval` (boot the stock definition).
- An `:eval` form is rewritten by the codemod (`rw/rewrite`) and evaluated
  by the real `clojure.lang.Compiler`, one form at a time. Stock macros
  defined along the way are wrapped (`:macro-wrap-style :root`), so their
  expansions pass through the same codemod before compiling.
- The twelve libraries load through the same loader: their forms evaluate
  as written (`policy/decide-lib`; `lib-overrides` is empty).

So the booted core is: most of core.clj compiled from source by the stock
compiler, a named set of names referred from the jar's own core, the
type machinery taken from the host, and interop inside the compiled
bodies routed through `cohost.shim` where the shim models it.

## 2. Catalog — every rewrite / substitution, and its JVM state

### Policy level (boot.cljc + policy.cljc)

**C1. `:host` takes** — `policy/host-names`: printing/reading/IO, the
namespace/loader machinery, regex (`re-pattern`; the `re-*` fns boot over
the shim's Matcher), the dynamic-var surface, concurrency facades
(future/agent/ref machinery), `case`, `locking`, big-number constructors,
`eduction`, `iteration`, tap. On the JVM the referred var is
clojure.core's own: **identical by construction; cannot produce a win or
a loss.** Cases touched: pr-str, format (over host `format`? — no:
`format` is in host-names; the case's gap is therefore window noise by
this catalog), regex (partially: `re-seq` boots, over the shim Matcher).

**C2. `:skip` + companion takes** — `proxy`/gen-class/structs machinery
skipped; `deftype`/`defrecord`/`defprotocol`/`reify`/`extend*`,
`satisfies?`, `record?`, `vector-of`, `print-method` etc. are
`companion-names`, taken from the host at the end of the boot (two,
`namespace-munge` and `hash-combine`, boot from `core_deftype.clj`).
**Parity by construction** for the protocol case: the case's protocol and
record are compiled by the case itself, against the host's machinery on
both sides.

**C3. `:replace` definitions** — `cast`, `bases`, `supers`, `realized?`,
`*clojure-version*` (policy/replacements): shim-predicate versions of
four fns. `realized?` answers through the shim's `-isRealized` instead of
stock's cast-to-IPending; none of the 30 bench cases calls these hot.
Boot-time only, for perf purposes.

**C4. Chunk fns from the host** — the JVM adapter supplies `:chunks`, so
`chunk-*`/`chunked-seq?` join `host-names` (policy/lists, `chunks?`):
the chunked branches inside booted `map`/`filter`/`concat`/`for`/`doseq`
call the host's own chunk fns, and the adapter's `:chunk-reduce` keeps
`IChunk.reduce`'s contract for `reduce`. **Parity by construction**;
touches the lazy-pipeline, transduce, into-vector, set-build, partition
cases' insides.

### Codemod level (rewrite.cljc, JVM ctx from boot/make-ctx)

**C5. Host aliases** (`rw-static` + boot/host-aliases): a call the source
writes as a clojure.lang static — `(. clojure.lang.Numbers (add x y))` —
is emitted as the host core's own fn symbol when the shim static's value
is that fn, "so the host's compiler sees (and may inline) its own +,
first, get". **JVM carve-out:** a host fn carrying `:inline` metadata is
*not* aliased on the JVM (its expansion would read `*unchecked-math*`
where the static call is always checked) — so `Numbers-add` and kin are
**not** aliased, and compile to `(cohost.shim/Numbers-add x y)`, whose
root on the JVM is clojure.core's `+` itself (`shim.cljc`:
`(def Numbers-add #?(:jank … :default +))`. Non-`:inline` statics (e.g.
`RT-first` class) do alias. Net JVM shape: booted-core internal static
calls cost at most one Var hop over the jar core's direct static call —
a small *loss* mechanism inside booted fns, not a win. The exact aliased
set on the JVM is enumerable from a boot; §5 measures the aggregate by
toggling `:host-aliases?`.

**C6. Shim statics / members / ctors** (`rw-static`, `rw-method`,
`rw-new`, `rw-dot`): interop forms the shim models become
`cohost.shim/<Class>-<member>` calls (with prebuilt `instance?` dispatchers
and `cast$` checkcasts installed per class); interop the shim lacks is
left to the host on the JVM (`:interop-passthrough? true`). Inside booted
core bodies this is the standard path for `(.swap atom …)`,
`(. RT …)`-style calls not covered by C5.

**C7. Shim MultiFn with a one-entry cache** — JVM adapter
`:multimethod-cache? true`: booted `defmulti` builds the shim's MultiFn,
not clojure.lang.MultiFn. Banked precedent (adapter comment, X3-1, on
max/main): the cache moved the multimethod case 8.49 → 7.24 ms against
the *uncached* shim MultiFn, battery unchanged. Against the host's real
MultiFn the refresh shows the stock side *slower* (median ×1.83, min
×2.3) — attribution target T1 (§5): cache on/off × shim/real MultiFn,
calm.

**C8. Natives** (hosts/java.clj `:natives`, merged by hosts/jvm.clj):
where core or a library names a shim leaf for `into`, `reverse`,
`replace`, `comp`, `partial`, `juxt`, `complement`, `fnil`, `every-pred`,
`some-fn`, `swap!`, `reset!`, `compare-and-set!`, `swap-vals!`,
`reset-vals!`, `last`, `butlast`, `max-key`, `min-key`, `repeatedly`, and
most of `clojure.string`'s fns, the value installed is the host's own fn.
**Parity by construction** where it fires; it fires inside booted bodies,
not at case level (the case's own `swap!` resolves to the booted core's
`swap!`, whose body reaches the host Atom through C6).

**C9. Macro wrapping** — stock macros are wrapped at the var root as the
boot defines them; every expansion they produce (in the boot, in the
libraries, in user code like the bench cases) is rewritten by the codemod
before the host compiler sees it. On the JVM the rewrite of an expansion
is C5/C6 plumbing inside it; the expansion's *shape* is stock's. This is
why §0.1 holds.

**C10. `:inline` left to the compiler** — JVM `:rewrite-inline? false`,
`:inline-meta :keep`: the codemod does not expand `:inline` fns at
rewrite time (`inline-expansion` is inert: ctx `:inline-fn` is nil), and
booted vars keep their `:inline` metadata, so the stock compiler inlines
`+`, `inc`, `<`, `get`, … at stock-side call sites exactly as it does at
host-side ones. **Parity mechanism** — and a *fragility*: anything that
silently dropped `:inline` meta in the boot would tax every stock-side
loop (the hinted-loop min-gap, §0.3, is the shape that would take; T4 in
§5 tests the sensitivity by stripping deliberately).

**C11. Static-type / numeric inference family** — `rw-fn*`'s guarded
typed twins, `infer-num`, `numeric-params`, unit-kinds (U6-7/U6-9):
**inert on the JVM.** `infer-here?` requires the adapter's
`:static-types`, which is nil for the JVM (it is a jank/C++ lever, opt-in
per run through `COHOST_STATIC_TYPES`); it also never applies inside the
booted core or a clojure.* library. `native-hints` is nil, so the
checked-native / native-int-alias machinery is inert too.

**C12. The alias/expansion levers the JVM leaves off** — adapter
`:core-aliases #{}` (call heads of booted fns emitted as the host's fn),
`:core-value-aliases {}` (the same for fns in *value* position —
`(map inc xs)`), `:core-expansions {}` (per-fn call expansions),
`:direct-var-calls nil` (same-namespace var calls emitted as the host's
direct call form). All four are empty/nil on the JVM (defaults in
adapter.cljc; java.clj/jvm.clj do not set them). They exist for hosts
whose compilers inline the host's own ops (the docs cite jank inlining
`clojure.core/not`). **These are the JVM's untried levers** — §6 takes
them up as opportunities, with their documented prices (a with-redefs or
alter-var-root of the booted var is not seen by an aliased call/value;
an aliased value is not `identical?` to the booted var's fn).

**C13. Def tags carried** — ctx `:def-tag? true` for `:clj`: the codemod
preserves the source's own type hints through `def` into compilation
(the jar's core was AOT-compiled from the same hints). Parity mechanism.

### Shim level (shim.cljc)

**C14. Shim values that are the host's own** — on the JVM a large share
of `cohost.shim`'s statics resolve to clojure.lang's own fns/classes
((C5)'s Numbers-add is the type specimen), and `host/*raw-macro-output*`
plumbing aside, a shim call on the JVM is a Var hop onto the host fn.
Where the shim instead carries its *own* implementation (MultiFn, C7;
the Matcher under the booted `re-*` fns, C1; the `-isRealized` predicate,
C3), that implementation is a genuine code difference and a legitimate
attribution target.

## 3. What the catalog already settles

- No JVM-active rewrite changes the *shape* of a bench case's own code.
  The refresh's per-cell wins and losses are therefore either (a) window
  noise — proven possible at ×2 by the pr-str same-fn cell — or (b) real
  differences inside the booted core fns the cases call, of which the
  catalog names exactly three plausible carriers: **C5/C6** (Var hops
  where the jar core calls statics directly — a loss mechanism),
  **C7** (shim MultiFn — measured loss at min), and **C10's fragility**
  (lost `:inline` anywhere in the boot would be a broad loss; the
  hinted-loop cell is its canary).
- The ×0.19 fn-call cell has **no catalog carrier**: its callees are
  `bench-inc` (the case's own, identically compiled), `inc` and `+`
  (inlined by the compiler on both sides, C10). The catalog predicts
  parity; its mins agree. §4 tests that prediction calm.

## 4. Calm re-measurement — protocol, and calm1 results (2026-11-08)

Driver `dev/perf/jvm_speed.clj`, runner `dev/perf/jvm_speed_run.sh`:
one side per process (no shared JIT/GC window), ABBA order, every
process started only at load1 < 3.0 with the load stamped per case;
1500 ms warm-up per case, then 9 reps of a batch calibrated to ≥ 200 ms;
the full per-rep distribution is reported, and a cell is called real
only if the sides' rep distributions separate. Cases: the three headline
wins (fn-call, partition, string-join), the two min-gap losses
(hinted-loop, multimethod), two controls (loop-arith — pure loop, no
callees; pr-str — same fn both sides), and transduce. Value equality is
checked per case from the recorded `:value`s.

**Side names, once, unambiguously** (the refresh's table and this lane's
driver share them, and they have already been misread once): side
**"stock"** is the stock core *booted through cohost* — the product;
side **"host"** is the host JVM Clojure's *own* core, plain. Below,
"booted" = stock side, "plain" = host side, and every ratio is
**booted ÷ plain** (> 1: the booted core is slower). Times are ms per
case run (the case's own n), pooled over both processes per side
(18 reps/side). Job `20261108T193500-jobagent-jvmspeed-calm1` on J,
EXIT=0; every process started at load1 2.80–2.99 (the < 3.0 gate held);
values equal on all 8 cases in all 4 processes. Raw:
`/home/john/j/cohost-jvm-speed-results/calm1/{stock,host}-{1,2}.edn`.

| case | booted min | booted med | plain min | plain med | ratio @min | ratio @med | refresh @med | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| fn-call | 0.341 | 0.355 | 0.198 | 0.389 | 1.72 | 0.91 | 0.19 | **retracted — parity** |
| partition 3 | 7.234 | 13.887 | 6.877 | 9.870 | 1.05 | 1.41 | 0.63 | **retracted — unstable case** |
| string/join | 0.739 | 1.172 | 0.716 | 1.280 | 1.03 | 0.92 | 0.81 | **retracted — parity** |
| hinted-loop | 0.065 | 0.097 | 0.069 | 0.074 | 0.94 | 1.31 | 2.75 (min 2.86) | **min-gap retracted** |
| multimethod | 9.261 | 10.837 | 1.561 | 2.510 | 5.93 | 4.32 | 1.83 (min 2.27) | **CONFIRMED — real loss, larger calm** |
| pr-str (control) | 3.401 | 6.846 | 2.863 | 4.191 | 1.19 | 1.63 | 2.15 | control: noise, as designed |
| loop-arith (control) | 0.102 | 0.105 | 0.069 | 0.101 | 1.47 | 1.04 | 0.98 | control: parity |
| transduce | 1.698 | 2.794 | 0.545 | 1.146 | 3.12 | 2.44 | — | **unconfirmed loss candidate** |

Per cell:

- **fn-call ×0.19 — RETRACTED.** Calm medians are at parity (×0.91);
  the booted side's two processes are the tightest in the run
  (0.341–0.375 at batch 640 in both), while the plain side spreads
  0.198–0.508 across its two processes (its low reps ride the
  batch-2560 process). The refresh's ×0.19 was its plain side's hot
  window, not a booted-core property. The catalog's prediction (§3:
  no carrier, parity) is confirmed.
- **partition ×0.63 — RETRACTED.** The direction flips between windows
  (refresh: booted faster at median; calm: ×1.41 slower) and the reps
  overlap in both. Worse, the case is unstable *within* single
  processes — bimodal rep sets on both sides (one booted process:
  7.2–8.8 and 15.1–15.6 in the same 9 reps) — so neither window's
  median is a measurement of code. No claim either way until an
  allocation-controlled instrument exists for this case.
- **string/join ×0.81 — RETRACTED as a gap; parity.** Mins ×1.03,
  medians ×0.92. What C8 predicts (the booted `join` is the host's own
  fn — parity by construction) is what calm measures.
- **hinted-loop min-gap (×2.86 at min) — RETRACTED.** The booted min
  calm is 0.065, at parity with plain's 0.069; the refresh's 0.266
  booted min does not reproduce. A median tendency (×1.31) remains but
  the distributions overlap, one booted process is bimodal, and the
  batch calibration differs across processes (10240 vs 2560) — under
  this section's discipline that is not a real cell. C10's canary does
  not fire. T3 (§5) still runs, as the sensitivity control that prices
  what a lost `:inline` *would* cost.
- **multimethod — CONFIRMED, and larger calm: ×4.32 at median,
  ×5.93 at min, fully separated** (the booted side's fastest rep,
  9.261, is slower than the plain side's slowest, 2.570). This is the
  one cell in the set that survives the protocol, and by catalog
  elimination its carrier is C7: the case's own code compiles
  identically (§0.1), so the only differing component on the path is
  the dispatch machinery — the shim MultiFn the booted `defmulti`
  builds, vs `clojure.lang.MultiFn` on the plain side. T1 (cache off)
  prices how much of the ×4.3 the one-entry cache is already buying
  back; T4 (real MultiFn) prices the absolute remainder (§5).
- **pr-str (control) — behaves as a control must.** Same fn object on
  both sides (C1), yet batch-matched process pairs disagree by ×2.1
  (batch 160: booted 7.18 vs plain 3.42) and ×1.04 (batch 40) in
  opposite proportions to the pooled read. That disagreement *is*
  the calm window's noise floor for this case family, and it is why
  no non-separated cell above is evidence of anything.
- **loop-arith (control) — parity** at median (×1.04), as a pure loop
  with no callees must be; its min spread is tail luck in both
  directions.
- **transduce — UNCONFIRMED loss candidate, ×2.44 at median.** The
  direction (booted slower) is consistent in both ABBA pairings
  (3.22 vs 1.91; 1.77 vs 1.00) but the distributions touch (booted
  min 1.698 < plain max 1.950), so the protocol does not call it
  real yet. It has plausible catalog carriers (C5/C6 Var hops inside
  the booted reduce/transducer machinery; C4 chunk paths). A repeat
  calm run (calm2) is filed to settle it, and T2 (host-aliases off)
  is its attribution toggle.

**The ×1.1 geomean** is not re-derived here (calm1 covers 8 of the
refresh's ~30 cases, and no corrected geomean is quotable from a
subset). What calm1 does to it: of the cells checked, the three
loudest median gaps were hot-window artifacts, the one real cell is
a *loss* the refresh understated (×1.83 → ×4.32), and the controls
sit at parity. The refresh's per-cell medians should be treated as
unproven wherever this section has checked them; the geomean's rough
shape (booted modestly slower overall) is not contradicted, but it
is not evidence either — it is a sum of mostly unmeasured cells.

## 5. Attribution toggles — results (2026-11-08)

- **T1** `:multimethod-cache?` true→false on the JVM adapter (isolates
  C7's cache against the uncached shim MultiFn, calm; X3-1's hot-window
  delta predicts ~15%).
- **T2** `:host-aliases?` true→false (isolates C5's aggregate inside
  booted core bodies; expect ≤ a few % on seq/collection cases, sign
  unknown).
- **T3** `:inline-meta :keep`→`:strip` as a *sensitivity control*: if
  stock-side loops collapse, C10 is load-bearing and the hinted-loop
  gap deserves a meta audit of the boot; if nothing moves, the compiler
  wasn't inlining through those vars anyway and the gap lives elsewhere.
- **T4** multimethod against the host's real MultiFn (policy: take
  `defmulti`'s machinery from the host where possible) — prices C7
  absolutely, not just its cache.

### §5 results — collected 2026-11-08 (successor seat)

Collection note: T1/T2/T3 and calm2 all ran on J on 2026-11-08 (jobs
20261108T210608–T210611, EXIT=0; every process load-gated, load1
2.76–2.97) and sat uncollected ~7 h; the successor seat read the raw
EDNs firsthand. Values equal on every case in every run. calm2, T1 and
T2 ran interleaved in one window (21:17–21:26 UTC) and T3 just after
(21:22–21:49) — comparisons inside that set share a window; calm1
(19:41–19:50 UTC) is the earlier window. Ratios are booted ÷ plain,
pooled over both processes per side (18 reps/side).

**calm2 — transduce CONFIRMED; hinted-loop stays unconfirmed.**
transduce: med ×4.35, min ×3.74, fully separated (booted min 2.027 >
plain max 0.990). With calm1 (med ×2.44, min ×3.12, distributions
touching at the edges), two calm windows put booted transduce at
×2.4–4.4 median / ×3.1–3.7 min. **Verdict: a real loss — the lane's
second confirmed cell.** hinted-loop: calm2's booted side is slow in
both its processes (med 0.320/0.372) where calm1's was at parity
(pooled med 0.097), while the plain side is steady (med 0.073/0.111)
across both windows — the *booted* side flips between windows, not
the window. Under this lane's discipline the cell stays
**unconfirmed**, now with a named shape: boot-to-boot variance of the
treated side on the hinted loop (and T3 bounds what it is not: an
:inline loss costs ×16, not ×3.3). pr-str control behaved: min parity
(×1.00), median ×2.11 — the calm noise floor, as designed.

**T1 — the one-entry cache buys back nothing; on this pattern it
costs.** (cache off; multimethod + pr-str.) Booted absolutes: calm1
cache-on pooled med 10.837 (processes 10.677/10.837); T1 cache-off
pooled med 5.775 (5.168/7.379). Within-window ratios: ×4.32 at median
(on) → ×1.71 (off); ×5.93 → ×3.75 at min. The bench's dispatch is
cyclic — `(mod x 3)` over a 50k reduce — so the (state, hierarchy)
generation cell pays its derefs and identity checks on every call,
while the per-dispatch-value method cache underneath (present in
both arms: mf-method / mf-fast-method) does the actual remembering.
**Reading: the cache is not the ×4.3's explanation and not its
remedy; turning it off recovers roughly half the median gap.** The
remainder — cache-off booted still ×1.6–2.3 per-process at median —
is the shim MultiFn's dispatch machinery itself (a CLJC closure over
a state atom, a find walk) against clojure.lang.MultiFn's, and is
what T4 prices. T1 **contradicts X3-1's bench:jvm read** (cache on:
8.49 → 7.24 ms; the comment in hosts/jvm.clj) taken under a
different instrument; both cannot be the last word, and §7's
legality gate must re-run the cache A/B under bench:jvm before any
default is proposed from either. For attribution the question is
settled: the cache does not explain the confirmed loss.

**T2 — null; C5 exonerated; transduce's carrier narrows.** (aliases
off; same window as calm2.) transduce ×4.28 med / ×4.36 min with
aliases off vs ×4.35 / ×3.74 on: no movement. partition mins at
parity both ways (×1.04 off, ×1.05 on); string-join mins at parity
both ways (×0.94 off, ×1.03 on). **Verdict: C5's aggregate Var-hop
cost is not measurable on any of its three cases; host aliases are
not a loss carrier.** transduce's carrier is therefore inside what
T2 does not touch: the booted reduce/transduce bodies' per-step path
— C6 shim statics inside booted bodies, C4 chunk/reduce plumbing,
and the xform's value-position callees (`inc`, `odd?` reached as
booted fns per element). The deciding instrument is a case
decomposition (identity / map-only / filter-only against the
existing reduce-range and full transduce cases) plus the O1/O3
toggles; the decomposition is filed as job `cohost-jvm-speed-t5`
(patch: dev/perf/t5-transduce-decomp.patch, scratch-clone only).

**T3 — :inline survival is load-bearing (C10 is a first-order
mechanism).** (strip; the sensitivity control.) Booted medians
collapse exactly where C10 predicts: loop-arith ×26.6 (2.796 vs
plain 0.105), hinted-loop ×16.6 (1.725 vs 0.104), fn-call ×2.86
(1.012 vs 0.354). The control's positive is the verdict: **any
booted var that loses its :inline pays this class of tax on every
loop through it**, and the production boot's `:inline-meta :keep`
is doing first-order work. Corollary: production calm numbers for
these cases (medians ~0.1) show no wholesale inline loss in the
real boot — but the per-var audit (O5, §6) is now the cheapest
high-leverage probe in the lane: a static enumeration, and a single
lost var is a pure-loss bug.

**T4 — priced in; patch authored and smoke-verified.** T1 leaves a
×1.6–2.3 median remainder that only the dispatch machinery
explains, so T4 runs as an authored patch, not a sed:
dev/perf/t4-real-multifn.patch adds an adapter capability
`:multifn-ctor` (doc + nil default in adapter.cljc); where a host
provides it, `cohost.shim/new-MultiFn` returns the host's own
multimethod, and the shim's MultiFn members (addMethod /
removeMethod / getMethod / method & prefer tables / preferMethod)
reach it through the existing host-mf? delegation via a JVM
`:multifn-op` (hosts/jvm.clj builds a real clojure.lang.MultiFn).
Hosts without the capability are untouched. Smoke (hatch,
correctness only, loaded box — no numbers): full boot + the twelve
libraries on the treated tree; the bench multimethod value exact
(1250008333); `(instance? clojure.lang.MultiFn …)` true of a booted
defmulti; methods / get-method / prefers correct; remove-method,
re-add and prefer-method live; a defmethod on the host-taken
print-method (an object this boot did not construct) dispatches.
The patched files are byte-identical to the smoke-tested tree
(sha256-verified). Calm measurement filed as job
`cohost-jvm-speed-t4` (multimethod, pr-str control, fn-call as a
boot-sanity cell); this section closes with its number.

## 6. New-opportunity hunt (planned; ranked after §4–§5 land)

Candidate species, from the catalog's own logic — places stock pays for
dynamism a whole-core compile can legally shed, that the JVM pipeline
currently leaves on the table:

- **O1. `:core-value-aliases` for the provably-semantic-identical core
  fns** (`inc`, `dec`, `identity`, `not`, `pos?`, … — each name needs its
  own probe, per the adapter doc's "a probe per name"): value positions
  like `(map inc xs)`, `(filter odd? xs)`, `(reduce + …)`'s fn argument
  currently pass the *booted* var's fn, whose body is the stock source
  compiled — for `inc` that body is a definline'd shell around
  Numbers.inc reached through a Var, where the host's is the jar's.
  Documented price: redefinition blindness + non-identical fn values;
  the legality gate (§7) decides whether that price is payable, and for
  which names.
- **O2. `:core-aliases` at call heads** for the same probed set, inside
  the twelve libraries' hot paths especially (they evaluate as written;
  their internal `(inc x)`-shaped calls name the booted vars).
- **O3. The C5 remainder**: statics still emitted as shim Var calls
  inside booted bodies where a direct `(. Class (m …))` form is legal
  JVM source (passthrough already does this for *unknown* statics; the
  known ones take the shim path). A toggle that emits the direct form
  for a named set (Numbers add/inc/dec/lt/gt, RT first/next/nth/get)
  prices the Var hop exactly.
- **O4. Multimethod**: if T4 prices the shim MultiFn's dispatch above
  the real one calm, a bigger cache or a dispatch-structure port is the
  opportunity; if the cache closes the gap, C7 stands as a validated win
  to *keep*, and its X3-1 precedent (a shim structure beating the naive
  port) is the template for O1–O3.
- **O5. Boot-side audit of `:inline` survival** (from T3): any booted var
  that lost its `:inline` between core.clj's definline and the compiled
  core is a pure-loss bug, fixable in the boot, winning on every host
  that compiles through it — the highest-leverage item if it exists.

## 7. Legality gate + proposed landing set (appended at the end of the lane)

Every rewrite validated or proposed above ships a verdict only with the
cohost JVM battery + library suite green on the treated core
(`bb` battery tasks on the bench tree); failures mark the proposal
FAILED with the failing rows named. §7 collects the survivors as the
proposed landing set for cohost proper. Landing itself is a separate
act, not this lane's.
