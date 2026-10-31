# Plan: five new hosts (clojurust, jank, let-go, ClojureWasm, Phel)

Written 2026-10-31 ET, on branch `claude/eager-euler-ci8ev7`, for the agent that builds it.

## Where the facts come from

**`doc/hosts/survey-five.json`** is a read-only survey of 2026-10-31. For each of the five
hosts it holds the assessment and an adversarial re-check. Every claim cites a source: the
host's own source file, docs, or commit and release pages. Nothing was installed or run.

**`dev/host-workflows/`** holds the two Workflow scripts:
- `clojure-host-survey.js` is the survey.
- `five-hosts-plan.js` was started, then stopped before it reported. It designs each
  adapter capability by capability from the host's source, plus jank's type layer and the
  shared layer.

Re-run `five-hosts-plan.js` with the Workflow tool to get those deeper capability maps.
It is read-only research.

## Goal and done-bar

cohost boots stock core.clj on each of the five hosts, the same way it does on jolt and
basilisp today. A host is done when all of these hold:

- **Boot:** `bb boot:<host>` boots core.clj's 791 forms with `errors: 0`.
- **Battery:** passes 139/139, except rows that are documented host facts in
  `doc/REPORT.md`, such as `(= 1 1.0)` on hosts with one number type.
- **Library battery:** 61/61.
- **Bench:** `bb bench:<host>` writes `target/bench-<host>.edn`, and `doc/PERF.md` has the
  host's column.
- **Docs:** `README.md`'s status table and `doc/REPORT.md` have the host's row and host
  facts.

## The five hosts at a glance

| host | language | reader key | pinned | status (2026-10) | biggest gaps for cohost |
|---|---|---|---|---|---|
| ClojureWasm (`cljw`) | Zig | `:cljw` | v1.15.4 (2026-10-23) | active; maintainer handed to BuddhiLW in 2026-10 | the reader also matches `:clj` (first branch in written order wins); 57/247 suite namespaces failing at v1.13.4 |
| Phel | PHP 8.5 | `:phel` | v0.54.0 (2026-10-04), 1.0 RCs | very active | no `refer`/`alias`/`push-thread-bindings` fns; PCRE pattern strings; no char type; no StringBuilder |
| let-go | Go | `:lg` | v1.13.0 (2026-09-20) | very active | `&env` is nil; binding is per var (`push-binding!`); RE2 regexes, no `re-matcher` |
| clojurust (`cljrs`) | Rust | `:cljrs` (legacy `:rust`) | v0.2.32 (2026-10-29) | very active, about 6½ months old | no `macroexpand-1`, `push/pop-thread-bindings`, `refer` or `load-string` fns; `read-string` takes no opts; no `.` interop; macros only via `defmacro` |
| jank | C++/LLVM | `:jank` | main (no versioned releases) | active, funded, alpha | no `deftype`/`defrecord`/`defprotocol`/`reify` yet; `&env` not passed; `load-string`/`read`/`var-set` are TODO stubs; `eval` only in the dynamic runtime |

Every host has these: its own reader key with `#?@`, runtime `eval`, namespaces and vars
with metadata and `alter-var-root`, `ex-info`, and persistent collections.
`doc/hosts/survey-five.json` has the evidence for each.

## Step 0: installs (need the user's approval: they download outside code)

Pin each version and record it in `bb.edn`.

- **ClojureWasm:** the prebuilt Linux x86_64 binary from the BuddhiLW/ClojureWasm releases
  page, v1.15.4.
- **Phel:**
  - PHP 8.5 first, because the box has 8.3.6:
    `add-apt-repository ppa:ondrej/php && apt-get install php8.5-cli php8.5-mbstring`.
  - Then `phel.phar` v0.54.0 from the phel-lang releases page, or
    `composer require phel-lang/phel-lang:0.54.0`.
- **let-go:** the v1.13.0 release binary from nooga/let-go releases. Its `go.mod` wants Go
  1.27 and the box has 1.24.7.
- **clojurust:** `cargo install cljrs --version 0.2.32`. Check the crate and binary names
  first on crates.io.
