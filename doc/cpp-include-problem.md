# The cohost `:include` problem — full account (2026-11-03, lane M)

## The feature

Cohost (github.com/johnmn3/cohost.cljc) boots stock Clojure on several hosts,
jank among them. The C++ passthrough (branch `muse/cpp-passthrough`, commit
9c99e6e on main 2ddebc8, plus uncommitted boot.cljc work described below) lets
Clojure code use jank's `cpp/` interop directly. One piece is the namespace
clause from John's spec:

```clojure
(ns foo (:include "vector"))   ; like jank's own ns clause
```

In jank, `(ns x (:include "vector"))` expands to `(clojure.core/include
"vector")`, a macro (core.jank:5734) expanding to a top-level
`(cpp/raw "#include <vector>")`. Cohost's boot.cljc consumes ns forms itself
(its `ns!`), so the passthrough teaches `ns!` to collect `:include` /
`:refer-global` clauses on `:cpp?` hosts (jank only) and emit them for the
host.

## The failing case

Battery row (src/cohost/battery.cljc, one eval-str string, two forms):

```clojure
#?(:jank (ns bt.cpp (:include "vector")) :default (ns bt.cpp))
#?(:jank (let [v (cpp/new (cpp/std.vector cpp/int64_t))
               n (cpp/.size v)]
           (cpp/delete v) n)
   :default 0)                                   ; expected 0
```

Result on every run, both boxes: **FAIL — "Unable to compile C++ source"**,
with the clang error:

```
nil:1:6: error: no type named 'vector' in namespace 'std'; did you mean 'hecto'?
    1 | std::vector::size_type v3{ };
```

The other four new cpp battery rows pass (direct call, two catch rows, the
`^cpp/long` hint row). Libraries 62/62. Snapshot builds (exit 0).

## What works (all measured through cohost's jank host, snapshot build)

1. A bare constructor after the clause, as its own form:
   `(cpp/new (cpp/std.vector cpp/int64_t))` → "native object of type
   `std::vector<long> *` that is not convertible" — i.e. the type RESOLVED
   and the constructor ran; the error is only about returning a raw pointer.
   True for: the ns form in its own eval call; the ns form and constructor in
   one eval-str string; the constructor in a later call. Analysis-level use
   of the included type works in all these shapes.
2. The same constructor after a hand-written top-level
   `(cpp/raw "#include <vector>")` in a previous form: works (same error).
3. Plain jank, one FILE: `(ns pj-ns (:include "vector"))` + uses → works.
   Jank compiles a file's namespace as one module.
4. Plain jank, two `-e`-style forms in one process where the first is
   `(cpp/raw "#include <vector>")` and the second is the bare constructor:
   works.

## What fails (all measured)

5. The battery row above (let binding a member-call result), via eval-str,
   via the battery, on both boxes, in every code variant tried.
6. A FILE loaded through cohost's loader:
   `(ns t3 (:include "vector"))` + the same let → "Unable to compile C++
   source". (A file with a hand-written top-level cpp/raw + a use also
   failed, earlier variant.)
7. The let shape after a literal top-level `(cpp/raw "#include <vector>")`
   as the immediately preceding form — NO cohost ns machinery involved:
   `(cpp/raw "#include <vector>")` then
   `(let [v (cpp/new (cpp/std.vector cpp/int64_t)) n (cpp/.size v)] (cpp/delete v) n)`
   → same clang error, and this run exposed the generated fragment:
   `std::vector::size_type v3{ };` with "In file included from
   <<< inputs >>>:1".
8. The let shape wrapped by hand in one form with the raw:
   `(do (cpp/raw "#include <vector>") (let ...))` → same failure.
9. Variants of the let that still bind the `.size` result to a local fail;
   the bare constructor (no binding of a member result) passes.

So the failure is specific: **a form whose generated code must materialize a
value of a type that comes from the included header** (here
`std::vector::size_type`, the return type of `.size`, bound to local `n`)
fails in jank's JIT eval, even when the `#include` was a literal top-level
form in the same session moments earlier.

## The mechanism, as far as jank's source establishes it

Jank source: github.com/jank-lang/jank (cloned at
~/workspace/cohost-install/jank-src).

- **Analysis** resolves `cpp/…` names through CppInterOp (Clang's incremental
  interpreter): one global interpreter; declarations accumulate across
  translation units. This is why constructor analysis succeeds after any
  earlier raw/include: the interpreter's AST knows `std::vector`.
- **Codegen** (codegen/cpp_processor.cpp) builds one C++ TU per module/form:
  `declaration_str()` = that unit's own `cpp_raw_buffer` + module header +
  deps + body. `gen(ir::inst::cpp_raw)` appends a raw's text to the unit's
  buffer. A form compiled on its own therefore has only its own raws in its
  TU — expected, and cohost's prepend (below) addresses exactly this.
