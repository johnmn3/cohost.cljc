# INSTALL DISENTANGLE — FINAL REPORT (Lane M, lane-m/cohost-disentangle)

Base: `lane-m/cohost-install-portable` @ 92ad3e90 (four proven
seats: jank, bb, clj, jolt; snapshot key
`df88adacda69c0e2d4ac53df68476d9d5b6ea40d7dbac44bb5d9c1bbc87bde39`).
All heavy proofs on j.slaa.us via heavy-queue, staged under
/home/john/j/cohost-disentangle; logs cited per proof.

## The seat table — five first-class installer seats

| seat | driver | snapshot key | battery | notes |
|---|---|---|---|---|
| jank | `jank run install.cljc -- --repo` | df88adac… | via A-class installs | proof B re-run (proofs2) |
| bb | `bb install.cljc` | df88adac… | 219/221 + 62/62 | proof A (inherited chain) |
| clj | `clojure -M install.cljc` (vendored shim) | df88adac… | 219/221 + 62/62 | proof D (inherited chain) |
| jolt | `jolt run install.cljc` (+ crypto pin) | df88adac… | 219/221 + 62/62 | portable lane; emit caveat below |
| **ClojureCLR** | `Clojure.Main install.cljc` | **df88adac…** | **219/221 + 62/62** | proofs4, this lane |

(The 2 stock battery fails are the standing \u-escape cases
that fail identically under the original bb flow — the lane's
established exact bar on every seat.)

## P1 — the key is driver-independent; the bb coupling was availability

`snapshot-key` = sha256hex(sha256sum-listing(sorted input
files) + "jank " + jank-version + "\n"). Keyed inputs: the
resources/cohost/clojure-1.12.0 and src/cohost trees plus six
named dev/perf scripts. The key commits to input file BYTES +
repo-relative path strings + the jank binary's version line —
never to the driver, the emitted bytes, mtimes, hostname, or
cwd. The only bb couplings were availability, not parity:
(a) rebuild! shells the literal "bb" for its emit step (every
non-jank seat's cold build uses bb for emit; the fresh path
never reaches it); (b) the build files required the real
babashka.fs/babashka.process libraries — P2's subject.

## P2 — vendored shim (predecessor, verified by proofs A/D)

The used fs/process slice is vendored at dev/perf/shim/
babashka/{fs,process}.cljc (:clj + :cljr arms), the clj seat
runs `-Sdeps {:paths ["dev/perf/shim"]}` with the Maven pins
dropped, and proofs A (bb reference) and D (clj on the shim)
both cold-built df88adac… with byte-identical emitted trees
and 219/221 + 62/62 batteries. Proof C (standalone emit):
bb/clj emitters byte-identical; the JOLT emitter differs —
it prints reader-attached source meta on :arglists vectors
(79 KB diff, one shape). Content-equivalence unaffected; any
fix must live outside the keyed emitter file.

## The CLR gap, closed — five walls, in load order