- **jank:** the apt repo for Ubuntu 24.04, per book.jank-lang.org's install page.

Each install becomes `dev/install/<host>.sh`. Its done-bar: `<host> --version` (or the
equivalent) prints the pinned version.

## Part A: the shared layer (build once, before the hosts that need it)

### A1. Reader keys and branch order

- **Add the branches.** Every `#?`/`#?@` in `src/cohost/**` gets branches for `:cljw`,
  `:phel`, `:lg`, `:cljrs` and `:jank`, wherever a host's code differs. Elsewhere the
  `:default` branch is the generic one.
- **Watch the order for `:cljw`.** ClojureWasm's feature set is fixed at
  `{:cljw :clj :default}`, and the first matching branch in written order wins. So in every
  conditional with a `:clj` branch, `:cljw` must come before `:clj`.
- **Add a lint.** A `bb lint:reader-order` task should fail on any `#?` form where `:clj`
  precedes `:cljw`.
- **The other keys.** let-go matches `:clj` only when `LG_READ_CLJ` is set, so leave it
  unset. clojurust, jank and Phel never match `:clj`.

### A2. Hosts whose macros get no `&env` (jank, let-go)

`3e59a25`'s single walk leaves a rewritten macro's arguments raw and rewrites them once, in
the expansion. It learns the locals from the macro's `&env`:
- `rewrite.cljc`: `env-types`, the marker locals `cohost__hint_<class>_<depth>__<name>`;
- `boot.cljc`: `wrap-macro`;
- adapter `:env-locals`.

jolt's `&env` carries names only, and that is enough. With a nil `&env`, the expansion's
rewrite sees no locals, which breaks three things:
- a local or parameter named like a core fn is rewritten as the core fn;
- the hint markers are lost, and with them the checkcasts;
- inlining and the `instance?` shortcut apply to shadowed names.

**Fix:** add a capability, `:macro-env? false` (default true). When it is false,
`rewritten-macro?` behaves as before `3e59a25`: the codemod walks the macro's arguments
before expansion with `*inline?*` false, and the expansion is walked again. That is the
`5506876` path; recover it from git (`git show 5506876:src/cohost/rewrite.cljc`).

These battery rows guard it:
- "an inline fn applies after macroexpansion";
- "assert sees the source form";
- the local-named-like-a-core-fn rows;
- the hinted-argument checkcast row.

### A3. A cohost type layer for hosts without (full) types

Today `:type-method-style` is `:jvm`, `:protocols` (ClojureScript) or `:python`
(basilisp). See `rewrite.cljc` and `adapter.cljc`, under `:type-method-style`,
`:host-method`, `:user-type?` and `:invoke-member`.

- **Hosts with types.** clojurust, let-go, cljw and Phel all have `deftype`/`reify`/
  protocols, so each maps onto its own types with the closest existing style. Decide that
  per host in its M1.
- **jank, which has none.** It needs a new style, `:cohost-types`:
  - `deftype*`/`reify*` build an instance through a host-provided constructor, given the
    type's descriptor and its fields;
  - `defprotocol`/`extend` become dispatch tables in the shim, keyed by a type id: the
    descriptor for cohost types, the host's own type for host data;
  - `instance?`/`satisfies?` read the descriptor.

  The representation is the adapter's choice, behind three capabilities:
  - `:new-instance [desc fields]`;
  - `:instance-desc [x]` (nil when `x` is not a cohost instance);
  - `:instance-field [x i]`.

  Part B/jank gives jank's representation.

### A4. Shim fallbacks (defaults the adapter contract should get)

- **String builder** (Phel, jank, clojurust): when `:string-builder` is absent, the shim
  provides one. It accumulates parts in a host vector held in a volatile, and `toString`
  is `apply str`.
- **Regex group positions** (`:re-find-at`): each host adapts its engine.
  - **let-go:** Go RE2's `FindStringSubmatchIndex` through interop, if let-go exposes it;
    else a gap. RE2 has no lookbehind or backreferences, so record those as host facts.
  - **Phel:** `preg_match` with `PREG_OFFSET_CAPTURE` through `php/` interop. PCRE wants
    delimiters.
  - **clojurust:** Rust regex captures, if exposed. Otherwise the shim's fallback: match,
    then locate each group with `index-of` from the match start. This is wrong for
    repeated groups, so record it as a host fact.
  - **jank:** its `re-matcher`/`re-groups`; positions through C++ `std::match_results`
    via interop.
