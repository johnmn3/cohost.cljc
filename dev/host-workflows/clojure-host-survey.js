export const meta = {
  name: 'clojure-host-survey',
  description: 'Read-only survey of Clojure-like hosts mature enough for a cohost adapter and .cljc support',
  phases: [
    { title: 'Sweep', detail: 'candidates three ways: lists and registries, by platform, recent news' },
    { title: 'Assess', detail: 'per platform family, primary-source evidence against the adapter prerequisites' },
    { title: 'Verify', detail: 'adversarial check of the leading candidates; completeness critic' },
  ],
}

const CONTEXT = `CONTEXT
cohost (https://github.com/johnmn3/cohost.cljc) boots Clojure 1.12's own stock clojure/core.clj on "clojure-like" hosts other than the JVM. A codemod rewrites core.clj's forms so they evaluate on the host, a shim stands in for clojure.lang.* and java.*, and a per-host ADAPTER supplies host capabilities. Hosts done today: JVM Clojure, jolt (a Clojure on Chez Scheme), self-hosted ClojureScript (Node and Chromium), basilisp (Python). cohost itself is written in .cljc with reader-conditional branches per host (:clj :cljs :jolt :lpy :default), so a new host must also READ AND RUN cohost's own .cljc code (a codemod over forms, a shim; ordinary clojure.core usage).

Hard prerequisites for a host adapter:
1. A reader that reads .cljc with reader conditionals (#? and #?@) under the host's own platform feature key (like :cljr, :clje, :jank, :lpy), and reads strings/files into forms at run time.
2. Runtime eval of an arbitrary form in a chosen namespace (boot evals core.clj's ~790 top-level forms one at a time, then user code).
3. First-class namespaces and vars: create a namespace, intern a var, look up interned and referred vars, refer and alias, read and alter var metadata, alter a var's root, mark a var as a macro (or define macros at run time), dynamic vars with thread-local binding (push/pop binding frames).
4. Macros that receive &form and &env; macroexpand-1.
5. throw/try/catch with a catch-all; ex-info / ex-data.
6. Persistent immutable collections (vectors, maps, sets, lists), keywords, symbols with metadata, lazy seqs, atoms, delays.
7. A way to define types and protocols (deftype/defrecord/reify/defprotocol or the host's analog).
8. Strings, a mutable string builder, regexes with group positions.
Nice to have: a REPL; conformance numbers (e.g. jank-lang/clojure-test-suite); an install route; active maintenance; users.

The user already knows of clojerl (BEAM), "a few that target Go" (e.g. Joker, Glojure, let-go) and jank (C++/LLVM). Cover those too, but the question is what ELSE exists that is mature enough.

Toolchains on the user's box (for the install-route note only): go, cargo/rustc, clang, gcc, ruby, php, chez scheme, node 24, bun, python3, java, clojure, babashka. Not on it: dotnet/mono, erlang, dart/flutter, lua, julia, sbcl, racket, guile, zig.

THIS IS READ-ONLY RESEARCH. Never download-and-run, clone, build, install or execute any project's code. Read with web tools only: load them with ToolSearch query "select:WebSearch,WebFetch". WebSearch mode "standard" first; "extended" for niche, recent (2025-2026) or thin results; send several searches in one turn. Read GitHub through WebFetch: repo pages, /commits and /releases pages, and individual source files at raw.githubusercontent.com. Do NOT use any mcp__github__* tool (this session's GitHub access is scoped to another repository). Today is 2026-10-30.

EVIDENCE RULES: every claim about a project carries its source (a URL; for code, the raw file URL). Kinds: source (you read the code), docs (the project's own README/docs/changelog), secondhand (blog, forum, list). Dates come from commits/releases, not from blog posts. If you cannot establish something, say "unknown"; never guess.`

const FAMILIES = ['dotnet', 'beam', 'go', 'native', 'js', 'dart', 'python', 'ruby', 'php', 'lua', 'lisp', 'jvm-alt', 'other']

const CANDIDATES = {
  type: 'object',
  properties: {
    candidates: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          family: { type: 'string', enum: FAMILIES, description: 'native = C/C++/LLVM/Rust/Zig/WASM; lisp = Common Lisp/Scheme/Racket/Guile; jvm-alt = SCI/babashka/Truffle etc.' },
          platform: { type: 'string', description: 'the target runtime, e.g. BEAM, Go, .NET, LLVM' },
          repo_url: { type: 'string' },
          feature_key: { type: 'string', description: 'its reader-conditional feature key if known, else empty' },
          summary: { type: 'string' },
          sources: { type: 'array', items: { type: 'string' } },
        },
        required: ['name', 'family', 'platform', 'repo_url', 'feature_key', 'summary', 'sources'],
      },
    },
    notes: { type: 'string' },
  },
  required: ['candidates', 'notes'],
}