1. **dev/perf/sha256.clj used java.security.MessageDigest**
   (proof E's wall). Rewritten with runtime digest dispatch:
   try-eval picks the host's form — JVM arm is the original
   streaming code; CLR arm = SHA256/Create + Encoding/UTF8.
   hex via string accumulation (no StringBuilder; .NET spells
   it Append — the CLR probe caught that defect class in the
   shim earlier). sha256.clj is NOT keyed; the key did not move.
2. **(catch Throwable …)** — ClojureCLR resolves no Throwable
   classname. Changed to Exception in the dispatch guard;
   every guarded failure is an Exception on both hosts.
3. **(System/getenv "JANK_BIN") in the KEYED build files** is
   a compile wall under CLR (bare System is not a type: "No
   such namespace: System") — top-level in jank_snapshot_
   key.clj and jank_aot_ensure.clj, plus (System/nanoTime) in
   rebuild!. Keyed files cannot change, so the new host
   adapter **dev/perf/clr_compat.clj** (loaded only by the CLR
   driver, before the chain) creates the System namespace and
   interns getenv (→ System.Environment/GetEnvironment-
   Variable) and nanoTime (Stopwatch ticks scaled to ns).
   Dotted type forms still resolve as types with the shim
   namespace present (probed). The jank seat never loads the
   adapter; the JVM seats never load it either.
4. **Comparator divergence — the key itself.** CLR computed
   d8f072c3… for the same tree: ClojureCLR's string compare
   is System.String.CompareTo, CULTURE-sensitive, and the
   keyed input-files sorts by path string (listing diff =
   same (hex, path) pairs, different order). clr_compat
   rebinds clojure.core/compare for the build process:
   strings via System.String/CompareOrdinal, everything else
   delegates to stock. After: CLR snapshot-key = df88adac…,
   re-verified on bb and clj after every shared-code edit.
5. **My own :cljr shell arms** wrapped the sh -c payload in
   the shell single-quote quoter; .NET's Arguments parser
   groups on double quotes only. Fixed with a dq wrapper
   (escape \ and "), probed byte-exact on 8 command shapes
   (quotes, $-expansion, backslashes, nested sh -c) before
   banking (edcb823).

install.cljc's :cljr arms are if-chain dispatches (the case*
ban stands): capture/sh! via ProcessStartInfo; env via
GetEnvironmentVariable; self-exe via Environment/ProcessPath;
the banner uses -e "(print (clojure-version))"
(Clojure.Main --version loads a file named "--version");
step 2 shells the ensure chain with the vendored shim on
CLOJURE_LOAD_PATH and clr_compat loaded first.

## CLR proofs (proofs4.log, claim 2026-11-08T02:23Z)

- (a) Banner "cohost install: cljr 1.12.6"; install.edn
  {:seat :cljr, :cljr "/home/john/ds11/clrtools/Clojure.Main"}.
- (b) Cold build in a fresh clone: SNAPSHOT-BUILT
  df88adacda69c0e2d4… in 935 s, installer RC=0. Emitted tree
  and events byte-IDENTICAL to the bb reference after
  checkout-path normalization (0 differing files).
- (c) `cohost -Ttools install-latest :lib
  io.github.seancorfield/deps-new :as new` → "new: Installed
  io.github.seancorfield/deps-new v0.17.0 {:git/tag
  \"v0.17.0\", :git/sha \"657fca6\"}"; re-run → "new:
  Skipping, newest installed io.github.seancorfield/deps-new
  v0.17.0".
- (d) `cohost -e "(+ 1 2)"` → 3; battery on the CLR-built
  snapshot (SNAPSHOT-FRESH): 219/221 + 62/62, RC=0.
- (e) Fresh HOME with bb/clojure/jolt poisoned off PATH:
  SNAPSHOT-FRESH df88adac…, installer RC=0, -e → 3.
- (f) README names ClojureCLR as a seat (list, invocation
  block, seat paragraph). This supersedes the "ClojureCLR:
  NOT A SEAT" verdict in INSTALL-PORTABLE-REPORT-2.md — its
  named missing facility (a ClojureCLR-loadable form of the
  driver libraries) is exactly what the vendored shim +
  adapter now supply.

## Proof B (jank seat) — re-run verdict

The inherited chain's B died on an invocation error (missing
"--"). Re-run (proofs2): RC=0, stamp df88adac…. Events
byte-identical to A's after checkout-path normalization;
emitted src equivalent modulo reader-metadata / map print
order (jank's own emitter; ~20 lines across 14 files, all
carrying :jank/source or map order). Emit byte-parity today:
bb = clj = clr drivers identical; jolt differs in :arglists
meta; jank differs in metadata/map print order. None of
these touch the key or content equivalence.

## Discipline kept

The four proven seats' behavior and the key never moved:
every shared-code change (sha256 dispatch, catch Exception)
re-verified df88adac… under bb and clj before banking, and
the CLR key was verified equal before the cold-build proof
was attempted. No keyed file was edited.
