# cohost.cljc — report (measured 2026-10-30, US Eastern)

**The question.** Can a clojure-like boot Clojure's own `clojure/core.clj`
(1.12.0, unmodified) by shimming the `clojure.lang` / `java.*` surface it
calls onto the host's data structures, the way clinj runs JVM libraries on
non-JVM hosts — get a stock `clojure.core` out of it — and then load
Clojure's own standard libraries the same way?

**The answer, measured.** Yes on every host tried. One pipeline (codemod →
shim → boot and loader, over a host adapter contract) boots core.clj with
**zero errors on all four hosts**, and loads twelve `clojure.*` libraries
from their unmodified sources, each into its own namespace. Two batteries
check the result against JVM Clojure 1.12.0: 133 core behaviors, and 61 cases
across the twelve libraries (expected values computed on the JVM). Numbers
are from this box (4 vCPU, Linux), one run each, measured while other work
shared the machine (the wall times are upper bounds).

| host | version | core.clj: stock / from host / skipped / errors | core battery | library battery | wall |
|---|---|---|---:|---:|---:|
| jolt (Chez Scheme) | v0.8.23 prebuilt | 537 / 191 / 63 / **0** | **133/133** | **61/61** | 17 s |
| JVM Clojure (reference adapter) | 1.12.0 | 539 / 194 / 58 / **0** | **133/133** | **61/61** | 18 s |
| basilisp (Python 3.13) | 0.5.1 | 565 / 130 / 96 / **0** | **131/133** | **61/61** | 8 min 3 s |
| ClojureScript, self-hosted, Node 24 | 1.12.42 | 572 / 79 / 140 / **0** | **131/133** | **61/61** | 33 s with the compile |
| ClojureScript, self-hosted, Chromium | 1.12.42 | 572 / 79 / 140 / **0** | **131/133** | **61/61** | 19 s in the page |
| ClojureWasm (Zig), measured 2026-10-31 on the 139-case battery | v1.15.4 prebuilt | 555 / 145 / 91 / **0** | **137/139** | **61/61** | 86 s boot |
| Phel (PHP 8.5), measured 2026-10-31 on the 139-case battery | v0.54.0 phar | 591 / 73 / 127 / **0** | **139/139** | **61/61** | 41 s boot |

Every top-level count is out of 791: core.clj's 719 forms, the 37-var prelude
(the dynamic vars the JVM runtime creates before core.clj loads) and the 35
names the companion files and a skipped deftype define (`deftype`,
`defrecord`, `print-method`, `munge`, `Throwable->map`, `->Eduction`, …),
which are taken from the host or skipped, except `namespace-munge` and
`hash-combine`, booted from `core_deftype.clj`'s own definitions.
"From host" = names the policy takes from the host's own core: printing, the
reader, namespaces, `case`, `deftype` and protocols, concurrency. A host that
lacks one gets the stock definition where it can work without the JVM
(ClojureScript and basilisp run core.clj's own `ref`, `dosync`, `alter`,
`printf`, `defmulti`), else it is skipped.

## The standard libraries

Each library is required through cohost's loader from its unmodified 1.12.0
source, `clojure.X` booting into `cohost.X`, and its cases are compared with
`=` to what JVM Clojure 1.12.0 returns for the same source after the same
require (`src/cohost/lib_battery.cljc`; regenerate, never edit, its expected
values).

| library | cases | jolt | JVM | basilisp | ClojureScript | ClojureWasm | Phel |
|---|---:|---:|---:|---:|---:|---:|---:|
| clojure.walk | 7 | 7 | 7 | 7 | 7 | 7 | 7 |
| clojure.set | 5 | 5 | 5 | 5 | 5 | 5 | 5 |
| clojure.string | 10 | 10 | 10 | 10 | 10 | 10 | 10 |
| clojure.zip | 7 | 7 | 7 | 7 | 7 | 7 | 7 |
| clojure.data | 5 | 5 | 5 | 5 | 5 | 5 | 5 |
| clojure.edn | 4 | 4 | 4 | 4 | 4 | 4 | 4 |
| clojure.template | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| clojure.test | 3 | 3 | 3 | 3 | 3 | 3 | 3 |
| clojure.math | 7 | 7 | 7 | 7 | 7 | 7 | 7 |
| clojure.stacktrace | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| clojure.repl | 3 | 3 | 3 | 3 | 3 | 3 | 3 |
| clojure.spec.alpha 0.5.238 | 6 | 6 | 6 | 6 | 6 | 6 | 6 |

Every host loads all twelve. clojure.repl's `source` and
`set-break-handler!` are JVM facilities (class-loader resources,
`sun.misc.Signal`); they fail to load off the JVM and are recorded, and the
cases above do not use them.

What made them load, in order of how many libraries each fix unblocked:

- **A namespace per library.** The loader handles `ns`, `require`, `use`,
  `import`, `alias`, `in-ns` and `refer-clojure` itself, at the top level of
  a library and of user code: `:refer-clojure :exclude` (clojure.zip's own
  `next`, `remove`, `replace`), `:require :as/:refer/:rename/:as-alias`,
  prefix lists, `:import` (clojure.string's `Pattern`, `Matcher`,
  `LazilyPersistentVector`). `clojure.X` boots into `cohost.X` because each
  host has a `clojure.X` of its own its runtime may use. The rewriter keeps
  the original names where they show: `::keywords` read in a renamed library
  carry its own name (`:clojure.spec.alpha/problems`), explicit
  `clojure.string/x` references reach `cohost.string`.
