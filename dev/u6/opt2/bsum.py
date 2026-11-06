import sys, re, statistics as st
rows = {}
for ln in open(sys.argv[1]):
    if not ln.startswith('BENCH'): continue
    p = ln.split()
    # BENCH mode style fn val rep k unwrapped-ns/call U wrapped-ns/call W
    mode, style, fn, val = p[1], p[2], p[3], p[4]
    u, w = float(p[8]), float(p[10])
    rows.setdefault((mode, style, fn, val), []).append((u, w))
print(f"{'mode':4} {'style':6} {'fn':6} {'val':4} | unwrapped ns/call median [min-max] | wrapped ns/call median [min-max] | delta median")
for k in sorted(rows):
    us = [a for a, _ in rows[k]]; ws = [b for _, b in rows[k]]
    f = lambda xs: f"{st.median(xs):7.1f} [{min(xs):.1f}-{max(xs):.1f}]"
    print(f"{k[0]:4} {k[1]:6} {k[2]:6} {k[3]:4} | {f(us)} | {f(ws)} | {st.median(ws)-st.median(us):+.1f}  (n={len(us)})")