- **Thread bindings:**
  - **let-go:** composed from per-var `push-binding!`/`pop-binding!`. The adapter keeps a
    stack of frames, each a map, and pops every var of the top frame.
  - **clojurust:** only `with-bindings*`. Push/pop cannot be built from it, so the
    codemod must rewrite `binding`'s `push-thread-bindings` … `finally`
    `pop-thread-bindings` into `(with-bindings* m (fn [] body))`. Add the capability
    `:binding-style :push-pop | :with-bindings` to choose.
- **`macroexpand-1`** (clojurust): the adapter resolves the head var, checks it is a macro,
  and applies its fn to `(cons form (cons env args))`. Whether a clojurust macro var's
  value is callable is an open question.
- **`read-string` with opts** (clojurust): `read-string` takes one argument, and the reader
  keeps every branch for the evaluator to choose. cohost's `:read-forms` reads core.clj,
  which has no reader conditionals, so the 1-arity is enough to boot. Code that calls
  `(read-string {:read-cond ...} s)` gets a host-fact row.
- **`load-string`** (clojurust, jank): cohost reads with `:read-forms` and evaluates each
  form, so it needs no `load-string`.
- **No `.` interop** (clojurust): every capability must be a clojurust fn. Mark
  `:interop-passthrough? false`. Then every java/clojure.lang member reaches the shim.

### A5. Harness

- **Tasks.** In `bb.edn`, add `boot:<host>` and `bench:<host>` for `cljw`, `phel`, `lg`,
  `cljrs` and `jank`, like `boot:lpy`/`bench:lpy`. Each runs `src/cohost/run.cljc` on the
  host.
- **Load path.** How each host puts `src` on its load path is in its M0.
- **`run.cljc`.** It needs each host's entry; the `#?` branches in A1 cover it.
- **Conformance corpus (later).** Run jank-lang/clojure-test-suite through cohost on every
  host. All five already run some version of it on their own core.

## Part B: per host

The milestones are the same for every host:
- **M0** — install (step 0). Then probe the open questions below and record the answers in
  `doc/REPORT.md`.
- **M1** — `src/cohost/hosts/<host>.<ext>` exists; cohost's namespaces load on the host,
  and boot starts. Done-bar: `bb boot:<host>` prints `cohost.boot on <host>: 791 top-level
  forms`.
- **M2** — boot reports `errors: 0`.
- **M3** — the battery passes 139 minus documented host facts.
- **M4** — the library battery passes 61/61.
- **M5** — `bb bench:<host>` writes its EDN, and `doc/PERF.md` gets the column.
- **M6** — `:natives` opt-ins, each gated by the battery and the bench's JVM check. Last
  come README, REPORT and the HANDOFF entry.

Size each adapter against its analogs:
- `hosts/basilisp.lpy` is 379 lines;
- `hosts/jolt.clj` is 121, plus the shared 223 of `hosts/java.clj`.

### ClojureWasm (cljw): build first

- **What it has.** It has the most complete API of the five:
  - callable `macroexpand-1`;
  - `push/pop-thread-bindings` and `get-thread-bindings`;
  - `refer`, `alias`, `alter-var-root`;
  - a real `&env`, a map of locals;
  - `eval` and `load-string`;
  - `deftype` and protocols;
  - regexes with groups.
- **The catch** is A1's branch order.
- **It emulates java.lang/clojure.lang itself.** That may let it behave like jolt
  (`:interop-passthrough? true`), or it may collide with cohost's shim. Settle which in M0.
- **Probes:**
  - `cljw -e '(read-string {:read-cond :allow} "#?(:cljw 1 :clj 2)")'` (expect 1);
  - `(defmacro m [] (count &env)) (let [a 1] (m))` (expect 1);
  - `(class "")` and `(instance? clojure.lang.IPersistentMap {})`, to see what its JVM
    emulation answers.

