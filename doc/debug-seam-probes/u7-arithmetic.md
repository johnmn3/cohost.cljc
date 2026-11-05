# U7 (row #15, check #10): integer divide by zero -- analysis

Question: through the seam on jank, do `(/ 5 0)` and `(quot 5 0)` throw
a catchable ArithmeticException (JVM parity, exit 1 under the CLI's
report path), or reach native integer division (SIGFPE, exit 136)?

## Verdict from source: the guard already exists, at three layers

1. **The shim's Numbers wrappers** (src/cohost/shim.cljc). Stock core's
   `/`, `quot`, `rem` and `mod` bodies call `clojure.lang.Numbers`
   statics; the seam's are `Numbers-divide`, `Numbers-quotient`,
   `Numbers-remainder`, each `(if (zero? y) (throw (divide-by-zero))
   ...)` where `divide-by-zero` is
   `(host/ex* 'java.lang.ArithmeticException "Divide by zero")`. The
   shim's own comment states why the wrappers stay: "jank's own die
   with SIGFPE on a zero divisor where the JVM throws
   ArithmeticException." A float operand takes the IEEE branch
   (Infinity/NaN) instead, matching the JVM.

2. **The codemod's Numbers-divide inline** (jank adapter,
   `:static-expansions`): rewrites a visible `Numbers/divide` call to
   `(if (zero? b) (cohost.shim/Numbers-divide a b) <fast path>)` -- a
   zero divisor keeps the guarded wrapper; only nonzero divisors take
   `num-divide` / jank's own `/`. (Jank's own double `0.0/0` produces a
   wrong-signed NaN that segfaults when boxed -- also routed to the
   wrapper by the same branch.)

3. **The adapter's i64 helpers** (`cohost_num::div/quot/rem` in
   cpp/raw): return nil for a zero divisor and never divide; their
   callers (`num-divide` etc.) are documented "for a nonzero divisor"
   and are only reached behind layers 1-2.

`mod` by zero goes to the stock mod (the adapter's mod-expansion
excludes a zero divisor from its fast path), whose remainder is layer
1. `unchecked-divide-int` / `unchecked-remainder-int` in the shim call
core `quot` / `rem`, so they inherit layer 1 (on the JVM the
unchecked ops throw on a zero divisor too -- "unchecked" waives
overflow checks, not the zero check).

## Routes that remain UB (jank's, out of scope)

- Arithmetic written directly in C++ interop (`cpp/raw` bodies,
  `cpp/` calls into C++ operators): the user's own C++; a zero
  divisor there is a native SIGFPE, exactly as in a plain jank
  program. Cohost cannot guard code it does not rewrite.
- Jank's own `/` used as a value outside the seam's core (not
  reachable from booted code: `/` as a value is the stock fn).

## Empirical status

Runner check #10 (both spellings, exit exactly 1, "Divide by zero"
in the output) PASSES on the JVM target (bb debug-seam:check jvm,
2026-11-04). The jank run of the same check is pending B1 + the
local snapshot environment (see u3-diagnosis.md, "Environment
blockers"): the prediction from this analysis is PASS with no code
change. If the jank probe surprises -- a route that reaches the
native op -- the fix belongs at layer 1 or 2 above, never in jank.
