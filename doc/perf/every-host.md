# Every-host gains (M-10)

The speed gains that landed on jank first, decided host by host. A row
is ON (the host has the cost the gain removes; the gain is wired and
measured before/after), N/A (the host has no such cost; the reason is
stated), or NOT-YET (the blocker, with the error). Hosts: jvm, jolt,
lpy (basilisp), cljs, cljw, phel, lg (let-go). Jank's column is the
reference state.

| gain | jvm | jolt | lpy | cljs | cljw | phel | lg |
|---|---|---|---|---|---|---|---|
| :syntax-quote-source? | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| :cohost-inst? | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| :prim-kind-test | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| :defn-fn-name? | N/A | ON | ON | N/A | N/A | N/A | N/A |
| :rewrite-inline? | N/A | ON | ON | ON | ON | ON | ON |
| tag type tests (M-4.1) | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| bound RT arms (M-4.2/3) | ON | ON | ON | ON | ON | ON | ON |
| :native-hints (H1) | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| Numbers-minus as `-` | ON | ON | ON | ON | ON | ON | ON |

## The rows

- **:cohost-inst?** -- the bare tag test for a cohost type instance.
  Only jank is a :cohost-types host (the adapter contract says so:
  deftype/defrecord/defprotocol are the adapter's own macros there
  and dispatch keys off descriptors). On every other host a user type
  is a host type and the host's own ops take it; there is no
  descriptor to test for. N/A x6, by the contract, not by probing.

- **Numbers-minus as `-`** -- ON everywhere. The wrapper was a pure
  forwarder on every host (it checked nothing anywhere; the `-` in
  its body was already the host's), so the shim static is the twin
  itself and host-aliases fires on all hosts. Verified by boot on all
  eight (this tree): jvm 145/146, jolt 146/146, lpy 144/146, cljs
  144/146, cljw 144/146, phel 145/146, lg 144/146, jank 144/146 --
  every host at its pre-change standing, libraries 61/61 on all.

- **:defn-fn-name?** -- jolt ON: an interleaved in-process A/B
  (200 x fib 25, seven pairs, one booted process) totals 11,882 ms
  for var-mediated self-recursion against 4,205 ms on the fn's name
  (x2.83, every pair agreeing). boot:jolt stays 146/146 with the cap
  on -- jolt's named fn* still sees a redefinition, so there is no
  battery price. The JVM is N/A: it is the reference host -- the
  battery's redefinition expectation IS its semantics, and its JIT
  makes a var deref on a hot self-call free.

  The same A/B on the other hosts (the unnamed arm's emission
  verified in each session): cljs x0.996 (V8's inline caches make
  the namespace-property call as fast as the named local), cljw
  x0.99, phel x0.88 -- no cost to remove, cap stays off. On let-go
  the only name shape is the letfn* binding, and it measures x6.3
  SLOWER than the var (3,140 ms against 19,830 ms, every pair
  agreeing): let-go's letfn* cell costs more per call than its var
  deref, so the gain does not exist there in any shape.

  Basilisp ON, smaller: the clean interleaved A/B (20 x fib 20,
  five pairs) totals 227,988 ms through the var against 210,936 ms
  on the name (x1.08, the name winning four pairs of five) -- its
  var deref is a smaller share of a Python-level call than of a
  Chez-compiled one. The bench:programs pair at fib:25 (18,362.9
  before; 19,788.2 after with a min of 11,674) was contention-tainted
  past reading and decided nothing; the A/B did.

- **:syntax-quote-source?** -- N/A on all six. The capability exists
  for one reader only: jank's, which wraps a syntax-quoted form in a
  `(with-meta X {:jank/source ...})` map the codemod must strip or
  the boot breaks (that breakage is what M-4 item 4 fixed). Probed
  raw, the other readers answer a plain list for `(type \`(a b))`:
  jolt a PersistentList, basilisp a Cons, let-go a PersistentList,
  ClojureWasm a Cons. For the JVM, cljs and phel the evidence is the
  boot itself: each compiles the full stock core -- syntax-quote in
  nearly every macro -- with the capability unset and stands green;
  a source-map wrapper would break that boot the way it broke
  jank's. The JVM's syntax-quote attaches :line/:column metadata,
  which the codemod already carries; it is not a wrapper.

- **:rewrite-inline?** -- ON on all six, N/A on the JVM. The
  capability exists for hosts whose compiler ignores a var's :inline
  fn: the codemod then applies core.clj's own inline expansions at
  rewrite time, as the JVM compiler does on its own (adapter
  contract) -- which is exactly why the JVM needs nothing: its
  compiler honours :inline natively. Five hosts already carried it
  (jolt, basilisk, cljs, ClojureWasm, phel -- the adapter inventory,
  verified by grep); let-go was the only host missing it. Turned on
  there (M-10): bb boot:lg -> battery 144/146, libraries 61/61,
  EXIT=0, let-go's standing unchanged.

- **:prim-kind-test** -- jolt N/A, measured by isolation. Wired as
  `(instance? java.lang.Long sym)` / `(instance? java.lang.Double
  sym)`, the battery stayed 146/146 (the class test's extension is
  a subset of the shim test's, so no value that needs converting
  passes). But with :defn-fn-name? held on and the box quiet, the
  hinted programs read fib-hinted 219.5 ms with the shim test
  against 248.6 ms with the class test (tak-hinted 556.3 against
  562.1): jolt's instance? is a class check through its JVM
  emulation, not a tag read like jank's is_integer, and it costs
  what the shim's bound call costs. The cap stays off. (An earlier
  in-process A/B through the stock-compiled eval path read the
  class test x4.16 slower still; that path is not the one the cap
  takes, so the bench isolation is the number that counts.)

  Cljs N/A, by the same isolation: with the long arm wired as
  `(.isInteger js/Number sym)` -- a subset of cljs's long-kind?,
  which is cljs.core/integer? itself there (the host sets no
  :bigint?) -- bench:programs fib-hinted reads 370.5 ms against
  366.5 ms with the shim test, the ranges overlapping. On both
  hosts the bound shim test is already cheap; the hinted programs'
  remaining gap to unhinted lives in the coercion's shape, not in
  this test. Noted on cljs: the raw tak-hinted bench program is
  ABSENT there -- cljs's own compiler fails it with a TypeError
  before it times. That is a fact about the bench program through
  the host's own compiler; cohost's hinted path (codemod plus
  coercion) passes the battery's H1 rows on cljs.