### Phel: second

- **Reader and macros.** The parser takes `#?(:phel …)` and `#?@`, falling back to
  `:default`. Macros are defs with `:macro true` meta, and they get a real `&env`.
- **Vars.** `PhelVar` has `alterRoot`, `alterMeta`, `resetMeta` and `isDynamic`.
- **Binding.** Frames are fiber-local; PHP's `DynamicScope::pushFrame` backs them.
- **What the adapter must build:**
  - `:refer!`/`:alias!`: eval an `(ns …)`/`(require …)` form, or call the PHP internals
    through `php/`;
  - `:push-thread-bindings!`: `DynamicScope` through `php/`;
  - `:char-code`: chars are strings;
  - the string builder (A4) and regex positions (A4).
- **Types.** Phel has `defstruct`, plus `deftype`/`defrecord` as macros over it, and
  `reify`. Pick the closest existing style in M1.
- **Probes:**
  - `(eval '(def ^:dynamic *x* 1))` then `(binding [*x* 2] *x*)`;
  - `(alter-meta! #'*x* assoc :k 1)`;
  - how `php/preg_match` returns offsets.

### let-go: third (needs A2 and A4's binding composition)

- **What it has.** `eval`, `read-string` and `load-string`; namespace-scoped eval.
  `create-ns`, `intern`, `in-ns`, `alias`, `refer`, `resolve`, `ns-resolve`, `find-var`,
  `alter-meta!`, `alter-var-root`, `set-macro!` (which gives `:mark-macro!` directly) and
  `macroexpand-1`. Catching `Throwable`, or a bare `(catch e …)`, catches everything.
- **Loading.** The loader resolves `.lg`, then `.cljc`, then `.clj`.
- **Gaps:**
  - `&env` is nil (A2);
  - binding is per var (A4);
  - regexes are RE2 with no `re-matcher` (A4).
- **Probes:**
  - `(let [a 1] (defmacro m [] &env) (m))` (expect nil, confirming A2);
  - Go interop for regex submatch indices;
  - whether `.cljc` files in `src/` load by namespace.

### clojurust (cljrs): fourth (needs A4's binding and macroexpand fallbacks)

- **What it has.** Its `&env` is a real map of locals. It has `deftype`, `reify` and
  `defprotocol` (its TODO.md marks them done), `alter-var-root`, `alter-meta!`, `intern`,
  `create-ns`, `find-ns`, `ns-resolve`, a catch-all and `ex-info`.
- **Gaps:** A4's `macroexpand-1`, binding, `read-string` opts and no-interop items. Also:
  - `refer` is absent, so `:refer!` must intern each public var into the target namespace
    (this changes semantics: the vars are copies, not refers);
  - macros can be marked only by the `defmacro` special form, so use the adapter's
    existing `:macro-def-style :defmacro`;
  - `alter-var-root` and `with-bindings*` are "sentinel builtins" the evaluator
    intercepts, so they may not be first-class values. The adapter must call them as
    written, never pass them as fns.
- **Probes:**
  - `(let [f alter-var-root] (f #'x inc))`;
  - `(defmacro m [x] x)` then `((var-get #'m) '(m 1) nil 1)`;
  - `(with-bindings* {#'*d* 2} (fn [] *d*))`.

### jank: last (needs A2, A3 and its object-model work)

- **What it has.**
  - `#?` matches `:jank`, `:default` or extended features, and `#?@` splices.
  - `read-string` works at run time once `*read-eval*` is bound. `read` and
    `load-string` are TODO stubs, so implement `:read-forms` through the C++ reader via
    `cpp/` interop, or by reading one form at a time with an offset.
  - `eval` works in the dynamic runtime only.
  - Vars and namespaces: `create-ns`, `intern`, `find-ns`, `ns-resolve`, `ns-map`, `refer`,
    `alias`, `in-ns`, `alter-var-root`, `alter-meta!`, `reset-meta!`. A var is a macro when
    its meta has `:macro`, so `:mark-macro!` is `(alter-meta! v assoc :macro true)`.
    `var-set` is a TODO stub, so `:var-set!` falls back to `alter-var-root` (the contract
    default).
  - `push/pop-thread-bindings` are per thread.
  - `ex-info` exists. The catch-all is `cpp/jank.runtime.object_ref`, which does not catch
    foreign C++ exceptions.
  - `re-matcher`/`re-groups` exist.