- **`cpp/raw` texts are hash-guarded**: analyze/processor.cpp
  `analyze_cpp_raw` wraps every raw text in
  `#ifndef JANK_CPP_RAW_<hash of the raw string>` … `#endif` ("jank
  currently does codegen twice … ODR violations"). The same text is
  therefore suppressed in every TU after the first one that carried it.
  This defeated the first prepend attempt (same text in the let's own TU,
  guarded out).
- The failing fragment (`std::vector::size_type v3{ };`, parsed as its own
  `<<< inputs >>>`) appears to be jank asking the interpreter to materialize
  / declare the let's local. That input is NOT the form's
  declaration_str() TU (which would start with the raw buffer) — it starts
  at the declaration line itself, parsed in a context where the header is
  absent. **Why that context lacks the header — given the interpreter
  processed `#include <vector>` TUs earlier in the same process — is the
  open question.** Probe 7 shows it is not a cohost artifact: plain jank
  forms reproduce it.

## What cohost currently does (uncommitted, on top of 9c99e6e)

boot.cljc, in order of attempt:

1. v1/v2 (committed history in the worktree only): ns! evaluated
   `(cpp/raw "#include <h>")` / the host ns route directly from ns!.
   Failed the row (the eval is nested inside the ns form's own evaluation).
2. **Drain**: ns! queues the clause forms on `pending-host-forms`;
   `eval-in*` evaluates them as top-level forms, in the new namespace,
   immediately after the directive, before the next form is read. Effect:
   analysis-level uses started passing in every shape (probe 1). The row
   still failed — codegen layer.
3. **Record + prepend**: ns! also records the raws per namespace
   (`cpp-raws-by-ns`; verified live: `{bt.w9 [(cpp/raw "#include
   <vector>")]}`); `eval-with-cpp-raws` prepends them, in one `do`
   evaluated as a single form, to every later form in that namespace
   (deliberately bypassing eval-rewritten's twin split). Effect: none on
   the row — the guard (above) suppressed the repeated text.
4. **Uniquified prepend**: each prepended copy gets a unique trailing
   comment (`// cohost tu N`) so its guard hash differs. Effect: none on
   the row — the failing fragment is not the prepended TU at all (see open
   question). Snapshot rebuilt with this code (352 s) and the row re-run
   clean, alone: still the same error.

Note: attempt 3's first insertion also broke the AOT build once
(`pending-host-forms could not be resolved` — the defonce sat below its
first use; the JIT tolerates that, AOT analysis does not). Fixed by moving
definitions above the eval section. A second insertion put a helper between
eval-in*'s docstring and its arg vector (parens balanced, semantically
corrupt; AOT caught it). Both fixed; current file is paren-clean and the
snapshot builds.

## The open question for whoever picks this up

When jank's JIT compiles a form whose code must materialize a local of an
included type (the `std::vector::size_type v3{ };` fragment), in what
interpreter context is that fragment parsed, and why does it not see
headers that earlier TUs in the same process included? If that context is
supposed to accumulate, the accumulation is what to fix (possibly a jank
issue). If it is intentionally per-form, then a form-at-a-time evaluator
like cohost needs a sanctioned way to attach include texts to a form's
materialization — or the feature's honest scope on the JIT path is
"analysis-level uses", with full use reserved to module compilation
(AOT / whole-file), which works today.

## Pointers

- Branch: `muse/cpp-passthrough` @ 9c99e6e (pushed nowhere yet; the
  boot.cljc drain/record/prepend work is uncommitted in
  ~/workspace/cohost-cpp and synced to /home/john/cohost-cpp on j.slaa.us).
- Plan: ~/workspace/cohost-install/cpp-passthrough-plan.md
- Gate logs on j: /home/john/cpp-gates-j.log, cpp-gates-j2.log, rowtest4.log
- The two stock battery rows that fail only on j's path ("subs count nth
  first last butlast", "Character outside ASCII" — reader errors) and the
  j corpus delta (4398 vs 4401) are separate, undiagnosed, and not part of
  this problem.
- Also found, unrelated: jank's snapshot launcher link needs `-lz`
  (zlib1g-dev) on the host; nothing checks for it beforehand — a preflight
  probe in cohost's snapshot ensure would turn a minutes-late raw ld error
  into a one-line message.

## Resolution (2026-11-04 ~00:15 ET, John's probe)

JANK_PRINT_CODEGEN=1 diff decided it — hunch 1 was right:

- Working paths (jank compile-module, jank run of a file) declare the size
  local as `auto &&v3(v2->size());` — deduced, no typedef spelled.
- The eval path materializes the local through a separate declaration
  fragment, `std::vector::size_type v3{ };` — template arguments DROPPED
  (`std::vector` for `std::vector<long>`). That spelling cannot resolve
  with any headers visible. It is jank's type printer on the eval path,
  not header visibility; the TU/guard analysis above was real but not the
  deciding layer.

Workaround, verified on the eval path through cohost: cast the member
result — `(cpp/cast cpp/long (cpp/.size v))` — the row then returns 0.
(`^cpp/long n` does NOT work; the materialization still spells the
typedef.) Second, separate defect: `(cpp/delete v)` on a template type
fails JIT symbol lookup (`std::__new_allocator<long>::deallocate` not
found; plain-jank repro in pj2.jank) — the battery row drops the delete.
Both defects reproduce in plain jank and belong upstream with these
outputs. The drain in boot.cljc stays (it is what makes the header
visible to analysis within one eval call); the prepend/uniquify
experiments were removed — they cannot reach a fragment that is not in
the form's TU.