const TRI = { type: 'string', enum: ['yes', 'partial', 'no', 'unknown'] }
const ASSESS = {
  type: 'object',
  properties: {
    assessments: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          platform: { type: 'string' },
          repo_url: { type: 'string' },
          implemented_in: { type: 'string' },
          status: { type: 'string', enum: ['active', 'slow', 'dormant', 'dead', 'unknown'] },
          last_activity: { type: 'string', description: 'date of the latest commit and of the latest release you saw, and where' },
          latest_release: { type: 'string' },
          feature_key: { type: 'string' },
          reads_cljc: TRI,
          runtime_eval: TRI,
          namespaces_vars_meta: TRI,
          macros_env: TRI,
          dynamic_binding: TRI,
          persistent_collections: TRI,
          types_protocols: TRI,
          exceptions_ex_info: TRI,
          strings_regex: TRI,
          conformance: { type: 'string', description: 'test-suite or conformance numbers, if any' },
          toolchain_on_box: { type: 'boolean' },
          install_route: { type: 'string' },
          adapter_fit: { type: 'string', description: 'how the adapter contract maps onto it; the hardest gaps' },
          verdict: { type: 'string', enum: ['strong', 'possible', 'weak', 'not-viable'] },
          verdict_reason: { type: 'string' },
          evidence: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                claim: { type: 'string' },
                source: { type: 'string' },
                kind: { type: 'string', enum: ['source', 'docs', 'secondhand'] },
              },
              required: ['claim', 'source', 'kind'],
            },
          },
        },
        required: ['name', 'platform', 'repo_url', 'implemented_in', 'status', 'last_activity', 'latest_release', 'feature_key',
          'reads_cljc', 'runtime_eval', 'namespaces_vars_meta', 'macros_env', 'dynamic_binding', 'persistent_collections',
          'types_protocols', 'exceptions_ex_info', 'strings_regex', 'conformance', 'toolchain_on_box', 'install_route',
          'adapter_fit', 'verdict', 'verdict_reason', 'evidence'],
      },
    },
  },
  required: ['assessments'],
}

const VERIFY = {
  type: 'object',
  properties: {
    checks: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          claims: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                claim: { type: 'string' },
                result: { type: 'string', enum: ['confirmed', 'refuted', 'unverified'] },
                evidence: { type: 'string' },
              },
              required: ['claim', 'result', 'evidence'],
            },
          },
          corrected_verdict: { type: 'string', enum: ['strong', 'possible', 'weak', 'not-viable'] },
          note: { type: 'string' },
        },
        required: ['name', 'claims', 'corrected_verdict', 'note'],
      },
    },
  },
  required: ['checks'],
}

const CRITIC = {
  type: 'object',
  properties: {
    missing: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          platform: { type: 'string' },
          repo_url: { type: 'string' },
          why_it_matters: { type: 'string' },
          quick_verdict: { type: 'string', enum: ['strong', 'possible', 'weak', 'not-viable', 'unknown'] },
          sources: { type: 'array', items: { type: 'string' } },
        },
        required: ['name', 'platform', 'repo_url', 'why_it_matters', 'quick_verdict', 'sources'],
      },
    },
    gaps: { type: 'array', items: { type: 'string' } },
  },
  required: ['missing', 'gaps'],
}

const SWEEP_TAIL = `Return every candidate you find; completeness matters more than precision here (a later stage assesses each). Include the ones the user named. Exclude JVM Clojure itself, ClojureScript itself, jolt and basilisp (already hosts), but DO include other JS-targeting Clojure-likes and other Python ones. Mark dead projects as such in the summary.`