- **Gaps:** `&env` (A2), the string builder (A4), and types (below).
- **Data structures: nothing to bring.** jank has Clojure's persistent collections, lazy
  seqs, atoms and the rest natively. cohost uses the host's own data, so no eve-like layer
  is needed.
- **Types: the "basic type hierarchy" through jank's opened object model.** Agreed with
  the user 2026-10-31. jank's 2026-03-06 blog post says the object model "has been opened
  up" and allows "JIT-defined objects while still avoiding the costs of C++'s runtime type
  information": "jank users can define their own jank objects which integrate well into
  the system, can be stored within jank data structures, and used with jank functions".
  The plan:
  1. **J0 — read the mechanism first.** Find the commits/PRs behind it (about 2025-11 to
     2026-03; search for object model, custom object, behaviors, RTTI). Establish three
     things:
     - how a new object type is declared;
     - which behaviors it can implement (count, seq, first/next, nth, get, assoc, conj,
       call, =, hash, print, meta);
     - whether jank's own core fns dispatch to it.

     Its 2023 object-model rework moved to a tagged object model with compile-time
     templates, so this change is the hook. Re-running `five-hosts-plan.js` covers J0.
  2. **J1 — one generic object type.** Define it once at adapter load, from C++ through
     `cpp/raw`, compiled by jank's JIT: `cohost_object { descriptor, fields }`. Each of
     its behaviors calls the jank fn the descriptor's method table holds for that method
     (`count` → the type's `count`, call → its `invoke`, `=`/hash → `equiv`/`hasheq`,
     print → `toString`). `deftype` instances then work with jank's own fns too: as map
     keys, called as fns, printed, and through natives and host aliases.
  3. **J2 — the type style and its three capabilities.** A3's `:cohost-types` style, with
     `:new-instance`, `:instance-desc` and `:instance-field` over `cohost_object`.
     `defprotocol`/`extend` use the shim's dispatch tables. The descriptor's method table
     maps the clojure.lang interfaces core.clj and the shim use to the type's methods.
     That covers IFn, ISeq/Seqable, Counted, ILookup, Associative, IPersistentCollection/
     Map/Vector, IObj/IMeta, IHashEq, IDeref, IPending, IReduce, Sequential and
     java.lang.Object's `equals`/`hashCode`/`toString`. Its rows are the basic type
     hierarchy.
  4. **Fallback, if J0 shows the object model cannot dispatch per instance.** Use plain
     jank values tagged in metadata, with protocol dispatch in the shim. Stock core runs
     on the shim, so most of the battery passes. Record as host facts where it breaks:
     an instance as a map key, a callable instance, and printing.
  5. **Upstream.** jank's creator plans protocols and records for about Q1 2027
     (secondhand, an HN comment). When those land, map onto them instead.
- **Probes (M0):**
  - `(binding [*read-eval* true] (read-string "#?(:jank 1 :default 2)"))`;
  - `(let [a 1] (defmacro m [] &env) (m))`;
  - J0's object-type declaration from `cpp/raw`, once found;
  - `(try (throw (ex-info "x" {})) (catch cpp/jank.runtime.object_ref e (ex-data e)))`.

## Build order

1. **Step 0** installs (with the user's approval).
2. **A1** reader keys plus the lint, and **A5** harness tasks.
3. **ClojureWasm** M1–M5.
4. **A4** string builder and regex fallbacks, then **Phel** M1–M5.
5. **A2** no-`&env` path and **A4** binding composition, then **let-go** M1–M5.
6. **A4** `macroexpand-1`/binding/`read-string` fallbacks, then **clojurust** M1–M5.
7. **A3** type layer and **J0–J2**, then **jank** M1–M5.
8. **M6** for each host; the README status table; `doc/REPORT.md`; `doc/PERF.md`.

Each host lands on its own commit once its milestones pass. Never land a host with a red
battery row that is not a documented host fact.