- **Protocols.** The host's `defprotocol`, `extend`, `extend-type`,
  `extend-protocol`, `satisfies?` are in place before any library loads; JVM
  class names in extend positions become the host's types (`Object` is
  basilisp's `python/object` and ClojureScript's `default`; `java.util.Set`
  basilisp's `IPersistentSet` and ClojureScript's two set types).
  ClojureScript has no run-time `extend`: the adapter supplies one that does
  what `extend-type` compiles to. `(instance? clojure.spec.alpha.Spec x)`
  names a protocol's JVM interface; it becomes `satisfies?`.
- **The shim's String, regex and Math.** A portable
  `java.util.regex.Matcher` over three adapter search primitives (find at an
  index, full match, group count): `find` resumes past an empty match,
  `appendReplacement` expands `$n` group references, `Pattern.split` drops a
  leading empty string from a zero-width match and, with limit 0, the
  trailing ones — so core.clj's own `re-find`, `re-seq`, `re-matches`,
  `re-groups` and `re-matcher` now boot stock too. `toUpperCase`, `replace`,
  `replaceAll`, `indexOf` with a char or a char code, `subSequence`, a
  host-native `StringBuilder` the host's `str` prints. Every `Math` static
  clojure.math calls, from the host's math library where it has one, with
  the JVM's answers where the host differs (Python raises where the JVM
  returns NaN or an infinity; jolt's `log10` misses `log10(1000) = 3`;
  ClojureScript computes `ulp`, `nextUp` and `getExponent` from the double's
  bits). `MapEntry`, `Thread/currentThread`, `Compiler/demunge`.
- **EDN.** `clojure.edn` evaluates stock and reads through the host's own EDN
  reader with the JVM's options: basilisp's consults `:readers` only for
  namespaced tags, so `:default` bridges the rest; ClojureScript's
  `read-string` ignores `:eof`, so `read` is used.
- **Run-time reflection on ClojureScript.** Its `resolve`, `ns-interns`,
  `ns-publics` are macros over quoted literals; clojure.test and clojure.repl
  call them with values. The adapter answers them from the analyzer state the
  self-hosted compiler keeps, with `cljs.core/Var` objects.
- **Refs where the host has none.** clojure.test counts with `ref`,
  `dosync` and `commute`. On basilisp and ClojureScript core.clj's own
  definitions boot over a shim Ref (a host atom) and a transaction that runs
  its body once: these hosts run one thread, so there is nothing to retry.
