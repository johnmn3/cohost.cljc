import re, subprocess, sys
sys.path.insert(0, 'lane')
import splint_engine as E

findings = []
for line in open('/tmp/carve-fresh.txt'):
    m = re.match(r'^(src/\S+):(\d+):1 (\S+)/(\S+)$', line.strip())
    if m:
        findings.append((m.group(1), int(m.group(2)), m.group(3), m.group(4)))
print('findings:', len(findings))

# privacy + def kind per finding, from the source form at the line
by_file = {}
for f, ln, ns, var in findings:
    by_file.setdefault(f, []).append((ln, ns, var))
info = {}
for f, items in by_file.items():
    s = open(f).read()
    tops = E.parse_forms(s)
    lines = s.split('\n')
    offsets = [0]
    for L in lines: offsets.append(offsets[-1] + len(L) + 1)
    for ln, ns, var in items:
        pos = offsets[ln - 1]
        form = None
        for t in tops:
            if t.start <= pos < t.end:
                form = t
                break
        priv = '?'
        if form is not None:
            raw = E.raw(s, form)
            head = E.head_sym(form) or ''
            if head in ('defn-', 'def-'):
                priv = 'private'
            elif re.search(r'\^:private', raw[:200]) or ':private true' in raw[:300]:
                priv = 'private'
            elif head.startswith('def'):
                priv = 'public'
        info[(ns, var)] = (f, ln, priv)

# repo-wide mentions: qualified name anywhere except the finding line itself
import os
corpus = []
for root, dirs, files in os.walk('.'):
    dirs[:] = [d for d in dirs if d not in ('.git', '.clj-kondo', 'target', 'node_modules')]
    for fn in files:
        p = os.path.join(root, fn)
        if fn.endswith(('.clj', '.cljc', '.cljs', '.clj', '.edn', '.md', '.sh', '.py', '.json')) or fn in ('bb.edn',):
            corpus.append(p)
texts = {}
for p in corpus:
    try:
        texts[p] = open(p, errors='ignore').read()
    except Exception:
        pass

rows = []
for ns, var in sorted(info):
    f, ln, priv = info[(ns, var)]
    qname = f'{ns}/{var}'
    mentions = 0
    for p, t in texts.items():
        c = t.count(qname)
        if p == './' + f:
            c = max(0, c - 0)  # def line uses the short name, not qualified; keep as-is
        mentions += c
    rows.append((ns, var, priv, mentions, f, ln))

import collections
print('by privacy:', collections.Counter(r[2] for r in rows))
print('private with 0 qualified mentions:', sum(1 for r in rows if r[2] == 'private' and r[3] == 0))
with open('/tmp/carve-triage.tsv', 'w') as fh:
    for r in rows:
        fh.write('\t'.join(map(str, r)) + '\n')
print('wrote /tmp/carve-triage.tsv')
