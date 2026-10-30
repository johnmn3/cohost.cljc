export const meta = {
  name: 'cohost-perf-attribution',
  description: 'Trace why cohost\'s booted stock core is slower than the host\'s own core, per code path, by reading the source only',
  phases: [
    { title: 'Trace', detail: 'one reader per hot code path: core.clj -> codemod -> shim -> adapter' },
    { title: 'Check', detail: 'an independent skeptic re-reads each area\'s top claims' },
  ],
}

const REPO = '/home/user/cohost.cljc'
const QUICK = args.quick

const CONTEXT = `
You are investigating the PERFORMANCE of cohost, a Clojure library at ${REPO}.
cohost boots Clojure 1.12.0's own clojure/core.clj (vendored at ${REPO}/resources/cohost/clojure-1.12.0/clojure/core.clj, unmodified)
inside other Clojure-like hosts (jolt, basilisp, self-hosted ClojureScript, and JVM Clojure as a reference). The pipeline:
- src/cohost/rewrite.cljc: a codemod that rewrites JVM interop in each core.clj form, at load time, into calls on the shim:
  (.m x args) becomes ((cohost.shim/dot "m" cohost.shim/-m) x args); (. clojure.lang.RT (seq x)) / (clojure.lang.RT/seq x) become shim fns like
  cohost.shim/RT-seq; (new clojure.lang.LazySeq f) becomes a shim constructor; ^Hint'd locals may get a check-cast (see checked-hints).
- src/cohost/shim.cljc: clojure.lang / java.* written over the host's own data structures (RT-*, Numbers-*, Util-*, LazySeq, instance-of?, dot, ...).
- src/cohost/policy.cljc: which core.clj names are booted from core.clj's own source ("stock"), which are taken from the host's own core ("host-names"), which skipped.
- src/cohost/boot.cljc: the boot loop; take-from-host! interns host fns (sometimes wrapped: ns-arg-wrap); macro wrapping.
- src/cohost/adapter.cljc + src/cohost/hosts/{jolt.clj,java.clj,jvm.clj,basilisp.lpy,cljs.cljs}: the host adapter (capabilities like :invoke-member, :user-type?, :sb-append!).
The benchmark harness is src/cohost/bench.cljc: each case body is compiled once in a namespace that sees only the booted stock core and once in one that sees
the host's own core, then timed. A first quick run on jolt (one timed call per side, ms per call; "x" = stock time / jolt's own core time):
${QUICK}

RULES: Read and grep only. Do NOT run jolt, clojure, java, node, basilisp, bb, python or any build/benchmark: a timing run is in progress on this
4-CPU machine and any heavy process skews it. Cite file:line for every claim and quote the code. Do not guess numbers; where a cost needs a
measurement, propose a small probe form instead (it will be timed later, on jolt, in the stock namespace, with core.clj's own (time ...)).
`

const AREAS = [
  { key: 'lazy-seqs', cases: 'lazy-pipeline (x196), partition, distinct, str-build, lazy-iterate, sort',
    focus: 'core.clj map/filter/lazy-seq/cons/seq/first/rest/next/chunked-seq?/range: what each element of (count (filter odd? (map inc (range 100000)))) costs on the stock side. Follow lazy-seq -> (new clojure.lang.LazySeq ...) -> the shim; RT-seq/RT-first/RT-next/RT-more; chunked-seq? -> instance? clojure.lang.IChunkedSeq -> instance-of?/canon/interface-preds; range -> clojure.lang.LongRange/Range -> shim. Why is reduce over range only x2 but map/filter over it x196?' },
  { key: 'transients-dispatch', cases: 'transient-vector (x140), into-vector (x56), transduce (x50), frequencies (x56), group-by (x44), set-build (x35)',
    focus: 'conj!/assoc!/persistent!/transient and the interop dispatcher: (.conj ^ITransientCollection coll x) -> rewrite -> check-cast (checked-hints in rewrite.cljc) -> instance-of? -> canon -> interface-preds; then (dot "conj" -conj) -> host-object? -> host/user-type? -> host/invoke-member (jolt.clj invoke-member and its member cache); closure allocation in dot per call. Compare with conj-vector (x1.0, persistent conj) and assoc-map (x1.1). What does ONE conj! cost on the stock side, step by step?' },
  { key: 'arith-fncall', cases: 'loop-arith (x55), fn-call (x6), atom-swap (x41)',
    focus: 'core.clj +, -, *, <, inc, = and their :inline / :inline-arities metadata: does the codemod or the boot honor :inline (expand (+ a b) to a Numbers call at the call site) or is every arithmetic op a full var-deref + fn call into a stock fn whose body calls a shim Numbers-* fn? What do Numbers-add/Numbers-lt/Numbers-inc do (overflow checks? type dispatch)? For atom-swap: swap! on stock -> (.swap ^clojure.lang.IAtom atom f x) -> dispatch/check-cast -> the host atom. For fn-call: defn in the stock ns -> any wrapping?' },
  { key: 'reduce-lookup', cases: 'keyword-lookup (x74), destructure (x87), map-lookup (x3.9), reduce-range (x2.1), equality (x1.0)',
    focus: '(reduce f 0 (repeat 100000 {:a 1 :b 2})): stock repeat -> clojure.lang.Repeat/create -> shim Repeat; stock reduce -> instance? IReduceInit / coll-reduce protocol (core/protocols.clj is NOT booted; which reduce path runs?) -> per-element cost. Destructuring {:keys [a b]} expands to (get m :a) -> stock get -> RT-get -> ... Why is map-lookup (reduce over range, get on a host map) only x3.9 while keyword-lookup over repeat is x74? Is it repeat?' },
  { key: 'strings-regex', cases: 'string-join (x346!), str-build (x21), regex (x30), format (x4.3), pr-str (x0.93)',
    focus: 'clojure.string/join is loaded from clojure/string.clj source through cohost\'s loader: (StringBuilder. ...) and (.append sb ...) in a loop with first/next. jolt\'s own StringBuilder appends 20k strings in 2.3 ms (measured), yet stock join of (range 20000) takes ~1150 ms: find what is ~50 microseconds per element. Check: how the rewrite treats StringBuilder construction and .append (host passthrough on jolt? shim SB?), dot dispatch on a jolt StringBuilder (host-object?/user-type?/invoke-member and its try/catch), (str ...) , and next/first on a range. Also re-seq (Matcher shim, re-find loop) and format (host format on jolt).' },
  { key: 'dispatch', cases: 'multimethod (x8), protocol (x1.8), atom-swap (x41)',
    focus: 'Stock defmulti over the shim\'s clojure.lang.MultiFn (src/cohost/shim.cljc: mf-state, mf-find-method, -getMethod...): per call, how is the dispatch value found and the method looked up (isa?/hierarchy walk every call? caching?). Protocols are the host\'s (defprotocol taken from the host). swap! on an atom: stock swap! -> .swap -> dispatch -> host swap!.' },
]

