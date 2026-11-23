#!/usr/bin/env python3
"""prefer-method-values: rewrite (.meth target args) to
(Class/.meth target args) where the target's class is provable from
local evidence only:
  - target is a constructor call (Class. ...)
  - target is (io/file ...) / (clojure.java.io/file ...) -> java.io.File
  - target carries a ^Class hint in the call
  - target is a string literal, or bound (let/fn param) to one of the
    above, or to a string literal / (str ...) -> java.lang.String
  - target is a fn param with a ^Class hint
Anything else is left for the disable pass. Only nodes whose span
start matches a splint diagnostic are rewritten.
"""
import json, sys
sys.path.insert(0, 'lane')
import splint_engine as E

def class_of_target(s, node, scopes):
    """Return class name string or None. scopes: list of dicts
    sym -> (init_node_or_None, hint_or_None), innermost last."""
    if node.kind == 'string':
        return 'java.lang.String'
    if node.kind == 'list' and node.children:
        h = node.children[0]
        if h.kind == 'atom':
            if h.text.endswith('.') and len(h.text) > 1 and h.text[0].isupper():
                return h.text[:-1]
            if h.text in ('io/file', 'clojure.java.io/file', 'io/as-file'):
                return 'java.io.File'
            if h.text == 'str':
                return 'java.lang.String'
        return None
    if node.kind == 'atom':
        name = node.text
        for scope in reversed(scopes):
            if name in scope:
                init, hint = scope[name]
                if hint:
                    return hint
                if init is not None:
                    return class_of_target(s, init, scopes)
                return None
        return None
    # hinted node: (^Class x) appears as meta-prefixed node
    if node.prefix == '^' and node.kind == 'atom':
        return None
    return None

def collect_bindings(s, vec_node, scopes, is_bindings=False):
    """vec of [sym init sym init ...] or fn params [sym ...]."""
    out = {}
    ch = vec_node.children
    i = 0
    pending_hint = None
    while i < len(ch):
        nd = ch[i]
        if nd.prefix == '^' and nd.kind == 'atom':
            pending_hint = nd.text
            i += 1
            continue
        if nd.kind == 'atom':
            # could be a plain param (no init) or a binding sym
            if is_bindings and i + 1 < len(ch):
                out[nd.text] = (ch[i+1], pending_hint)
                i += 2
            else:
                out[nd.text] = (None, pending_hint)
                i += 1
            pending_hint = None
            continue
        i += 1
    return out

def transform_file(s, flagged):
    tops = E.parse_forms(s)
    repls = []

    def visit(node, scopes):
        # scope-introducing forms
        new_scopes = scopes
        h = E.head_sym(node) if node.kind == 'list' else None
        if node.kind == 'list' and h in ('let', 'let*', 'loop', 'binding', 'when-let', 'if-let', 'doseq', 'for'):
            if len(node.children) >= 2 and node.children[1].kind == 'vec':
                new_scopes = scopes + [collect_bindings(s, node.children[1], scopes, is_bindings=True)]
        elif node.kind == 'list' and h in ('fn', 'fn*', 'defn', 'defn-', 'defmethod', 'letfn'):
            # params: find vec children that look like param vectors
            for ch in node.children:
                if ch.kind == 'vec':
                    new_scopes = scopes + [collect_bindings(s, ch, scopes)]
                    break
        elif node.kind == 'vec' and node.prefix == '#':
            pass
        # the method call itself
        if node.kind == 'list' and node.children and node.children[0].kind == 'atom' \
                and node.children[0].text.startswith('.') and len(node.children[0].text) > 1 \
                and node.start in flagged:
            meth = node.children[0].text[1:]
            ch = node.children
            cls = None
            target = None
            rest = []
            if len(ch) >= 3 and ch[1].prefix == '^' and ch[1].kind == 'atom':
                cls = ch[1].text
                target = ch[2]
                rest = ch[3:]
            elif len(ch) >= 2:
                target = ch[1]
                rest = ch[2:]
                cls = class_of_target(s, target, new_scopes)
            if cls and target is not None:
                parts = [cls + '/.' + meth, E.raw(s, target)] + [E.raw(s, a) for a in rest]
                repls.append((node.start, node.end, '(' + ' '.join(parts) + ')'))
        if node.children:
            for ch in node.children:
                visit(ch, new_scopes)

    for t in tops:
        visit(t, [])
    return repls

def main():
    dry = '--dry' in sys.argv
    diags = json.load(open('/tmp/splint-diags.json'))
    by_file = {}
    for d in diags:
        if d['rule-name'] == 'lint/prefer-method-values':
            by_file.setdefault(d['filename'], set()).add(
                E.offset_of(open(d['filename']).read(), d['line'], d['column']))
    total = 0
    for filename, offsets in sorted(by_file.items()):
        s = open(filename).read()
        # recompute offsets on the same text splint saw (file unchanged since)
        flagged = set()
        for d in diags:
            if d['rule-name'] == 'lint/prefer-method-values' and d['filename'] == filename:
                flagged.add(E.offset_of(s, d['line'], d['column']))
        repls = transform_file(s, flagged)
        repls.sort(key=lambda r: -r[0])
        out = s
        last = len(s) + 1
        applied = 0
        for st, en, new in repls:
            if en > last:
                continue
            out = out[:st] + new + out[en:]
            last = st
            applied += 1
        print(f'{filename}: {applied}/{len(flagged)} resolved')
        total += applied
        if not dry and applied:
            open(filename, 'w').write(out)
    print('TOTAL resolved', total)

if __name__ == '__main__':
    main()