const SWEEPS = [
  {
    key: 'lists',
    prompt: `Sweep curated lists and registries for Clojure implementations on hosts other than the JVM. Read at least: (1) github.com/chr15m/awesome-clojure-likes (its README); (2) jank-lang/clojure-test-suite: its README, CI workflows and per-dialect configuration (which dialects it runs, with which feature keys); (3) the dialects named on Wikipedia's Clojure article and on clojure.org (look for a dialects or "other platforms" page); (4) reader-conditional feature keys in the wild: clojure.org/guides/reader_conditionals, clj-kondo's docs on :features and dialects, and searches for exotic keys in .cljc files (:cljr :clje :cljd :jank :bb :joker :glj :lpy :squint :cherry :nbb :phel :ferret ...). For each implementation: name, family, platform, repo URL, feature key if known, one-line summary, sources.`,
  },
  {
    key: 'platforms',
    prompt: `Sweep by target runtime. For each runtime below, search "Clojure on X", "Clojure dialect X", "Clojure implementation in X", "Clojure-like Lisp X", and follow the hits: .NET/CLR; BEAM (Erlang/Elixir); Go; Rust; C/C++/LLVM; Zig; WebAssembly (standalone, WASI); Lua/LuaJIT; Python (other than basilisp); Ruby; PHP; Dart/Flutter; Swift/Objective-C; Julia; R; Common Lisp; Scheme/Racket/Guile/Chez (other than jolt); Nim; OCaml/Haskell; Kotlin/Native; JavaScript runtimes other than ClojureScript itself (squint, cherry, nbb, scittle, ...); JVM/GraalVM alternatives that carry their own core (SCI/babashka, Truffle-based). Report every implementation that claims to be Clojure or a close Clojure dialect, dead ones included (marked), with name, family, platform, repo URL, feature key if known, one-line summary, sources.`,
  },
  {
    key: 'recent',
    prompt: `Sweep 2023-2026 news for new, revived or maturing Clojure implementations on non-JVM hosts: The Clojure Deref (clojure.org/news) issues of 2024-2026; Clojure/conj 2023-2025, reClojure, Heart of Clojure and London Clojurians talk listings; Clojurians Slack/Zulip announcement archives; r/Clojure; Hacker News ("Show HN" Clojure-like); ClojureVerse; jank's blog (jank-lang.org/blog); Lobsters. For known dialects (ClojureCLR, Clojerl, ClojureDart, jank, Joker, Glojure, let-go, babashka/SCI, squint/cherry, Phel) note 2025-2026 status news (releases, funding, abandonment). Return each implementation with name, family, platform, repo URL, feature key if known, one-line summary, sources.`,
  },
]

phase('Sweep')
const swept = await parallel(SWEEPS.map(s => () =>
  agent(`${CONTEXT}\n\nTASK (sweep: ${s.key})\n${s.prompt}\n\n${SWEEP_TAIL}`, { label: `sweep:${s.key}`, phase: 'Sweep', schema: CANDIDATES })))

