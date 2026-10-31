export const meta = {
  name: 'five-hosts-plan',
  description: 'Read-only design of cohost adapters for clojurust, jank, let-go, ClojureWasm and Phel, with jank types and a shared layer',
  phases: [
    { title: 'Design', detail: 'one designer per host, a jank type-system deep dive, and the shared cohost layer' },
    { title: 'Review', detail: 'adversarial review of every design; completeness and sequencing critic' },
  ],
}

const SURVEY = '/tmp/claude-0/-home-user/2da07f7e-61e7-51d9-a452-3e0ccf16189f/scratchpad/survey.json'

const CONTEXT = `CONTEXT
You are designing part of an implementation plan for cohost (local checkout /home/user/cohost.cljc; read-only for you: never modify it). cohost boots Clojure 1.12's own stock clojure/core.clj (and twelve stock libraries) on clojure-like hosts other than the JVM:
- the codemod (src/cohost/rewrite.cljc) rewrites core.clj's forms so they evaluate on the host;
- the shim (src/cohost/shim.cljc) stands in for clojure.lang.* and java.*;
- boot (src/cohost/boot.cljc) reads and evaluates core.clj form by form and loads the libraries; policy (src/cohost/policy.cljc) decides which names are taken from the host;
- each host has an ADAPTER. The contract, every capability with its default, is src/cohost/adapter.cljc (contract-order). Adapters live in src/cohost/hosts/: jvm.clj + java.clj (JVM Clojure), jolt.clj + java.clj (jolt, a Clojure on Chez Scheme), cljs.cljs (self-hosted ClojureScript), basilisp.lpy (Python);
- tests: the battery (src/cohost/battery.cljc, 139 behaviors), the library battery (src/cohost/lib_battery.cljc, 61 cases), the bench (src/cohost/bench.cljc), run by bb tasks in bb.edn (boot:jolt, boot:jvm, boot:cljs, boot:lpy, bench:*); jolt's conformance corpus via corpus:jolt;
- docs: README.md (status table), doc/REPORT.md (host facts), doc/PERF.md, doc/perf/HANDOFF.md.
cohost's own sources are .cljc with reader-conditional branches per host (:clj :cljs :jolt :lpy :default), so a new host must read and run cohost's own code too. Status today: JVM 139/139 battery, jolt 139/139, ClojureScript 137/139, basilisp 138/139; every host 61/61 libraries.

The five hosts to add:
- clojurust (cljrs, Rust): https://github.com/csm/clojurust
- jank (C++/LLVM): https://github.com/jank-lang/jank
- let-go (Go): https://github.com/nooga/let-go
- ClojureWasm / cljw (Zig): https://github.com/clojurewasm/ClojureWasm (releases now cut from BuddhiLW/ClojureWasm)
- Phel (PHP): https://github.com/phel-lang/phel-lang
A read-only survey assessed each one, with sources. Its JSON is ${SURVEY}: field "assessments" (one per project) and "verify.checks" (an adversarial re-check). Read your host's entries there first.

Box facts: Ubuntu 24.04 x86_64; go 1.24.7 (let-go's go.mod wants Go 1.27, or use its release binary); cargo/rustc; clang 18; gcc; PHP 8.3.6 (Phel needs PHP >= 8.5); node 24; bun; python3; java; clojure; bb; jolt; basilisp. None of the five hosts is installed. Installing them means downloading external code, which needs the user's approval: name the exact install commands in your design, but do not run them.

RULES: READ-ONLY. Never download-and-run, clone, build, install or execute any external project. Read cohost's files locally (Read, Grep). Read the hosts' code and docs with web tools: load them with ToolSearch query "select:WebSearch,WebFetch". Read GitHub through WebFetch of github.com pages and raw.githubusercontent.com files. Do NOT use any mcp__github__* tool. Every claim about a host carries its source (a URL or raw-file URL); every claim about cohost carries its file:line. Say "unknown" when you cannot establish something; never guess. Today is 2026-10-31.
Never give time estimates in hours or days. Size work by what must be written (capabilities, shim pieces, codemod changes) and by analogs in this repo (e.g. hosts/basilisp.lpy is 379 lines; hosts/jolt.clj is 121 plus the 223 of hosts/java.clj; hosts/cljs.cljs is 876).`