- **Tag type tests (M-4 item 1)** -- N/A on all six, same root as
  :cohost-inst?. The tag test exists because on jank a cohost type
  instance otherwise pays a descriptor walk for every stock type
  predicate; the object_type tag answers in one C++ call. On the
  other hosts the shim's type predicates bind straight to the host
  core's own (vector? is the host's vector?), which is already the
  native test -- there is no second layer for a tag to shortcut.

- **Bound RT arms (M-4 items 2-3)** -- ON on all six, inherited.
  Item 3 (the hot capability lookups bound once at shim load) is a
  shared-layer change: cohost.shim binds h-cohost-inst? and the
  other hot caps once (shim.cljc, via host/capability) for every
  host; there is nothing per-host to wire. Item 2's one-call
  instance guard binds the :cohost-inst? capability, which only
  jank has -- the guard itself is the :cohost-inst? row above.

  The remaining hosts, decided: the JVM is the reference -- its
  compiler consumes the hints themselves (the :native-hints row)
  and JIT-inlines the shim test. ClojureWasm has no gap to close:
  bench:programs reads fib 9,764.9 ms against fib-hinted 8,526.9 ms
  -- hinted already beats unhinted through the coercion there. On
  phel the bench vehicle cannot isolate a hinted program at all:
  phel's own compiler takes ^long as a native PHP parameter type
  and the raw fib-hinted program dies with "Argument #1 ($n) must
  be of type long, int given" (cohost's own hinted path passes the
  H1 rows; it never presents a raw hint to phel's compiler). On
  let-go and basilisp there is no cheaper exact-kind op to emit:
  each shim test already binds the host core's own integer test
  (let-go has no class/tag op; basilisp's integer? is the one
  isinstance its core has), and the two hosts where an inline form
  existed both measured it a wash -- the test is not where the
  hinted cost lives.

- **:native-hints (H1)** -- N/A on all six, three of them measured
  by trial. The capability keeps a primitive tag on the emitted
  fn* parameter for a compiler that compiles tags as native types,
  instead of stripping the tag and coercing at entry. Trialled on
  the three compiler hosts that take tags at all -- each battery
  stayed at its standing (the tag's semantics are the JVM's cast
  semantics, which is what the coercion implements) and none was
  faster than the coercion on bench:programs fib-hinted: JVM
  100.2 ms native against 96.6 ms coerced (boot:jvm 145/146);
  jolt 385.3 against 219.5 (boot:jolt 146/146; even the native
  run's 256.2 ms minimum does not beat the coercion median);
  ClojureWasm 8,868.2 against 8,526.9 (boot:cljw 144/146). The
  coercion's fast path -- one kind test, then the value passes --
  is already at the floor these compilers reach; the programs'
  remaining cost is their body arithmetic. The other three hosts
  (cljs, basilisp, phel, let-go) have no native scalar type for a
  fn* parameter to carry: their compilers are dynamic, and phel
  shows the shape of forcing one (its compiler's own ^long type
  rejects an int argument). Jank remains the one host where the
  tags are the machine's own types and the coercion would be the
  foreign path.
