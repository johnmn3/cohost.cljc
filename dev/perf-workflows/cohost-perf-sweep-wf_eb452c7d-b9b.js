export const meta = {
  name: 'cohost-perf-sweep',
  description: 'Sweep cohost\'s sources for needless costs (boot, codemod output, adapters, shim), verify each, find what the first pass missed',
  phases: [
    { title: 'Find', detail: 'one finder per area' },
    { title: 'Verify', detail: 'a skeptic re-reads each finding' },
    { title: 'Find more', detail: 'a second finder per area, told what is already found' },
  ],
}

const REPO = '/home/user/cohost.cljc'
const CONTEXT = `
You are hunting for NEEDLESS PERFORMANCE COSTS ("places where we're being dumb") in cohost, a Clojure library at ${REPO}.
cohost boots Clojure 1.12.0's own clojure/core.clj (resources/cohost/clojure-1.12.0/clojure/core.clj, unmodified) inside other
Clojure-like hosts — jolt (Chez Scheme), basilisp (Python), self-hosted ClojureScript (Node, browser) — and JVM Clojure as a reference:
- src/cohost/rewrite.cljc: the codemod; at load time it rewrites each form's JVM interop into shim calls. Today (.m x a) becomes
  ((cohost.shim/dot "m" cohost.shim/-m) x a) — a dispatcher closure built at every evaluation of the call; a ^Hinted local may get
  (cohost.shim/check-cast 'Class x); (. clojure.lang.RT (seq x)) becomes cohost.shim/RT-seq; (new C ...) a shim constructor.
- src/cohost/shim.cljc: clojure.lang / java.* over the host's data (RT-*, Numbers-*, Util-*, LazySeq, Matcher, Formatter, MultiFn,
  instance-of?/canon/interface-preds/class-of, dot/host-object?).
- src/cohost/adapter.cljc: the host contract; every accessor (host/xyz) calls (cap :k), which derefs an atom and does contains? + get
  on a 100+-key map per call. src/cohost/hosts/{jolt.clj,java.clj,jvm.clj,basilisp.lpy,cljs.cljs}: the adapters.
- src/cohost/boot.cljc: the boot loop over core.clj's 791 forms (boot takes 1.7 s on the JVM, 3.7 s on jolt, ~4.5 min on basilisp),
  the loader for clojure.* libraries, eval-str. src/cohost/policy.cljc: per-form decisions.
Measured (median ms per call, stock = cohost's booted core, own = the host's own core): on jolt the stock core is ~x45 slower than JVM
Clojure overall and x50-x350 slower than jolt's own core on lazy map/filter, conj!/transients, keyword lookup over (repeat ...),
destructuring, frequencies, sort-by (x523 vs JVM), clojure.string/join (x2220 vs JVM); on the JVM the stock core is x5 overall, with
clojure.string/join x580, re-seq x129, lazy map/filter x45, conj!/into/transduce x22-28; arithmetic in loop/recur is x1 on the JVM
(the JVM compiler honors core.clj's :inline) but x300 on jolt and ClojureScript.

RULES: read and grep only; do NOT run jolt, clojure, java, node, basilisp, bb, python or any build/benchmark (timing runs share this
4-CPU box). Every finding cites file:line and quotes the code. Only report real costs on a path that runs (per call, per element, per
form at boot, per eval) — not style. For each: what it costs, how often it runs, and the smallest fix that keeps behavior identical.
Do not estimate speedups with numbers you did not measure; say what work the fix removes.`

