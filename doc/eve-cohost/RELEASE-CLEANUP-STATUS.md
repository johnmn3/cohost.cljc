# RELEASE-CLEANUP STATUS — the audit's named exceptions, cleared (Lane M, Nov 7)

Operator order (Nov 7 ~4:46 AM ET): clean up the release audit's named
exceptions (doc/eve-cohost/RELEASE-AUDIT.md on lane-m/cohost-release-audit,
verdict READY WITH NAMED EXCEPTIONS) and get the release ready. Branch
`lane-m/cohost-release-cleanup` off release tip 60353a0; main untouched;
the move to main stays the operator's word. All gate numbers below were
re-run by this lane on the build box (j.slaa.us), on this branch's tree,
exactly as printed. Gate runs: jank + cljs at tree 6fedd0e, JVM at
60b20d9 (the only delta between those trees is item 4's boot:jvm launch
line in bb.edn, which neither the jank nor the cljs gate reads).

Commits: 92e0574 (item 1), 6c859f5 (item 2), 40ec3a8 + 2ee922c + 6fedd0e
(item 3), 60b20d9 (item 4).

## Item 1 — the 10 unreferenced root scratch files: DELETED

Re-verified unreferenced firsthand before deleting: zero hits for all
ten names across the shipped configuration and code (bb.edn, deps.edn,
cljs-*.edn, bin/, src/). Remaining mentions are documentation prose and
comments. One caveat, stated plainly: the dev-harness runner
dev/u5/run-jvm-gates.sh executes two of them (eve_cohost_check.clj,
probe_memory.clj) by path; that script is a development harness, not
shipped config, and the files' contents remain in git history.
Deleted (commit 92e0574): probe_atom.clj, probe_collections.clj,
probe_memory.clj, probe_thread.clj, probe_tier.clj,
eve_cohost_check.clj, eve_cohost_check_u5.clj, eve_cohost_check_u7.clj,
eve_http_check.clj, LANDING-U4-STATUS.md.

## Item 2 — Agent 3's harness `.trim`: FIXED; leg re-run, 13/36 as printed

