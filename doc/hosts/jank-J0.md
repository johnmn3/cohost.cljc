# J0: jank's object model, read from source

Read 2026-10-31 ET from jank `main` at `3b7ffc2`, with history fetched to depth 400.
Nothing was built or run, so every behavior below is read from source, not observed.
Paths are under `compiler+runtime/`. Inferences are labeled as such.

## The answer

jank's object model is **half open**.

A C++ subclass of `jank::runtime::object` gets per-instance dispatch for these:
- `seq`, `first`, `next`, `get`, `contains?`;
- `=`, `hash`, `str`, `pr-str`, `deref`;
- being called as a fn.

So such an instance can be a map key, sit in jank's collections, be printed and be
called.

`count`, `conj`, `assoc`/`dissoc`, `nth` and `meta`/`with-meta` still go through
`visit_object`. That is a closed `switch` on `object_type`, with no value for a new
type, and its `default:` calls `jtl::panic`. It aborts the process; it does not throw.

## How a type is declared

`ab85cc8` "Open the object model" (#677, 2026-02-04) gave every object a virtual base:
- the base is `struct object : gc` (`include/cpp/jank/runtime/object.hpp:411-616`);
- its constructor is `object(object_type, object_behavior)` (object.hpp:416);
- its fields are `type` and a behavior bitset (object.hpp:614-615).

A built-in type declares:
- `static constexpr object_type obj_type`;
- `static constexpr object_behavior obj_behaviors`;
- `static constexpr bool pointer_free`.

It then overrides the virtuals; `obj/atom.hpp:13-19` is an example. `make_box<T>` needs
`T::obj_type` (concept `object_like`, object.hpp:631-636). There is no registration
step.

`object_type` is closed: it ends at `max = exception_info` (object.hpp:16-113).
`opaque_box` (object.hpp:104) is a built-in boxed `void*`, not an extension point.

The repo has no example of a JIT-defined subclass of `object`:
- the book says "Improved support for extending jank's object model with JIT … compiled
  types will be coming soon" (`book/src/cpp-interop/native-types.md:27-31`);
- 225 `test/jank` files use `cpp/raw`, and none subclasses `object`.

## Behaviors

**Virtual on `object`, each with a bit in `object_behavior`** (object.hpp:361-375,
422-612):
- `call` (0–10 arguments, plus a packed rest);
- `get`, `contains`, `find`;
- `nth` (bit `indexable`);
- `compare`;
- `seq` / `fresh_seq` (bit `seqable`);
- `first` / `next` (bit `sequence_like`), `next_in_place`;
- `deref`;
- the ref-like watch and validator methods, `to_integer` / `to_real`, the array methods.

**Always present, with no bit:** `equal`, `to_string`, `to_code_string`, `to_hash`.

**Still compile-time concepts**, commented out as future bits (object.hpp:376-388):
- `countable`, `conjable`, `associatively_writable`, `metadatable`;
- `sequential`, `collection_like`, `stackable`, `transientable`.

## Which core fns dispatch per instance

| core fn | path | for a new type |
|---|---|---|
| `seq`, `first`, `next` | virtual (core.jank:227-229, seq.cpp:299-325) | dispatches |
| `get`, `contains?` | virtual (seq.hpp:102-110, seq.cpp:518-533) | dispatches |
| `=` | `lhs.equal(rhs)` (equal.cpp:19-22) | dispatches |
| `hash` | `to_hash` (core.cpp:623-626) | dispatches |
| `str`, `pr-str` | `to_string` / `to_code_string` (seq.cpp:798-815, core.jank:630-631) | dispatches |
| `deref` | virtual (core.cpp:714-717) | dispatches |
| calling it | codegen emits `.call(...)` (cpp_processor.cpp:589-605); `ifn?` reads the `call` bit | dispatches |
| hash map/set key | `std::hash<object_ref>` → `to_hash`, `equal_to` → `equal` (detail/type.hpp:44-57) | works |
| `count` | `visit_object` + concept (seq.cpp:833-866) | **panics** |
| `conj`, `assoc` | `visit_object` (seq.cpp:387-450) | **panics** |
| `nth` | `visit_object`, then the `indexable` bit (seq.cpp:639-680) | **panics** |
| `meta`, `with-meta` | `visit_object` (meta.cpp:13-60) | **panics** |

The panic is in `include/cpp/jank/runtime/visit.hpp:291-294`; `jtl::panic` is
`[[noreturn]]`.

## Other facts the jank adapter needs

**`cpp/raw`.**
- It is analyzed at `analyze/processor.cpp:4041-4114`: one string literal, guarded by
  `#ifndef JANK_CPP_RAW_<hash>`.
- It is evaluated by `jit_prc.eval_string` and returns nil (evaluate.cpp:597-601).
- A class defined there can be constructed: `test/jank/cpp/member/pass-access-base.jank`
  does so.

**Calling a jank fn from C++.** Hold it as `jank::runtime::object_ref` and call
`f.call(a1, …)` (oref.hpp:521+), or `jank::runtime::apply_to(f, args)`
(core/call.cpp:13-18). The free `dynamic_call` was removed in `308a857` (#806).

**Catching.**
- `(catch :default e …)` is the catch-all (processor.cpp:2956-2968). Its codegen is
  `catch(...)`, and it binds `e` to nil (cpp_processor.cpp:1109-1113).
- A thrown jank value is caught with `cpp/jank.runtime.object_ref`.
- `ex-info` builds `obj::exception_info` (`message`, `data`, `cause`;
  exception_info.hpp:31-56).

That `:default` binds `e` to nil matters: cohost's sites that read the exception they
caught need the `object_ref` form on jank. The `(catch … _ nil)` sites do not.

**Types and macros.**
- `deftype`, `defrecord`, `defprotocol` and `reify` are not defined anywhere in
  `src/jank/**`. The book says "No records (yet) / No protocols (yet)"
  (`book/src/differences-from-clojure.md:37-41`).
- `&env` is not provided: `macroexpand1` has `/* TODO: Provide &env. */`
  (context.cpp:633-635). That is A2's case.

## What this means for J1/J2

The plan's fallback, type-tagged values with shim dispatch, would give up three things:
an instance as a map key, a callable instance, and printing. On the virtual path above,
all three work. So the plan is a hybrid:

1. **J1 — `cohost_object`.** One `object` subclass, defined once through `cpp/raw`,
   with fields `descriptor` and `fields`.
   - It sets the virtual behaviors (`call`, `get`, `seq`, `first`/`next`, `deref`,
     `equal`, `to_hash`, `to_string`/`to_code_string`).
   - Each forwards to the jank fn in the descriptor's method table.
   - Its `obj_type` must be a value outside the enum (there is none to reuse:
     `static_cast`ing it to a built-in's class is undefined behavior).
2. **J2 — the shim keeps `count`, `conj`, `assoc`, `nth` and `meta` off jank's own fns.**
   Stock core.clj calls them through the shim (`RT/count`, `RT/conj` …). There the
   adapter's `:user-type?` sends a `cohost_object` to its own method before any jank fn
   runs. A `:natives` entry or a host alias that would hand one to jank's `count` must
   not apply to user types.
3. **Probe first, at jank's M0.** Two things are needed before J1 is built:
   - a JIT-defined `object` subclass with an out-of-enum `obj_type` constructs and
     survives `seq`, `=`, `hash`, `str` and a map lookup;
   - `count` on it panics exactly as read here.

   If the first fails, use the plan's tagged-value fallback and record its three host
   facts.

Upstream: the object.hpp:340-360 comment plans to port the remaining concepts to
virtual behaviors. When `countable`, `conjable`, `associatively_writable` and
`metadatable` become bits, step 2's guard can go.