const HOST_TASK = host => `TASK: design cohost's adapter for ${host}. Produce:
1. Facts to pin: the version to target (tag or commit), the exact install commands, the feature key, how to run a program and a REPL, how files and namespaces load (does require find .cljc?), and how cohost's own sources would be put on the load path.
2. A capability map: go through EVERY capability in src/cohost/adapter.cljc's contract-order. For each one whose default does not apply on this host, say how the adapter provides it (the host fn or interop, with its source), or that it is a gap. Kinds: direct (a host fn), adapter (a few lines of adapter code), shim (cohost's shim must provide it), gap (needs a cohost change, or is impossible).
3. The type strategy: how deftype*, reify*, defprotocol/extend, defrecord, definterface and proxy (as core.clj and the shim use them) map onto this host. Compare the existing :type-method-style values (:jvm, :protocols, :python) in rewrite.cljc and adapter.cljc, and name the style this host needs.
4. The codemod and shim changes this host needs (with file:line in cohost), covering at least: reader-branch order (does the host also match :clj or :cljs?); &env (does the host pass a real &env to macros? cohost's single walk rewrites a rewritten macro's arguments in its expansion using &env's locals: see rewrite.cljc env-types and marker locals, boot.cljc wrap-macro, adapter :env-locals. What happens here if &env is nil?); a string builder; regex group positions; exceptions and a catch-all; chunked seqs; which natives are worth opting into.
5. Risks: the hardest problems, ranked, with evidence.
6. Milestones in order. Each has a done-bar that is a command with its expected result (e.g. "bb boot:<host> boots core.clj with 0 errors", "battery >= N/139"), and the files it touches.
7. Open questions only a hands-on run can settle, each with the probe to run once the host is installed.`

const JANK_HOST_NOTE = `NOTE for jank: a separate agent designs jank's type layer (deftype/defrecord/reify/defprotocol) in depth. Cover everything else in full, and in item 3 list exactly what the type layer must provide for core.clj and the shim to boot, without designing it.`

const JANK_TYPES_TASK = `TASK: answer the user's questions about jank's types from jank's source, and design cohost's type layer for jank. The user asked, verbatim: "For jank, we'll just have to build up a basic type hierarchy... Would we need something like eve? Do they not have persistent data structures? Or just deftype? Can't we make a deftype? Or does C++/LLVM not have a stable type system?" (eve is the user's own slab-based persistent-data-structure runtime from another project; the question is whether cohost would have to bring its own data structures to jank.)
Establish from jank's source (github.com/jank-lang/jank: compiler+runtime/include/cpp/jank/runtime/ and compiler+runtime/src/cpp/jank/runtime/, plus core.jank):
1. Which persistent and runtime data structures jank has natively (vectors, hash/array/sorted maps, sets, lists, cons, lazy and chunked seqs, transients, atoms, volatiles, delays, reduced, keywords, symbols, ratios, big ints, regexes, chars, strings), and how they are implemented (its own code, immer, ...).
2. The object model: how a jank object is represented (a tagged header? an object_type enum? vtables?); how core fns dispatch on it (visit_object? behaviors as C++ concepts?); and the change behind the 2026-03-06 blog post's "jank's object model has been opened up ... an approach which allows for JIT-defined objects while still avoiding the costs of C++'s runtime type information" and "jank users can define their own jank objects which integrate well into the system, can be stored within jank data structures, and used with jank functions". Find the commits/PRs (about 2025-11 to 2026-03; search the repo's PRs and commits for object model, custom object, behaviors, RTTI) and the headers they added. Establish how a new object type is defined (from C++? from jank via cpp/raw at run time?), which behaviors it can implement, and whether jank's own core fns (count, seq, first/next/rest, nth, get, assoc, conj, call/invoke, =, hash, str/print, meta/with-meta) dispatch to it. Quote the headers and code.
3. Multimethods: do defmulti/defmethod exist in jank (source)? What else could carry dispatch (var-based protocol tables, the new object model)?
4. C++ interop: can jank define a C++ class or struct at run time (cpp/raw, JIT) and instantiate it from jank? Can such a class derive from or register with jank's object model?
5. jank's own plans and dates for deftype/defrecord/defprotocol/reify (issues, PRs, blog, the creator's statements).
Then design cohost's deftype/defrecord/reify/defprotocol/definterface support on jank. Read first how cohost handles types today: rewrite.cljc's handling of deftype*/reify*/extend/defprotocol per :type-method-style, adapter.cljc's :type-method-style/:host-method/:user-type?/:invoke-member, and the shim's clojure.lang stand-ins. Compare at least:
(A) a cohost-level instance: a jank value (a map, vector or fn) carrying a type tag in metadata, with protocol dispatch in cohost's shim;
(B) one generic jank object type defined once through the opened object model, whose behaviors call jank fns from a per-type method table;
(C) a C++ class per deftype, generated and JIT-compiled at run time;
(D) waiting for upstream.
For each: what works; where a user type meets jank's own fns (a deftype instance as a map key needs jank's hash and =; calling an instance that implements IFn; printing; jank's natives and host aliases receiving a user type); the cohost files that change; the risks. Recommend one, and spell out the minimal "basic type hierarchy" the user mentioned: which clojure.lang interfaces and java.lang classes it must cover for core.clj and the shim to boot.
Finally answer the user's five questions plainly, each with its evidence.`