What was wrong: dev/eve_http_programs.cljc's header-parse helper used
the JVM member call `(.trim ^String s)`, which jank's analyzer rejects,
so unit 9's jank harness leg died before running (0/36).
What changed (commit 6c859f5): `media-type` rewritten member-call-free
using clojure.string only (trim, split on #";", lower-case, index-of
with nil-for-absent handled explicitly, starts-with?, ends-with?).
Behavior-identical to the old form on 15 Content-Type cases under JVM
string semantics (probe: the old body loaded from git under a renamed
ns, both run over the case set, outputs equal).
Re-run on the build box (clearing lane's shape: checkout + Agent 3's
harness overlay from ~/cohost-release-clear/cohost-u9 — dev files, not
this repo's tree — Eve sibling at the pin 76fd94c): the leg now RUNS.
First re-run (before item 3's read-forms fix): client process ran all
23 client programs, exit 0; serve process died reading the handler
source ("Unterminated map") — that was item 3's J1 defect, below.
Final re-run on the final tree, as printed:
  unit 9 gate (jank seat): 13/36 PASS; JVM-only client programs not
  run on this seat: 0
Both client processes exit 0, serve process exit 0; the GET routes
pass including /utf8 ("héllo ☃"). The 23 remaining FAILs are the next
layer down, NOT this item, named here with owners:
- Eve-on-jank server answers [500 nil nil "native error"] on every
  route that reads the request body or echoes the request map
  (/req x3, /echo POST/PUT/DELETE): owner, the Eve http-server jank
  adapter line (Eve repo), via Agent 3's unit-9 harness evidence.
- Jank client programs error against both servers on four shapes:
  Optional "No value present"; a jank codegen rejection ("The `base`
  local cannot be captured, since its type `const char[23]` is not
  copyable" — upstream jank behavior, repro in the run log); a
  byte-array cast ("class array cannot be cast to class [B"); a
  persistent_string/persistent_array_map interop mismatch. Owner:
  Agent 3's unit-9 line / the jank adapter completion.

## Item 3 — the two standing pairs

### jank pair: cohost defects, FIXED. Gate now 221/221 + 62/62.

Both rows traced firsthand (bare-jank probes, no cohost code) to one
family: jank's string is a UTF-8 byte string — its count/nth/subs walk
BYTES ((count "héllo") = 6, (subs s 0 2) splits é, nth past a
multibyte char answers 0), while its seq walks CHARACTERS
((vec "héllo") counts 5, seq/first/mapv char-correct). Cohost's own
layers assumed character indexing in four places; all fixed in
cohost's layers:
- J1 read-forms (src/cohost/hosts/jank_host.jank): each form's text
  was sliced with (subs src i j) using the scan's CHARACTER indices,
  so any source with a multibyte character before a form boundary was
  sliced mid-character or short and the reader reported it
  unterminated (the battery's "Unterminated vector", the Character
  row's "Unexpected end of file", and item 2's serve-stage
  "Unterminated map"). Slices now come from the character vector
  ((apply str (subvec cs i j))); line-starts walks the character
  vector too. (Commit 40ec3a8.)
- J2 count: three routes to byte count — cohost_leaf::count_or (C++)
  returns a string's data.size() directly; the shim RT-count :jank
  arm fell through to host count; native-count likewise. A string is
  now counted by its seq in count-fast-with / nth-with
  (src/cohost/hosts/jank_runtime.jank) and in the shim's RT-count
  (src/cohost/shim.cljc). (Commits 40ec3a8, 2ee922c, 6fedd0e.)
- J3 Character statics (shim defaults, hosts naming no :java-statics
  — jank among them): isDigit was ASCII 0-9 only and isLetter was the
  upper≠lower heuristic, so a caseless letter (U+65E5) was "not a
  letter" and an Nd digit outside ASCII (U+0663) "not a digit". The
  defaults now use generated Unicode category tables
  (src/cohost/unicode_ranges.cljc: the JDK 21 predicates evaluated at
  every code point, run-compressed; spot-verified against the JDK on
  14 code points including Nl/No edges). JVM names the real statics
  and cljs names Unicode-regex ones; both untouched. (Commit 40ec3a8.)
- Same-family sibling fixed with them: nth on a string (nth-with and
  the shim RT-nth :jank arm, whose bounds check now measures the
  character count) goes by the seq, never the byte-indexed host nth.
Re-run on the final tree (build box): battery 221/221, libraries
62/62, boot counts unchanged (544 stock / 141 host / 28 natives /
108 skipped / 0 errors). Both standing rows pass; nothing else moved.

### cljs pair: seat platform facts, proven on the bare seat

- "numeric tower": the row's got vs expected differ at exactly one
  element, (= 1 1.0): got true, expected false. On cljs every number
  class maps to the single JS number (the host leaf maps
  Number/Long/Integer/Double to [number]); the category is erased at
  read time. Bare-seat proof (stock ClojureScript 1.12.42, cljs.main,
  node, no cohost code): (= 1 1.0) => true, (== 1 1.0) => true,
  (type 1) and (type 1.0) both Number. Stock ClojureScript itself has
  (= 1 1.0) true; the expectation encodes a JVM category distinction
  the seat does not have.
- "defmacro &form &env": got [8 m2], expected [2 m2] — &form is
  right; &env counts 8. Booted-seat probe (the gate build's own
  artifact, --eval): (keys &env) = (:column :context :def-emits-var
  :fn-scope :js-globals :line :locals :ns), count 8 at top level,
  with 1 local, and with 2 locals alike — on cljs &env IS the
  analyzer environment (locals nested under :locals), not a locals
  map. Bare-seat proof (stock cljs 1.12.42): (count &env) = 7, keys
  (:column :context :fn-scope :js-globals :line :locals :ns) — the
  same analyzer env (cohost's boot adds :def-emits-var). The JVM's
  &env contract (a locals map) is one stock cljs does not share.
Disposition for both rows: the seat's own compiler semantics, exactly
as the manifest records them standing; no cohost change is possible
that would not falsify the seat's stock behavior. Final-tree cljs
gate, as printed: battery 219/221 (this pair), libraries 62/62.
(One earlier run on this tree printed libraries 60/62 — both FAILs
the same Eve realm fault, "a reference written by another process
names nothing in this one", under heavy box load; the re-run on the
identical tree printed 62/62.)

## Item 4 — the JVM eduction canary: mechanism verified; lever baked

Mechanism verified firsthand in plain stock Clojure 1.12.0 (no
cohost/Eve code): Eduction is IReduceInit and Iterable but NOT
IReduce; the vendored protocols.clj (resources/cohost/clojure-
1.12.0/clojure/core/protocols.clj:85-88) IReduceInit 2-arity
coll-reduce body casts the collection to IReduce, so when the impl
map's hash iteration order over the three unrelated Class keys picks
that impl, (coll-reduce eduction) throws the byte-identical cast
error the gate prints; the Iterable and Object impls return 9, and
(reduce + eduction) returned 9 in the same process. The dispatch is a
tie-break riding on identity-hash allocation state — 221/221 on the
collation box, 220/221 on the build box, as UNIT4-REMAINDER-REPORT.md
dispositioned.
The lever (commit 60b20d9): the gate's own launch shape. bb.edn's
boot:jvm now launches clojure with -J-XX:+UnlockExperimentalVMOptions
-J-XX:hashCode=3 (sequential identity hashes), making the impl map's
iteration order deterministic and the dispatch the lawful one.
Nothing the battery measures changed: every row runs and asserts the
same values; bin/cohost and all other launch shapes untouched.
Numbers on the build box, final tree, as printed: baseline bb
boot:jvm 220/221 (canary the sole FAIL) + libraries 62/62; direct
flag runs 221/221 + 62/62 twice; bb boot:jvm with the baked flag
221/221 + 62/62 TWICE consecutively.

## Final gate state on this branch's tree (all re-run by this lane, build box)

- JVM: battery 221/221, libraries 62/62 (bb boot:jvm, twice).
- cljs: battery 219/221 (the standing pair, both seat platform facts
  with bare-seat proofs above), libraries 62/62 (bb boot:cljs).
- jank: battery 221/221, libraries 62/62 (bb boot:jank) — the
  standing jank pair is closed.
- Unit 9 jank harness leg: 13/36 as printed; it runs end-to-end now
  (was 0/36, dead before running); remainders named in item 2 with
  owners outside this lane.

Readiness: the release branch's named exceptions are cleared or
dispositioned with proof — ready for the operator's word on the move
to main.