const AREAS = [
  { key: 'boot-loader', files: 'src/cohost/boot.cljc, src/cohost/policy.cljc',
    focus: 'Per-form boot work (process-form!, eval-form!, prepare, wrap-macro, scan-macros! after EVERY form — what does it walk?, record!, take-from-host!, ns-arg-wrap), the loader (load-lib!, load-source!, find-source, decide-lib), eval-str/eval-top per call. Anything quadratic in the number of forms or vars; repeated work that could be done once.' },
  { key: 'codemod-output', files: 'src/cohost/rewrite.cljc',
    focus: 'Both the codemod\'s own cost per form (walks, repeated lookups, *local-tags* handling) and, more important, the SHAPE of what it emits and what that costs at run time: the per-evaluation dot dispatcher closure, check-cast with a quoted class canonicalized on every call, instance? calls on literal class symbols going through cohost.core/instance? -> (. c (isInstance x)) -> dot -> -isInstance -> instance-of? -> canon, statics, constructors. What could be resolved once at rewrite time instead of on every call?' },
  { key: 'adapters', files: 'src/cohost/adapter.cljc, src/cohost/hosts/jolt.clj, src/cohost/hosts/java.clj, src/cohost/hosts/jvm.clj, src/cohost/hosts/basilisp.lpy, src/cohost/hosts/cljs.cljs',
    focus: 'Accessor overhead (cap per call) and which accessors are on hot paths; each host\'s implementation of hot capabilities (user-type?, invoke-member and its member cache and try/catch, host-instance?, char?*/counted?*/indexed?*, sb-append!, re-find-at with groups/spans, intern!, eval and register-uses! on cljs — what does each eval scan?). JVM/jolt: calls that would be reflective without a type hint (StringBuilder .append, Matcher methods, etc.). Exceptions used for control flow.' },
  { key: 'shim-core', files: 'src/cohost/shim.cljc (roughly the first 1000 lines: RT-*, Numbers-*, Util-*, collections, LazySeq/Cons/chunking, Iterate/Repeat/Range/Cycle, transients, instance-of?/canon/interface-preds/class-of/token-isa?)',
    focus: 'Per-call cost of the RT / Numbers / collection shims that core.clj calls on every element: redundant checks, predicates that call several host accessors, allocation, canon/string scans per call, wrappers that could be the host fn itself.' },
  { key: 'shim-rest', files: 'src/cohost/shim.cljc (roughly lines 1000-end: strings, StringBuilder, regex Pattern/Matcher, java.util.Formatter (jformat), MultiFn and its dispatch, Var/Namespace/Compiler, Character, dot/host-object?)',
    focus: 'Per-call cost of strings, regex (Matcher re-creating host matchers and recomputing all groups/spans per find?), MultiFn dispatch (method lookup and isa? per call — is there a cache like the JVM\'s methodCache?), the Formatter, and the dot dispatcher.' },
]

const FINDINGS = { type: 'object', properties: {
  area: { type: 'string' },
  findings: { type: 'array', items: { type: 'object', properties: {
    id: { type: 'string', description: 'short slug' },
    where: { type: 'string', description: 'file:line(s)' },
    code: { type: 'string', description: 'the quoted code' },
    cost: { type: 'string', description: 'what work it does needlessly' },
    frequency: { type: 'string', description: 'how often: per call / per element / per form at boot / per eval / once' },
    fix: { type: 'string', description: 'smallest behavior-preserving change' },
    risk: { type: 'string' } },
    required: ['id', 'where', 'code', 'cost', 'frequency', 'fix', 'risk'] } } },
  required: ['area', 'findings'] }

const VERDICT = { type: 'object', properties: {
  verdicts: { type: 'array', items: { type: 'object', properties: {
    id: { type: 'string' }, verdict: { type: 'string', enum: ['real', 'not-real', 'minor'] },
    reason: { type: 'string', description: 'with file:line' },
    fix_ok: { type: 'boolean', description: 'does the proposed fix keep behavior identical?' } },
    required: ['id', 'verdict', 'reason', 'fix_ok'] } } },
  required: ['verdicts'] }

const perArea = await pipeline(
  AREAS,
  (a) => agent(`${CONTEXT}
AREA: ${a.key}. Files: ${a.files}.
${a.focus}
Find every needless cost in your files. Return them as findings.`, { label: `find:${a.key}`, phase: 'Find', schema: FINDINGS }),
  (f, a) => f && agent(`${CONTEXT}
A finder swept area "${a.key}" (${a.files}) and reported the findings below. Re-read each cited place yourself and judge it: real (a
needless cost on a path that runs, worth fixing), minor (real but rarely run or tiny), or not-real (the path does not run, the cost is
cached or compiled away, or the code is needed for correctness). Also judge whether the proposed fix keeps behavior identical.
FINDINGS:
${JSON.stringify(f.findings, null, 1)}`, { label: `verify:${a.key}`, phase: 'Verify', schema: VERDICT }).then(v => ({ ...f, verdicts: v && v.verdicts })),
  (fv, a) => fv && agent(`${CONTEXT}
AREA: ${a.key}. Files: ${a.files}.
${a.focus}
A first sweep of this area already found the items below. Find what it MISSED: needless costs not on this list (do not repeat them).
ALREADY FOUND:
${fv.findings.map(x => '- ' + x.id + ' @ ' + x.where + ': ' + x.cost).join('\n')}`, { label: `more:${a.key}`, phase: 'Find more', schema: FINDINGS })
    .then(m => ({ ...fv, more: m ? m.findings : [] })),
)
return perArea.filter(Boolean)