const norm = s => (s || '').toLowerCase().replace(/[^a-z0-9]/g, '')
const repoKey = u => {
  const m = (u || '').toLowerCase().match(/github\.com\/([^\/\s#?]+\/[^\/\s#?]+)/)
  return m ? m[1].replace(/\.git$/, '') : ''
}
const EXISTING = new Set(['clojure', 'clojurejvm', 'clojurescript', 'cljs', 'jolt', 'basilisp'])
const byKey = new Map()
for (const r of swept.filter(Boolean)) {
  for (const c of (r.candidates || [])) {
    const nk = norm(c.name)
    if (!nk || EXISTING.has(nk)) continue
    const rk = repoKey(c.repo_url)
    let hit = null
    for (const [k, v] of byKey) { if (k === nk || (rk && v.rk === rk)) { hit = k; break } }
    if (hit) {
      const v = byKey.get(hit)
      v.sources = [...new Set([...v.sources, ...(c.sources || [])])]
      if (!v.feature_key && c.feature_key) v.feature_key = c.feature_key
      if (!v.rk && rk) { v.rk = rk; v.repo_url = c.repo_url }
      v.summaries.push(c.summary)
    } else {
      byKey.set(nk, { ...c, rk, sources: [...(c.sources || [])], summaries: [c.summary] })
    }
  }
}
const cands = [...byKey.values()]
log(`${cands.length} distinct candidates from ${swept.filter(Boolean).length} of ${SWEEPS.length} sweeps`)

const BINS = [
  { key: 'beam-dotnet-jvm-alt', fams: ['beam', 'dotnet', 'jvm-alt'] },
  { key: 'go', fams: ['go'] },
  { key: 'native', fams: ['native'] },
  { key: 'other-runtimes', fams: ['js', 'dart', 'python', 'ruby', 'php', 'lua', 'lisp', 'other'] },
]
const batches = BINS.map(b => ({ ...b, items: cands.filter(c => b.fams.includes(c.family)) })).filter(b => b.items.length)
for (const b of batches) log(`assess ${b.key}: ${b.items.map(c => c.name).join(', ')}`)

const ASSESS_TASK = `Assess each candidate below: one assessment per candidate (merge duplicates; a candidate that is not actually a Clojure implementation gets verdict not-viable with the reason). For each, establish from primary sources (the repo's README, docs, CHANGELOG, release page and commit history; individual source files via raw.githubusercontent.com where the docs are silent):
- status and dates: the latest commit and release dates (the repo's /commits and /releases pages), contributors, signs of users;
- prerequisites 1-8, each yes/partial/no/unknown with evidence. Specifically: does its reader take #? with its own feature key (which key?) and read at run time; runtime eval with namespaces; vars with metadata, alter-var-root and alter-meta!; defmacro with &form/&env; binding of dynamic vars; persistent collections; deftype/defrecord/protocols; ex-info; regexes with groups;
- conformance: numbers from jank-lang/clojure-test-suite or its own suite, if any;
- toolchain_on_box (from the list in CONTEXT) and the install route;
- adapter_fit: how cohost's adapter contract would map onto it, and its hardest gaps (e.g. no runtime eval, no first-class vars, AOT-only, no &env);
- verdict: strong (could host an adapter now), possible (could, with named gaps), weak (major gaps or unmaintained), not-viable (no runtime eval or vars, dead, or not Clojure).
Be concrete and cite; do not rate on reputation.`

phase('Assess')
const assessed = await parallel(batches.map(b => () =>
  agent(`${CONTEXT}\n\nTASK (assess: ${b.key})\n${ASSESS_TASK}\n\nCANDIDATES:\n${JSON.stringify(b.items.map(c => ({ name: c.name, platform: c.platform, repo_url: c.repo_url, feature_key: c.feature_key, summaries: c.summaries, sources: c.sources })), null, 1)}`,
    { label: `assess:${b.key}`, phase: 'Assess', schema: ASSESS })))
const all = assessed.filter(Boolean).flatMap(r => r.assessments || [])
const dropped = batches.filter((b, i) => !assessed[i]).map(b => b.key)
if (dropped.length) log(`assessment batches that returned nothing: ${dropped.join(', ')}`)
log(`${all.length} assessments: ${all.filter(a => a.verdict === 'strong').length} strong, ${all.filter(a => a.verdict === 'possible').length} possible`)

const leading = all.filter(a => a.verdict === 'strong' || a.verdict === 'possible')

const VERIFY_TASK = `ADVERSARIAL VERIFY. The candidates below were rated strong or possible. Try to REFUTE each rating from primary sources (read the code at raw.githubusercontent.com, the docs, the commits and releases pages). Check each candidate's key claims: (1) actively maintained as of 2026 (a commit or release in the last 12 months); (2) runtime eval; (3) reads .cljc with its own feature key (which key? find it in the reader's source); (4) namespaces, vars with metadata, alter-var-root and dynamic binding (find them in its core); (5) defmacro with &form/&env (find where the compiler passes them). Mark a claim "unverified" when you cannot confirm it from source or docs, "refuted" when evidence contradicts it. Return, per candidate, each claim's result with evidence and a corrected verdict.`

const CRITIC_TASK = `COMPLETENESS CRITIC. The survey found the candidates and verdicts below. Find what is missing: (a) Clojure implementations not in the list: search again from different angles: package registries (crates.io, pkg.go.dev, npm, PyPI, RubyGems, Packagist, Hex, NuGet, pub.dev, LuaRocks) for Clojure implementations; GitHub topics clojure-implementation, clojure-dialect, clojure-like, lisp-dialect + clojure; (b) whether the user-named ones (clojerl, the Go-targeting ones, jank) are all assessed; (c) important claims the assessments left unknown or rated on thin evidence. Return the missing implementations with quick verdicts and sources, and a list of gaps.`

phase('Verify')
const [ver, crit] = await parallel([
  () => leading.length
    ? agent(`${CONTEXT}\n\nTASK\n${VERIFY_TASK}\n\nCANDIDATES:\n${JSON.stringify(leading, null, 1)}`, { label: 'verify:leading', phase: 'Verify', schema: VERIFY })
    : Promise.resolve(null),
  () => agent(`${CONTEXT}\n\nTASK\n${CRITIC_TASK}\n\nCANDIDATES SEEN: ${cands.map(c => c.name).join(', ')}\n\nASSESSMENTS: ${JSON.stringify(all.map(a => ({ name: a.name, platform: a.platform, status: a.status, verdict: a.verdict, reason: a.verdict_reason, unknowns: Object.entries(a).filter(([k, v]) => v === 'unknown').map(([k]) => k) })), null, 1)}`,
    { label: 'critic', phase: 'Verify', schema: CRITIC }),
])

return {
  candidates: cands.map(c => ({ name: c.name, family: c.family, platform: c.platform, repo_url: c.repo_url, feature_key: c.feature_key })),
  assessments: all,
  dropped_batches: dropped,
  verify: ver,
  critic: crit,
}