const COMMON_TASK = `TASK: design the cohost-side changes the five new hosts share, so they are built once. Read cohost's sources and the five hosts' survey entries. Cover:
1. Reader conditionals: inventory every #? and #?@ in cohost's sources (src/cohost/**, by file and by branch keys). Say which branch each new host would take today: cljw also matches :clj (fixed feature set {:cljw :clj :default}, first match in written order); let-go matches :clj only when opted in; check clojurust, jank and Phel. Give the convention and the mechanical edit that adds the five keys, with the count of forms to touch.
2. &env: cohost's single walk leaves a rewritten macro's arguments raw and rewrites them in the expansion using &env's locals (rewrite.cljc: env-types and marker locals; boot.cljc: wrap-macro; adapter :env-locals). jank and let-go pass &env as nil; jolt's &env carries names only. Specify exactly what breaks with a nil &env (locals named like core fns, type-hint markers, inlining, instance? shortcuts) and design the fallback, e.g. a capability that restores walking a macro's arguments before expansion on such hosts. Give the code sites.
3. A cohost-provided type layer for hosts without full deftype/reify/protocols. jank has none; establish what clojurust, let-go, cljw and Phel lack. Design a new :type-method-style whose codemod output builds types in the shim. Read how rewrite.cljc and shim.cljc handle deftype*/reify*/defprotocol/extend today for :jvm, :protocols and :python, and give the design and the code sites. (A separate agent designs jank's object-model integration in depth; you design the host-neutral layer that any host lacking types can use.)
4. Common shim fallbacks for capabilities a host lacks: a string builder; regex group positions (Go's RE2 in let-go, PHP's PCRE in Phel, Rust's regex crate in clojurust, cljw's engine, jank's); push/pop-thread-bindings where a host has only per-var push (let-go) or only with-bindings* (clojurust); macroexpand-1; load-string; read-string with opts. For each, the default the adapter contract should get.
5. Harness: the bb tasks, the install scripts (exact commands, gated on the user's approval), the battery, library battery and bench on each host, and using jank-lang/clojure-test-suite as a cross-host corpus.
6. The order to build the five in, from what each needs of the shared layer.`

const STR = { type: 'string' }
const HOST_DESIGN = {
  type: 'object',
  properties: {
    host: STR, summary: STR, pinned_version: STR, install_commands: STR, feature_key: STR, loading: STR,
    capability_map: { type: 'array', items: { type: 'object', properties: {
      capability: STR, provision: STR, kind: { type: 'string', enum: ['direct', 'adapter', 'shim', 'gap'] }, source: STR },
      required: ['capability', 'provision', 'kind', 'source'] } },
    type_strategy: STR,
    cohost_changes: { type: 'array', items: { type: 'object', properties: { site: STR, change: STR }, required: ['site', 'change'] } },
    risks: { type: 'array', items: { type: 'object', properties: {
      risk: STR, severity: { type: 'string', enum: ['high', 'medium', 'low'] }, evidence: STR }, required: ['risk', 'severity', 'evidence'] } },
    milestones: { type: 'array', items: { type: 'object', properties: { name: STR, done_bar: STR, touches: STR }, required: ['name', 'done_bar', 'touches'] } },
    open_questions: { type: 'array', items: { type: 'object', properties: { question: STR, probe: STR }, required: ['question', 'probe'] } },
  },
  required: ['host', 'summary', 'pinned_version', 'install_commands', 'feature_key', 'loading', 'capability_map', 'type_strategy',
    'cohost_changes', 'risks', 'milestones', 'open_questions'],
}

const JANK_TYPES = {
  type: 'object',
  properties: {
    answers: { type: 'array', items: { type: 'object', properties: {
      question: STR, answer: STR, evidence: { type: 'array', items: STR } }, required: ['question', 'answer', 'evidence'] } },
    data_structures: STR, object_model: STR, multimethods: STR, cpp_interop: STR, upstream_plans: STR,
    options: { type: 'array', items: { type: 'object', properties: {
      name: STR, how: STR, works: STR, breaks: STR, cohost_files: STR, risks: STR },
      required: ['name', 'how', 'works', 'breaks', 'cohost_files', 'risks'] } },
    recommendation: STR, type_hierarchy: STR,
  },
  required: ['answers', 'data_structures', 'object_model', 'multimethods', 'cpp_interop', 'upstream_plans', 'options', 'recommendation', 'type_hierarchy'],
}