- **Python methods (basilisp).** basilisp's `defprotocol` counts a method's
  arity from its receiver parameter only when that parameter is named `this`
  or `self`, and its `reify` / `deftype` members are Python methods: one
  definition per name, plain-symbol parameters, `Object`'s spelled as
  head-less dunder methods. clojure.spec.alpha's `Specize` (two arities,
  receiver `_`) and its `reify` forms (destructured parameters,
  `clojure.lang.ILookup`'s two `valAt` arities) are exactly that, and failed
  to compile. The adapter declares `:type-method-style :python` (with a
  `:host-method` name table) and the codemod does the rest: a protocol
  signature's receiver becomes `this`, a destructuring parameter moves into
  a `let`, a JVM interface method's arities merge into one variadic member
  dispatching on the argument count, `valAt` becomes `val-at`, `toString`
  becomes `__str__` and goes first. Its own `meta` hands back a reify
  object's `meta` method unbound; the adapter's `:meta` calls it (spec keeps
  a spec's name in its reify's metadata).
- **Smaller facts:** `identical?` is true of equal keywords (the JVM interns
  them; ClojureScript does not, and spec compares `::invalid` with
  `identical?`); `(int \a)` is the char code where chars are strings;
  `(. x -field)` is field access (ClojureScript's `extend-type` expands to
  `(set! (.. T -prototype -p) ...)`); a Var object a macro embeds in its
  expansion (stock `defmulti`'s `#'global-hierarchy`) becomes `(var ...)`
  for compilers that only take forms; basilisp namespaces are emptied of the
  `basilisp.core` refers its `ns` adds whatever `:refer-clojure` says;
  basilisp's `extend-protocol` takes one clause per arity.

## The conformance-corpus scoreboard (jolt)

`bb corpus:jolt path/to/corpus.edn` evaluates the jolt-conformance corpus
(the 4,833 rows marked `:portability :common`, one excluded: an undelivered
promise blocks forever) through the booted stock core and through jolt's own
core, **each side in its own process**. The corpus is jolt's
`test/chez/corpus.edn` (https://github.com/jolt-lang/jolt, EPL-2.0); the
numbers here are for jolt's commit 569ba3b4062d9b4dd2993652bd98ccc8a17f2e90.
Each side has its own process because both share the host's namespace
registry, and a row that makes a namespace (`(ns my.app (:require …))`) on
one side used to meet it on the other. Earlier reports' numbers carried that
contamination.

| | pass | fail | error |
|---|---:|---:|---:|
| the stock core, booted on jolt | **4,186** | 512 | 135 |
| jolt's own hand-written core | 4,368 | 412 | 53 |

Rows the stock core passes and jolt's core fails: 5. Rows jolt's core passes
and the stock core fails: 187 (387 before the first review, 238 before the
second, 207 and 194 after its two batches: host interop on the JVM-like
hosts, the format exceptions, String's null checks; then host exceptions,
iterators, member calls on user types; then hinted arguments, macros,
namespaces; then the rows below that JVM Clojure confirms).
Both sides ran in 68 s in all; jolt's own count moves by a few rows between runs (rows that
time threads and futures). Each side runs in `target/corpus-cwd`, so rows
that write files leave nothing in the library's directory.

Each of the remaining 187 was run again on JVM Clojure 1.12.0 (`bb
corpus:jvm`, after `bb corpus:jolt`): the row's form evaluated in a fresh
namespace, printed, and compared with the row's expected answer as
`cohost.corpus` compares it. For 49 the JVM does not give that answer
either. 32 use names or arities that are jolt or ClojureScript
extensions rather than clojure.core (`atom?`, `regex?`, `sorted-map?`,
`transient?`, `ref?`, `keyword-identical?`, a three-argument `conj!`, a
zero-argument `ns-aliases`, `satisfies?` on an interface, jolt's
reader-feature toggles). 17 expect jolt's behavior where the JVM throws or
answers otherwise: `(distinct #{:only})` throws "nth not supported on this
type: PersistentHashSet" on the JVM (the corpus expects `[:only 1]`), as do
`with-open` over a map with a `:close` fn and a map as a multimethod's
`:hierarchy`, and `str` does not resolve in a namespace `in-ns` just created.
The stock core gives the JVM's answer on 8 of the 17 and throws where the JVM
throws on 5 more.

The other 138 are answers the JVM gives and the stock core on jolt does not
(sorted by the rows' suites and forms): JVM interop jolt shims and cohost
does not (`java.io` readers and writers, charsets, executors and threads,
`clojure.lang.Var` methods, system properties: 59 rows), the host's own
value model showing through (seq classes, chunking and chunked realization,
integer boxes: 41), type hints that coerce on the JVM (`^long` parameters
and returns: 17), macroexpansions and syntax-quote templates naming
`cohost.core/let` where the JVM's name `clojure.core/let` (13), a
syntax-quote splice realized sooner than the JVM realizes it (2: the
codemod walks a macro's output before it is evaluated), and 6 others: names
printing under `cohost.*` (`(doc map)`, the `ns-name` of a required
`clojure.string`), the `Inst` protocol being the host's,
`clojure.string/replace` with a nil replacement (calling nil is a
ClassCastException on jolt and a NullPointerException on the JVM), the `str`
of a class (class tokens are symbols), and `drop` past the long range (the
JVM's `IDrop` path casts the count). Seven more were answered after the
second review: `hash-combine` (4 rows), `->Eduction`, `default-data-readers`
and `empty` of a record.

## The review, and what it changed

An independent review read the codemod, the shim, the loader and the
adapters, and swept the booted core against JVM Clojure 1.12.0 expression by
expression. Four of its eight reviewers finished in the first round (rate
limits stopped the parity sweeps and every verifier); every one of their
findings was reproduced against the JVM before it was fixed, and the
user-visible ones are now battery cases (the core battery grew from 82 to 98).
What changed:

- **Member calls on user types.** `(.f x)` now reaches a deftype or record
  field or a reify / proxy method before the shim's method of that name
  (`(.count (Box. 5))` was 1). The call stays positional, so `->` and `doto`
  thread into it; adapters answer `:user-type?` and `:invoke-member`.
- **Host interop where the host has it.** On the JVM and jolt, interop the
  shim lacks goes to the host as written (`(.trim s)`, `(java.util.Date.)`,
  `Character/isDigit` threw "no shim" before), and user code gets the host's
  own `java.util.ArrayList` / `LinkedList` (the shim's stand-in serves
  core.clj's internals). The host core's own deftypes (an `Eduction`) stay
  its data: the shim's methods answer for them.
- **Class names in scope.** A local, a var of the namespace, or a deftype /
  record name spelled like a class stays a symbol; `case` constants are data.
- **The JVM surface, closer.** Exceptions classify through the JVM hierarchy
  for `instance?` too (stock `ex-data` of a NumberFormatException is nil; an
  AssertionError is no Exception); `Character/isWhitespace` is Java's;
  multimethod preferences are inherited through parents; String and array
  index errors are StringIndexOutOfBounds / ArrayIndexOutOfBounds; `class`
  says BigInt, MapEntry, PersistentQueue, PersistentHashMap where the JVM
  does; radix parsing and printing (`Integer/parseInt s 16`,
  `Long/toString n 16`, `toHexString`); 32-bit unchecked int arithmetic;
  `Matcher.start(g)` / `end(g)` (every adapter returns group spans);
  `(Exception.)` has no message; `StringBuilder.append(null)` is "null";
  `make-array` with several dimensions; hex floats in `parse-double`;
  `java.util.Formatter`'s exception classes; `.concat`, `subs`,
  `starts-with?` raise the JVM's NullPointerException / ClassCastException.
- **The loader.** `:as-alias` of a library not yet loaded, `:reload`, vector
  `ns` clauses, `in-ns`'s value, a failed load that a retry loads again, a
  library without its own `ns` form (the JVM's error), `#'clojure.core/x`
  naming the booted var, `eval-str` as a session that keeps its namespace.
- **ClojureScript records.** cljs.core's own `deftype` / `defrecord` write
  `this-as` and their protocol names unqualified; cohost's namespaces referred
  none of them, so a record implemented nothing and an Object method failed.
  Those names stay referred.
- **The contract.** `install!` refuses unknown capability keys; the three
  predicates listed as required have no default; every capability has a doc.

## The second review

A second review swept the booted core against JVM Clojure 1.12.0 again, this
time with the hosts side by side: sequences and collections, strings and
numbers, functions and the loader, and the ClojureScript and basilisp
adapters, each sweep's findings then checked by a separate verifier. It
reported 43 findings, and the verifiers confirmed 42; the 43rd (`chunk-first`
of a `chunk-cons` saw one element) they judged a documented limit, and it is
fixed too. Each was reproduced here before it was fixed, and 33 more core
battery cases (98 → 131) pin the fixes. What a sweep found and is still
open is listed under "Deliberately not done here". What changed:

- **Exceptions are the host's own.** On the JVM and jolt the shim raises the
  real `java.lang` class, so `str`, printing and `Throwable->map` show
  `java.lang.Exception: boom` rather than an ExceptionInfo carrying a marker
  map. On ClojureScript the shim's exceptions are `js/Error`s named for their
  class; on basilisp, instances of a Python exception class per JVM class.
  Both print as the JVM prints. Reader errors are the JVM reader's classes
  (NumberFormatException for `08`, IllegalArgumentException for a duplicate
  key), and the ClojureScript and basilisp adapters class more of their own
  errors as the JVM does (`No matching clause`, `is not ISeqable`, a
  validator's rejection, an empty pop, a missing protocol method, an arity
  error).
- **Iterators.** On the JVM and jolt `.iterator` is the host's own, so a user
  type implementing `Iterable` or `java.util.Iterator` works with `seq`,
  `vec`, `reduce`, `iterator-seq` and `sequence`. ClojureScript uses
  cljs.core's iterators.
- **deftype and reify on ClojureScript.** A JVM interface method becomes the
  protocol method that does its work there: `IDeref deref` is `IDeref
  -deref`, `Object equals` / `hashCode` are `IEquiv -equiv` / `IHash -hash`,
  and `ILookup`, `IFn`, `Counted`, `Seqable`, `Comparable`, `Iterable` the
  same way. Before, equality was ignored, a lookup gave nil and `@x` threw. A
  protocol method answers `(.m x)`, and a field holding a fn is read, not
  called. On basilisp a `reify` of `IFn` is callable (`__call__`).
- **`format` is java.util.Formatter's on every host.** The shim carries its
  own (`%x %o %e %g %b %c %h %n %%`, flags, widths, precisions, argument
  indexes, the JVM's exceptions) for ClojureScript, where goog's handled only
  `%s %d %f`, and for basilisp, where `format` was Python's `%`.
- **Numbers and characters.** `(Integer/MAX_VALUE)` in call position reads
  the field. Division by zero is ArithmeticException and NaN wins `max` and
  `min` on ClojureScript. `Math/round` is exact at 0.49999999999999994.
  `Character/isLetter` and its kin follow Unicode on ClojureScript and
  basilisp.
- **Strings and regexes.** `clojure.string/split` given a String separator is
  the JVM's ClassCastException: a `^Pattern` hint on an interop target is
  checked, as the JVM's checkcast does. A nil input is NullPointerException.
  Named groups work (`${name}`, `.group m "name"`), and so do `.pattern`,
  `.trim`, `.strip`, `.isBlank`, `.repeat`, `.equalsIgnoreCase`,
  `.codePointAt` and `.toCharArray`. On ClojureScript and basilisp, a method
  the shim lacks reaches the object's own JavaScript or Python method at run
  time instead of failing.
- **Collections.** `array-map` and keyword arguments keep insertion order
  past 8 entries. A trailing nil or `[k v]` in keyword arguments is taken as
  the JVM takes it. `sort` takes a `java.util.Comparator`. `seqable?` sees
  Seqable and Iterable objects. `instance?` of nil is false, and Iterable,
  Collection and IPersistentStack are the JVM's classes. `chunk-cons` keeps
  its chunk. A multimethod on `class` reaches a user type through `Object`
  and its interfaces.
- **Reading and the loader.** `eval-str` reads each form after the one before
  it ran, so a `::kw` after an `ns` form belongs to that namespace.
  `read-string` takes its opts and `*data-readers*` on ClojureScript.
  `clojure.edn` reads `#inst` and `#uuid` on ClojureScript, and an empty input
  is "EOF while reading" on basilisp. `resolve`, `find-ns`, `ns-publics` and
  their kin find a `clojure.*` name in the namespace its stock library booted
  into. `require` takes prefix lists written as vectors. `in-ns` enters an
  existing namespace as it is.
- **Refs and bindings on ClojureScript and basilisp.** A write outside
  `dosync` is the JVM's IllegalStateException, and a transaction that throws
  puts back what it wrote. `bound-fn` conveys bindings on ClojureScript.
- **ClojureScript extend positions** now name the concrete collection
  classes, the seq interfaces, `IFn`, `Pattern`, `UUID`, `Date` and the
  exception types. Before, one unmapped class failed the whole
  `extend-protocol`.
- **Hints and contracts.** A `^CharSequence` or transient hint is checked as
  the JVM's checkcast is, against the class declaring the method called
  (`assoc!` of a persistent map, `clojure.string/blank?` of a number are
  ClassCastException; `clojure.string/upper-case` of a keyword calls
  `Object.toString` and needs no cast); metadata must be a map; `.hashCode` is
  Java's (String's, Long's, Character's, Boolean's; the host's own on the JVM
  and jolt); `String.replace` of nil is NullPointerException.
- **Macros and namespaces.** `macroexpand-1` returns the macro's own expansion
  (the codemod's rewrite is for the compiler, which expands the form again);
  an `ns` form's docstring and attr-map are the namespace's metadata (not on
  ClojureScript, whose namespaces carry none); `uri?` boots, and
  `namespace-munge` boots from `core_deftype.clj`'s own definition.
- **The adapters' remaining gaps.** basilisp's extend positions name the
  concrete collection classes, and `with-redefs` of a protocol method reaches
  its callers there (a host `defprotocol`'s vars get `^:redef`, as every stock
  def does). On ClojureScript `alter-var-root` inside a `binding` changes the
  root while `var-set` sets the binding (a `:var-set!` capability), a map is
  no var, and `intern` works.
- **A clean build.** cohost's own sources compile for ClojureScript without a
  warning (they had 28), and a `defprotocol` in a cohost namespace no longer
  warns that `*unchecked-if*` is undeclared (cljs.core's macro sets the
  analyzer's flag by its bare name; 12 of a run's 23 warnings). The 11 left
  in a run come from loading clojure.repl's JVM-only parts (`proxy`,
  `sun.misc.Signal`, `read`; below).
- **Corpus rows the JVM confirms.** With the corpus gap re-run on JVM Clojure
  (above), four more of its confirmed rows' causes were answered:
  `hash-combine` boots from `core_deftype.clj` (`Util/hash` is
  `Object.hashCode`, nil 0, and `Util/hashCombine` is boost's in int
  arithmetic; ClojureScript's own `hash-combine` takes a hash, so companion
  names with a plain-Clojure definition now boot it on every host);
  `->Eduction` and `default-data-readers` come from the host where it has
  them; and `empty` of a record throws UnsupportedOperationException, as
  defrecord's does (jolt's `.empty` member answered nil, and ClojureScript's
  records have no `empty`). Two battery cases pin them (131 → 133).

## What each host needed (the adapter's job)

- **jolt** keeps macro status on the Var (`.setMacro`), expands syntax-quote
  at read time, ships many `clojure.lang` statics of its own but not all, and
  its sorted collections' `.comparator` is not an `IFn`. Its namespaces do
  not import `java.lang`, so class names in extend positions are written in
  full. Its `/` as a function value throws on a zero double divisor (the shim
  divides by IEEE 754); its `log10(1000.0)` is 2.9999999999999996. Its
  `Matcher.start(g)` of a group that did not participate is `false` (the
  JVM's is -1); it has no `Character/getNumericValue` or `forDigit`; a reify
  class is named `clojure.lang.IObj$reify__N`. A member accessor compiled
  under a cohost namespace would use the stock `fn`, whose output the codemod
  rewrites back into a member call: the adapter compiles them with `fn*` in
  its own namespace.
- **JVM Clojure** is the reference: the jolt adapter's shape, plus
  `.isDynamic` (the runtime's own dynamic vars carry no `:dynamic` metadata).
- **basilisp**: `recur` in a variadic arity re-packs the rest argument (the
  codemod loops such arities); vars compile to direct references unless
  `^:redef`; an unbound var reads as an `Unbound` sentinel; `ns-name` is a
  string; `ex-info` takes no cause; there are no refs, sorted collections,
  `alias` function, chunked seqs or agents; chars are one-character strings;
  `:inline` metadata is honored at compile time (stripped); its `ns` refers
  all of `basilisp.core` (emptied); `extend-protocol` wants one clause per
  arity; Python's `math` raises where the JVM returns NaN or an infinity, its
  `cbrt(27.0)` is 3.0000000000000004 (one Newton step lands on 3.0), `ldexp`
  wants an integer exponent; its EDN reader consults `:readers` for
  namespaced tags only; its `defprotocol` takes the receiver only as a
  parameter named `this` or `self` (with any other name a two-arity method
  cannot be implemented in `reify` or `deftype`: the arity counts disagree),
  its type members are Python methods (above), and its `meta` reads a reify
  object's `meta` method as a property. A Var is dynamic by its own flag, not
  its metadata; Python has one int type, so a BigInt is an int a long cannot
  hold.
- **ClojureScript (self-hosted)** has no macro vars (a stock macro compiles
  into `cohost.core$macros`; inside it the analyzer rewrites `cohost.core/x`
  to `cohost.core$macros/x`, so every core definition is also a forwarding
  property there), no thread bindings (a frame saves and restores slots,
  and `get-thread-bindings` reads the frames), no run-time `extend` or
  namespace reflection (both from the analyzer state), `js/Error` for
  everything (the message decides the JVM class; the shim's own carry it),
  JVM interfaces that are its protocols in a `deftype` (the codemod moves
  each method to its protocol), keyword literals that are not identical,
  regexes without a start index (a `g` copy and `lastIndex`; group spans
  from the `d` flag), and no `printf` or `format` of its own (core.clj's
  boot over the shim's Formatter). cljs.core's own
  `deftype` / `defrecord` write `this-as` and the protocols a record
  implements unqualified, so cohost's namespaces keep those names referred;
  `(var x)` is a fresh object each time (`=`, not `identical?`); its
  `ns-unmap` is a compile-time macro (a `with-local-vars` var stays interned).

- **ClojureWasm** (cljw v1.15.4, Zig) names clojure.lang and java.* classes of its
  own, with the JVM's exception hierarchy, so its adapter is the jolt adapter's
  shape. Each fact below was measured on the binary and found in cljw's source.
  - **Reading.** It has no `clojure.core/read` over a stream. The adapter finds each
    top-level form's extent with a scan over a vector of the chars, then reads that
    text with `read-string`. A vector, because `nth` of a cljw string walks it.
  - **Macros.** A var is a macro only by `defmacro`. `^:macro` on a def is ignored,
    there is no `.setMacro`, and a later plain `def` clears the bit. `defmacro` sets
    the bit at analysis time, on the var it interns, before analysing the macro's
    fn. So the adapter marks a var by evaluating a `defmacro` of its name whose
    body cannot be analysed, which leaves the root alone.
  - **Var metadata** says `:macro` for both the bit and a def's `^:macro`. In
    cohost's namespaces the adapter therefore tracks the roots it marked.
  - **Dynamic vars.** A var becomes dynamic by `(def ^:dynamic x)` with no value.
  - **`fn*`** takes no name: the codemod binds the name with `letfn*`
    (`:named-fn*? false`).
  - **Macro expansions.** Its analyzer takes no array seq back as code, and `apply`
    makes a fn's rest args one. Every rewritten expansion is rebuilt with lists,
    metadata included, and a Var object becomes `(var ...)` (`:code-form`).
  - **Fns carry no metadata**, so the shim keeps its stubs and stock multimethods
    by identity.
  - **Namespaces.** `set!` of `*ns*` under a binding moves `*ns*` but not
    `read-string`'s `::` resolution; `in-ns` moves both, and it takes the name
    unevaluated.
  - **Types.** Its `deftype`/`reify` turn a clojure.lang interface into protocols
    they name bare, so cohost's namespaces keep those 28 names referred. Its
    protocol dispatch reaches a map, a set or a number only through its own types
    (`clojure.lang.IPersistentMap`, `APersistentSet`, `Long` ...), never
    `java.util.Map` or `java.lang.Number`: `:host-type` translates. It has no `Inst`.
  - **Exceptions.** Class names print without their package and `(Exception.)` has
    the message `""`; the adapter answers as the JVM does (`:exception-string`,
    with `str` booted stock).
  - **Regexes.** `Matcher.find` takes no start, so a search walks forward on one
    matcher.
  - **Math.** Its `Math/round` gives 1 for `0.49999999999999994`; the shim's
    serves.
  - **Errors.** Its "not implemented" error cannot be caught. Interop the shim
    lacks therefore stays the shim's (no passthrough), and a Thread is never
    started: one corrupts its runtime ("incorrect alignment" at a later
    collection).

- **Phel** (v0.54.0, PHP 8.5) is a Lisp that compiles to PHP; its runtime is not
  Clojure's, so its adapter builds the contract from Phel's compiler and registry.
  Each fact below was measured on the phar and found in Phel's source.
  - **Namespaces** are names. The compiler's current one is GlobalEnvironment's
    (`*ns*` is no dynamic var: the adapter sets phel.core's and cohost.core's
    copy), and `ns` takes no docstring (cohost's own namespaces carry theirs as
    comments). phel.core is visible from every namespace, so a cohost namespace
    sees a host name until a stock definition shadows it. `ns-publics` and
    `ns-interns` decode each name as a namespace name (`_` becomes `-`): the
    adapter lists the registry's names itself. It has no `all-ns`, `the-ns` or
    `ns-name`; its `resolve` and `intern` answer a symbol, not a var.
  - **Special forms** are `fn`, `let`, `loop`, `defonce` (and `letfn` is a
    macro). The codemod renames `fn*`, `let*`, `loop*`, `letfn*` in its output
    (`:special-forms`), and a call of a stock macro of one of those names is
    written qualified, which no special form is. A special form wins over a var
    of its name. Phel's `defonce` is no expression, and `(def x)` sets nil: the
    adapter writes `(def x)` so a var keeps an unbound root.
  - **Rest args** are a vector: each variadic arity makes it a seq first
    (`:rest-args :vector`), and `cons` onto a non-seq returns a vector, so the
    shim's `RT-cons` takes its seq.
  - **Metadata.** `def` evaluates its name's metadata, and the compiler reads
    `:tag` as a PHP type and applies `:inline`: both are stripped, and the codemod
    applies the inline fns (`:inline-meta :strip`, `:rewrite-inline? true`). A
    var's metadata is its definition's in the registry, which `def` replaces;
    phel.core's `alter-meta!` installs an override the compiler never reads and no
    later `def` replaces, so cohost writes the registry's. A closure (a fn that
    captures) is a PHP Closure and carries no metadata.
  - **Macros.** A var is a macro when its registry metadata says `:macro`. Phel
    resolves no qualified name of a private definition outside a macro's
    expansion. A def may not redefine a name outside its interactive mode, which
    the adapter turns on (core.clj defines `let`, `fn`, `defn` twice). Phel's
    own macros expand to bare phel.core heads (`and`, `or`, `case`): an
    expansion taken from one is qualified before it reaches cohost's namespaces.
  - **Reading.** Phel's syntax-quote qualifies a symbol only when it resolves,
    to where it resolves, and leaves the rest bare; the adapter marks each `` ` ``
    in the parse tree and expands it as the JVM reader does. Its reader takes
    `08` as 8 (the JVM's is no number), reads `#"..."` as a PCRE string, has no
    `:readers`/`:default` for tags, and reads `{:a 1 :a 2}` with a
    `ReaderException`: the adapter's reader works on the parse tree for all four.
  - **Macro expansions.** Its analyzer takes a seq as a call only when it is a
    list, and its emitter writes a vector literal only when it is a
    PersistentVector (stock `sigs` makes a macro's `:arglists` with `subvec`),
    and no object as a literal. Every rewritten expansion is rebuilt with lists
    and vectors, metadata included, and a Pattern becomes a call that makes it
    (`:code-form`).
  - **The self-call shortcut.** Phel compiles a call of `f` inside
    `(def f (fn ...))` as a call of `$this`; in a fn of several arity clauses the
    nested fns are classes of their own, and the shortcut then makes one call
    itself. One clause is written `(fn [x] ...)`, and a def of several is given
    its fn through `(do ...)`, which turns the shortcut off.
  - **Equality.** `identical?` of two symbols is their equality.
  - **Maps.** `reduce`, `filter`, `keep`, `map` and `some` over a map see its
    values; its `seq` is the entries.
  - **Strings.** `class` of a string is the PHP class that string names; `count`
    and `subs` count characters, `get` bytes. `format` is PHP's `sprintf`
    (core.clj's boots over the shim's Formatter).
  - **Numbers.** PHP prints a float with 14 significant digits by default
    (`precision`); compiled float literals are written with it. The adapter and
    `phel-config.php` set `precision` to -1 (shortest round trip), and Phel's
    compiled cache must not hold code compiled before that.
  - **Types.** `deftype` and `defrecord` are `defstruct`s: a struct is a map of
    its fields to Phel (no other key: `assoc` of one makes a map), no `map?`,
    and its name is its constructor fn, not a class (phel.core's type registry
    maps the fn to the class; the shim asks the adapter, `:class-instance?`).
    A reify is an object of an anonymous class whose methods are PHP methods, one
    arity each: the adapter's `reify` keeps every arity of a method in one fn the
    object holds, and its protocol tables dispatch to it.
  - **Protocols** are maps whose methods dispatch through a table each, keyed by
    type (`:string`, `:int`, ... a class name). `defprotocol` takes no docstring
    or options; there is no `extend`; `extend-type` takes one clause per arity
    (`:protocol-impl-style :per-arity`).
  - **Regexes.** A regex literal is a PCRE string with `/` delimiters. The
    adapter's regex is a Pattern of the JVM source (`phel_host.php`): PCRE in
    UTF-8 mode, its byte offsets turned into characters.
  - **Math.** PHP has no `cbrt`, `ulp`, `nextUp`, `getExponent`, `scalb`,
    `copySign` or `IEEEremainder`; `phel_host.php` computes them over a double's
    bits. `pow` is PHP's `fpow` (IEEE 754).
  - **Arity** is checked when a call is compiled.
  - **Chars** are one-character strings.
  - **The C stack.** Phel's own `count` of a 100,000-item lazy pipeline, and its
    `sort`, `frequencies` and `distinct` at the bench's sizes, recurse past PHP's
    default 8 MB C stack and segfault, which nothing catches; at 10,000 items they
    run. `bb bench:phel` runs Phel with a 64 MB stack; the boot and the batteries
    need none.
  - **Loading.** Under `phel run`, an `ns` form that is evaluated loads nothing it
    requires; `dev/phel_main.phel` loads `phel.string` (Phel's `clojure.string`)
    for the bench's host side.

## Core battery cases that fail, and why (host facts)

- `(= 1 1.0)` is `true` on ClojureScript (one number type) and on basilisp
  (Python `1 == 1.0`).
- `Matcher.start(g)` / `end(g)` on ClojureWasm: its `java.util.regex.Matcher` has
  `find`, `group`, `start` and `end` for the whole match only (`Matcher.zig`), and
  no group positions. The row "Matcher group spans" fails with the shim's "not
  available on this host".
- `alter-var-root` of a var the thread binds, on ClojureWasm: cljw's
  `alter-var-root` applies the fn to the binding's value (`core.zig:1767`,
  `v.deref()`), not the root's. The root cannot be read there:
  - a Var has no members;
  - a Thread started to read it corrupts the runtime;
  - the binding frame does not pop from the adapter.

  The row "alter-var-root inside binding changes the root" gets `[1 11]` for
  `[1 10]`.
- `&env` inside a user macro is ClojureScript's analyzer env, not the JVM's
  locals map. basilisp passes a locals map, but `&env` first: cohost hands a
  stock or user macro `&form` and `&env` in core.clj's order.

## The design that fell out

- **One codemod, applied twice.** Interop is rewritten in the source form
  before eval and in every stock macro's output. The rewrite is positional:
  `(.m target args)` → `(-m target args)`, so `->`, `->>` and `doto` thread
  into rewritten forms exactly as into the originals.
- **Class tokens are symbols.** `(class x)` returns
  `'clojure.lang.PersistentVector`; `instance?` and `isa?` consult a
  hierarchy table and host predicates, and for an object that is not the
  host's own data (a `reify`), the host's own class or protocol; `catch`
  clauses become one host catch with a class test chain.
- **Host facade names are the host's**, interned as copies; dynamic ones are
  referred (var identity matters for `binding`).
- **Libraries keep their own structure.** The loader is the JVM's in shape:
  a namespace per library, its `ns` form honored, load-time bindings of
  `*warn-on-reflection*` and `*unchecked-math*`, one load per library, a
  failing form recorded and skipped.
- **The adapter contract** (`cohost.adapter`): everything the library needs
  from a host, as data, with generic defaults. Each capability added came
  from a measured host fact.

## Deliberately not done here

- `core_deftype.clj` and `core/protocols.clj` are vendored but not booted:
  their `deftype` and `defprotocol` generate JVM interfaces and bytecode, so
  the host's are used. Chunked seqs: the host's own seqs are not chunked
  (`chunked-seq?` is false for them; results are the same), though a seq
  `chunk-cons` builds keeps its chunk. `core_print.clj` (printing is the
  host's).
- In clojure.repl, `source` (it reads class-loader resources) and
  `set-break-handler!` (`sun.misc.Signal`) are the JVM's; they fail to load
  elsewhere and are recorded as load errors.
- Every name prints under `cohost.*` (`#'cohost.core/map`, and a
  syntax-quoted symbol is `cohost.core/=`); keywords keep `clojure.*`.
- Class tokens are symbols: `(str (class ""))` is `"java.lang.String"`, not
  `"class java.lang.String"`; seqs are the host's (`(class (range 5))` is
  `clojure.lang.Cons`).
- Type hints are dropped: they are JVM-compiler directives, so a `^long`
  parameter or return does not coerce or saturate as it does on the JVM.
- `(instance? clojure.lang.IReduce x)` and `IReduceInit` are true for vectors
  and lists, and ask the host about anything else: a range is one on the JVM
  and jolt, not on ClojureScript or basilisp.
- On ClojureScript: `seq` of a type that implements only `Iterable` fails
  (cljs.core's `seq` needs `ISeqable`), as does a 2-arity `reduce` over a type
  that implements only `IReduceInit`; `str` of a regex is the host's
  `"/a|b/"` (`.toString` and `.pattern` give the source); the analyzer cuts
  the `:arglists` of a defn with three or more arities to two; binding a var
  that is not dynamic does not throw; a namespace carries no metadata.
- On ClojureScript and basilisp a watch on a ref fires at each write in a
  transaction, not once at the commit.
- On basilisp: Python's regexes have no `(?<name>...)` groups; its maps hash,
  so `array-map` does not keep order; a deftype's `Comparable`, `Seqable` and
  `IPending` methods do not yet become Python's protocols, and `ILookup`'s two
  `valAt` arities merge into one member that cannot tell `(get x k)` from
  `(get x k nil)`; its own `read-string` ignores `:eof` and `:read-cond`, and
  its EDN reader's `#inst` wins over a user reader for that tag.
- jolt's own `read-string` ignores `:read-cond`.
- On every host a division by zero says `Divide by zero` where the JVM
  compiles `(quot 7 0)` to a primitive long division that says `/ by zero`.
- On jolt, calling nil is a ClassCastException (`class nil cannot be cast to
  class clojure.lang.IFn`) where the JVM's is a NullPointerException, so
  stock `clojure.string/replace` with a regex and a nil replacement throws
  the former.
- A vector is not an `IDrop` here, so `drop` takes core.clj's general path:
  a count past the long range drops everything, where the JVM's `IDrop` path
  casts it and throws.
- `java.util.ArrayList` / `LinkedList` made by user code are the host's own
  on the JVM and jolt; on ClojureScript and basilisp they are the shim's
  stand-in, which serves core.clj's own use but is no collection to `count`
  or `seq`.
- `load-string`, `load-file`, `load-reader` and a `require` that is not at
  the top level go to the host's loader, not cohost's: the code they load
  sees the host's core.
- Everything in `ROADMAP.md`.

## Reproduce

```
bb boot:jolt   bb boot:lpy   bb boot:cljs   bb boot:browser   bb boot:jvm
bb corpus:jolt path/to/corpus.edn
bb corpus:jvm
bb eval:jolt "(require '[clojure.data :as d])" '(d/diff {:a 1} {:a 2})'
```
from the repository root, with the host on PATH (`jolt` v0.8.23 prebuilt,
`uv tool install basilisp==0.5.1`, the `clojure` CLI for the JVM and
ClojureScript legs, Node 24, Playwright for Python with a Chromium for the
browser leg).
