import json, sys, os, re
D = sys.argv[1]
defs = {x['name']: x for x in json.load(open(D + '/defns.json'))}
idx = {}
for ln in open(D + '/out/index.txt'):
    f, h = ln.rstrip('\n').rsplit(' ', 1); idx[f] = h
rows = []
for f, x in defs.items():
    h = idx.get(f)
    if not h: rows.append((f, x['line'], 'NOT-RUN', '')); continue
    t = open(f'{D}/out/{h}.log', errors='replace').read()
    rc = int(re.findall(r'rc=(\d+)', t)[-1])
    calls = re.findall(r'^CALL \S+ (\d)', t, re.M)
    last = calls[-1] if calls else '?'
    if 'Panic encountered' in t:
        cls, why = 'PANIC', f'arity {last}: ' + re.search(r'Panic encountered: ([^\n]*)', t).group(1)
    elif 'terminate called' in t:
        cls, why = 'TERMINATE', f'arity {last}: ' + re.search(r'terminate called ([^\n]*)', t).group(1)[:90]
    elif rc == 124:
        cls, why = 'NO-RETURN-120s', f'arity {last}'
    elif rc not in (0,):
        cls, why = f'EXIT-{rc}', f'arity {last}; ' + (t.strip().splitlines()[-2][:100] if len(t.strip().splitlines()) > 1 else '')
    else:
        oks = re.findall(r'^OUT \S+ (\d) :ok ([^\n]*)', t, re.M)
        if oks: cls, why = 'ANSWERS', f'arity {oks[0][0]}: {oks[0][1][:60]}'
        else:
            th = re.findall(r'^OUT \S+ (\d) :threw ([^\n]*)', t, re.M)
            cls, why = 'THROWS', (f'arity {th[-1][0]}: {th[-1][1][:90]}' if th else '')
    rows.append((f, x['line'], cls, why))
from collections import Counter
c = Counter(r[2] for r in rows)
print('classes:', dict(c))
with open(D + '/table.tsv', 'w') as o:
    for r in sorted(rows, key=lambda r: (r[2], r[1])):
        o.write('\t'.join(map(str, r)) + '\n')
for cls in ('PANIC', 'TERMINATE', 'NO-RETURN-120s'):
    print(cls + ':', ' '.join(r[0] for r in sorted(rows, key=lambda r: r[1]) if r[2] == cls))
for cls in sorted(set(c) - {'PANIC', 'TERMINATE', 'NO-RETURN-120s', 'ANSWERS', 'THROWS'}):
    print(cls + ':', ' '.join(r[0] for r in rows if r[2] == cls))