const FINDINGS = {
  type: 'object',
  properties: {
    area: { type: 'string' },
    findings: { type: 'array', items: { type: 'object', properties: {
      cases: { type: 'array', items: { type: 'string' } },
      claim: { type: 'string', description: 'what costs time per element/call on the stock side, concretely' },
      evidence: { type: 'array', items: { type: 'string' }, description: 'file:line plus the quoted code' },
      per_iteration_work: { type: 'string', description: 'the calls/allocations/lookups one element or call does on the stock side vs the host side' },
      confidence: { type: 'string', enum: ['high', 'medium', 'low'] } },
      required: ['cases', 'claim', 'evidence', 'per_iteration_work', 'confidence'] } },
    fixes: { type: 'array', items: { type: 'object', properties: {
      change: { type: 'string' }, files: { type: 'array', items: { type: 'string' } },
      removes: { type: 'string', description: 'which per-element work it removes' },
      risk: { type: 'string' } }, required: ['change', 'files', 'removes', 'risk'] } },
    probes: { type: 'array', items: { type: 'object', properties: {
      form: { type: 'string', description: 'a form to evaluate in the stock namespace on jolt, using (time ...) ' },
      tests: { type: 'string', description: 'which claim it confirms or refutes, and what result would refute it' } },
      required: ['form', 'tests'] } },
  },
  required: ['area', 'findings', 'fixes', 'probes'],
}

const VERDICT = {
  type: 'object',
  properties: {
    area: { type: 'string' },
    checks: { type: 'array', items: { type: 'object', properties: {
      claim: { type: 'string' },
      verdict: { type: 'string', enum: ['supported', 'refuted', 'unproven'] },
      reason: { type: 'string', description: 'with file:line evidence' } }, required: ['claim', 'verdict', 'reason'] } },
    missed: { type: 'array', items: { type: 'string' }, description: 'costs on this path the tracer missed, with file:line' },
  },
  required: ['area', 'checks', 'missed'],
}

const results = await pipeline(
  AREAS,
  (a) => agent(`${CONTEXT}
YOUR AREA: ${a.key}. Cases: ${a.cases}.
${a.focus}
Trace the stock path end to end and explain the measured ratio. Return findings (each with evidence), concrete fixes (smallest change that removes the
dominant per-element cost, and its risk), and probes (forms to time later that would confirm or refute your top claims).`,
    { label: `trace:${a.key}`, phase: 'Trace', schema: FINDINGS }),
  (f, a) => f && agent(`${CONTEXT}
An investigator traced area "${a.key}" (cases ${a.cases}) and reported the JSON below. Act as a skeptic: re-read the cited code yourself and try to
REFUTE each finding's claim (wrong file:line, the path does not actually run, a cost that is cached or compiled away, a bigger cost elsewhere).
Mark each claim supported / refuted / unproven with reasons and file:line, and list costs on this path the investigator missed.
FINDINGS:
${JSON.stringify(f, null, 1)}`,
    { label: `check:${a.key}`, phase: 'Check', schema: VERDICT }).then(v => ({ trace: f, check: v })),
)
return results.filter(Boolean)