const COMMON = {
  type: 'object',
  properties: {
    reader_conditionals: STR, env_fallback: STR, type_layer: STR,
    shim_fallbacks: { type: 'array', items: { type: 'object', properties: {
      capability: STR, hosts_lacking: STR, default_design: STR }, required: ['capability', 'hosts_lacking', 'default_design'] } },
    harness: STR, build_order: STR,
    changes: { type: 'array', items: { type: 'object', properties: { site: STR, change: STR, for_hosts: STR }, required: ['site', 'change', 'for_hosts'] } },
  },
  required: ['reader_conditionals', 'env_fallback', 'type_layer', 'shim_fallbacks', 'harness', 'build_order', 'changes'],
}

const REVIEW = {
  type: 'object',
  properties: {
    findings: { type: 'array', items: { type: 'object', properties: {
      severity: { type: 'string', enum: ['blocker', 'major', 'minor'] }, design: STR, claim: STR, evidence: STR, fix: STR },
      required: ['severity', 'design', 'claim', 'evidence', 'fix'] } },
  },
  required: ['findings'],
}

const CRITIC = {
  type: 'object',
  properties: {
    gaps: { type: 'array', items: { type: 'object', properties: { area: STR, gap: STR, fix: STR }, required: ['area', 'gap', 'fix'] } },
    build_order: STR,
  },
  required: ['gaps', 'build_order'],
}

const HOSTS = ['clojurust (cljrs)', 'jank', 'let-go', 'ClojureWasm (cljw)', 'Phel']

phase('Design')
const designs = await parallel([
  ...HOSTS.map(h => () => agent(`${CONTEXT}\n\n${HOST_TASK(h)}${h === 'jank' ? '\n\n' + JANK_HOST_NOTE : ''}`,
    { label: `design:${h}`, phase: 'Design', schema: HOST_DESIGN })),
  () => agent(`${CONTEXT}\n\n${JANK_TYPES_TASK}`, { label: 'design:jank-types', phase: 'Design', schema: JANK_TYPES }),
  () => agent(`${CONTEXT}\n\n${COMMON_TASK}`, { label: 'design:shared-layer', phase: 'Design', schema: COMMON }),
])
const hostDesigns = designs.slice(0, HOSTS.length)
const jankTypes = designs[HOSTS.length]
const common = designs[HOSTS.length + 1]
const missing = [...HOSTS.filter((h, i) => !hostDesigns[i]), ...(jankTypes ? [] : ['jank-types']), ...(common ? [] : ['shared-layer'])]
if (missing.length) log(`designs that returned nothing: ${missing.join(', ')}`)

const bundle = JSON.stringify({ hosts: hostDesigns.filter(Boolean), jank_types: jankTypes, shared_layer: common }, null, 1)

const REVIEW_TASK = `TASK: adversarially review the five host designs, the jank type design and the shared-layer design below. For each, try to REFUTE its key claims: claims about a host (re-read the cited raw files) and claims about cohost (re-read the cited file:line). Find infeasible steps, wrong assumptions about cohost's internals, missing prerequisites, done-bars that cannot be measured, and contradictions between designs (e.g. the jank type design against the shared type layer, or two designs assuming different defaults for the same capability). Return findings, each with a severity (blocker, major, minor), the design it concerns, the claim, the refuting evidence, and the fix.`

const CRITIC_TASK = `TASK: completeness and sequencing critic for the plan below. Check: does each host design cover every capability in src/cohost/adapter.cljc's contract-order whose default does not apply on that host (list the ones it skipped)? Are the shared-layer pieces scheduled before the hosts that need them? Is every milestone's done-bar a command with an expected result? Are the install steps exact and gated on the user's approval? What is missing: tests, the library battery, the bench, natives, docs (README status table, doc/REPORT.md host facts), CI? Return the gaps with fixes, and a build order with its dependencies.`

phase('Review')
const [review, critic] = await parallel([
  () => agent(`${CONTEXT}\n\n${REVIEW_TASK}\n\nDESIGNS:\n${bundle}`, { label: 'review:adversarial', phase: 'Review', schema: REVIEW }),
  () => agent(`${CONTEXT}\n\n${CRITIC_TASK}\n\nPLAN:\n${bundle}`, { label: 'review:completeness', phase: 'Review', schema: CRITIC }),
])

return { hosts: hostDesigns, jank_types: jankTypes, shared_layer: common, review, critic, missing }
