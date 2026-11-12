# Champaca fixes from the jolt :clj body lane — for the champaca/jolt authors

Collected 2026-11-11 by the jolt-clj-body lane (Toby pickup). These are the
fixes this lane produced against champaca at
`44ad7c4cd817612240afeb86f47119555cfbb27f` while building cohost's no-JVM
`:clj` body (jolt + champaca). Nothing here has been posted upstream; the
operator will point the authors at this list. Diffs live in this repo at the
paths named below (`docs/clojure-runtime/multi-bodied/jolt-spike/…`).

## 1. Per-loader class identity — fixes the `deftype` LinkageError (jolt #455)

- **Diff:** `jolt-spike/harden/per-loader-identity.diff`
  (banked revision sha256 `fd7446d133fde1a30cdb48233d3255d20fac17b27905ac77a511f7cd59cc9314`;
  a later battery-v5 revision exists — see Status).
- **Problem:** champaca registered runtime-defined classes by binary name
  alone, process-wide. Clojure's compiler defines the same names
  (`compile__stub…`, every `deftype`) through a fresh DynamicClassLoader per
  eval, so the second definition was rejected as a duplicate.
- **Fix:** index dynamic definitions by (defining loader, binary name);
  allocate an internal name only on collision while retaining Java's binary
  name; loader-qualify resolution, reflection, MethodHandle and error-cache
  paths so one loader's classes, mirrors, statics and failures never leak
  into another's. Full write-up: `jolt-spike/harden/PR.md`.
- **Validation (banked revision):** focused checks 6/6 (ModuleCheck,
  ClassDefCheck, DefineCheck, LoaderIdentityCheck, LoaderBoundaryCheck,
  LoaderErrorCacheCheck) with JDK 25 oracle outputs byte-identical;
  library probes 9/9 incl. ClojureDeftype (same deftype evaled/constructed
  3×, distinct Class identities); no-JVM bundle stages all exit 0.
  Full suite at this revision: 248/249 cold — the one failure is
  SocketCheck, an environmental DNS assumption (`no.such.host.invalid`
  resolves on the test box), pre-existing on the unpatched base too.
- **Status:** battery v5 on the grok box reached 252/253 cold (385 PASS
  rows; the +1 is the new LoaderAssignabilityCheck, item 2's fix folded
  in) before the lane ended; that final diff was not banked. A
  v5-equivalent is being re-derived and will replace the banked diff here
  when validated.

## 2. Loader-qualified assignability (`instanceof` / `isAssignableFrom`)

- **Where:** gap in fix 1, found by the conformance run (`protocols`).
- **Problem:** `jinstance?` / `class-assignable?` still compared classes by
  bare name. After a protocol is redefined under a new loader, an instance
  of the old definition is not recognized against the new one (and vice
  versa), so `find-protocol-impl` misses. Detail + repro:
  `jolt-spike/conformance/DIFFS.md`, `protocols` section.
- **Status:** fixed in battery v5 (LoaderAssignabilityCheck passes);
  re-derivation in progress with fix 1 (same unbanked-diff situation).

## 3. Reject duplicate methods in a class file (ClassFormatError)

- **Diff:** `jolt-spike/conformance/patches/dup-method-classformat.diff`
  (sha256 `4b96262253351309871cae896f5fc26ec3e7b6b3956d29864e61208be2f2cc0f`).
- **Problem:** champaca's class-file reader accepted a class defining the
  same method name+descriptor twice; HotSpot throws ClassFormatError.
  Surfaced by `clojure.test-clojure.protocols` (`reify` with `size` from
  both `java.util.List` and `java.util.Collection` must throw; champaca's
  eval succeeded).
- **Fix:** `reject-duplicate-methods` in `src/champaca/classfile.clj`,
  message format matching HotSpot's as_klass_external_name wording.
- **Status:** draft patch, standalone from fix 1; not yet battery-validated
  as part of the combined patch.

## 4. `Thread.start0` must throw on EAGAIN, not hang

- **Diff:** `jolt-spike/conformance/patches/thread-start0-eagain.diff`
  (sha256 `ace66bb1a56b9bf7283a13748043110de95455bd64b4b18e831689d838255945`).
- **Problem:** when the OS refuses a new thread (pthread_create EAGAIN via
  Chez `fork-thread`), champaca hung; HotSpot throws. Surfaced by
  `clojure.test-clojure.delays` (the conformance run's one TIMEOUT).
  Bookkeeping was also left inconsistent (thread listed/marked alive,
  started-count high).
- **Fix:** in `resources/champaca/natives.ss`: recognize the fork-thread
  resource error, roll back start0's bookkeeping, and throw the way
  HotSpot does.
- **Status:** draft patch, standalone; the thread-leak check noted in
  DIFFS.md is still open.

## Documented follow-ups (deliberately NOT in the fixes above)

From `jolt-spike/harden/PR.md` — scoped out, with runnable repros against
the final source in the harden packet:

1. **Deferred loader constraints.** Fix 1 rejects already-defined override
   constraint violations at definition time. It does not keep a persistent
   JVMS 5.3/5.4 constraint graph for unresolved types; a late definition
   HotSpot rejects can still be accepted. A complete fix needs an
   initiating-loader constraint graph with transactional checks.
2. **First resolution of an as-yet-undefined child-first dependency.** If
   a custom loader defines a class whose not-yet-defined dependency also
   has classpath bytes, the host linker's global normalization can load
   the application copy before asking the custom loader; HotSpot returns
   the custom loader's class. A general fix needs lazy, loader-aware host
   class normalization (the eager constant-pool approach was tried and
   removed — it broke DefineCheck).
3. **Review risks:** full invokedynamic/bootstrap alias coverage,
   concurrent definition races, adversarial names resembling internal
   aliases, CodeSource/manifest behavior of aliased classpath definitions.

## Not upstream bugs (for completeness)

Three conformance diffs (`errors`, `fn`, `ns-libs`) were harness artifacts
in this lane's own runner — it never preloaded `clojure.test-helper`
(and `clojure.set` for `ns-libs`) per namespace; HotSpot fails identically
without the preload. Fixed in the lane's runner, not in champaca.
