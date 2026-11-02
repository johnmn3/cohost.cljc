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
| :prim-kind-test | | | | | | | |
| :defn-fn-name? | N/A | ON | ON | N/A | N/A | N/A | N/A |
| :rewrite-inline? | N/A | ON | ON | ON | ON | ON | ON |
| tag type tests (M-4.1) | | | | | | | |
| bound RT arms (M-4.2/3) | | | | | | | |
| :native-hints (H1) | | | | | | | |
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
