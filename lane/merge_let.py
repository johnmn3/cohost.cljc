#!/usr/bin/env python3
"""merge_let.py FILE INNER_LET_LINE

Merge the let form starting on INNER_LET_LINE into its parent let,
when the parent let's only body form is that inner let:
  (let [A] (let [B] body))  ->  (let [A B] body)
Comments between the parent's binding vector and the inner let are
preserved, moved to just after the merged binding vector.
Prints the merged parent form; with --apply, rewrites the file.
Verification is external: cljfmt parse + kondo + boot gate.
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/syntax-formalization/lanes/cohost/lane')
from fmatch import scan, match_end, OPEN

def head_sym(s, a):
    """Symbol right after the '(' at a, or None."""
    i = a + 1
    j = i
    while j < len(s) and (s[j].isalnum() or s[j] in '*+!-_?/.'):
        j += 1
    return s[i:j]

def vec_span(s, a):
    """Span of the first [..] vector at/after a (the binding vector)."""
    for i, c in scan(s, a):
        if c == '[':
            return i, match_end(s, i)
        if c == '(':
            continue
    raise ValueError('no binding vector')

def forms_between(s, lo, hi):
    """Top-level form spans strictly inside (lo, hi), skipping
    whitespace/comments (scan already skips comments/strings)."""
    out = []
    i = lo
    while True:
        nxt = None
        for k, c in scan(s, i):
            if k >= hi:
                break
            if c in OPEN:
                nxt = (k, match_end(s, k))
                break
            if not c.isspace():
                # a bare token (symbol/keyword/number) — count as a form
                j = k
                while j < hi and not s[j].isspace() and s[j] not in '()[]{}':
                    j += 1
                nxt = (k, j)
                break
        if nxt is None:
            return out
        out.append(nxt)
        i = nxt[1]
        if i >= hi:
            return out

def merge(s, inner_line):
    lines = s.split('\n')
    off = sum(len(l) + 1 for l in lines[:inner_line - 1])
    inner = None
    for k, c in scan(s, off):
        if c in OPEN:
            inner = (k, match_end(s, k))
            break
        if s[k] == '\n':
            break
    if inner is None or head_sym(s, inner[0]) not in ('let', 'let*'):
        raise ValueError('form on that line is not a let')
    ia, ib = inner
    iv_a, iv_b = vec_span(s, ia)
    inner_bindings = s[iv_a + 1:iv_b - 1].strip()
    inner_body = s[iv_b:ib - 1]
    # candidate parents: every '(' before ia whose span covers ib
    best = None
    for k, c in scan(s, 0):
        if k >= ia:
            break
        if c != '(':
            continue
        try:
            e = match_end(s, k)
        except ValueError:
            continue
        if e >= ib and head_sym(s, k) in ('let', 'let*'):
            if best is None or k > best[0]:
                best = (k, e)
    if best is None:
        raise ValueError('no enclosing let found')
    pa, pb = best
    pv_a, pv_b = vec_span(s, pa)
    body_forms = forms_between(s, pv_b, pb - 1)
    if body_forms != [inner]:
        raise ValueError(f'parent let body is not exactly the inner let: {body_forms}')
    middle = s[pv_b:ia]  # whitespace/comments between parent vector and inner let
    merged = (s[pa:pv_b - 1] + ' ' + inner_bindings + ']'
              + middle + inner_body + ')')
    return pa, pb, merged

if __name__ == '__main__':
    apply = '--apply' in sys.argv
    args = [a for a in sys.argv[1:] if a != '--apply']
    path, line = args[0], int(args[1])
    s = open(path).read()
    pa, pb, merged = merge(s, line)
    if apply:
        open(path, 'w').write(s[:pa] + merged + s[pb:])
        print(f'merged in {path}: parent span {pa}-{pb} replaced')
    else:
        print(merged[:600])
        print(f'... [merged form: {len(merged)} chars]')
