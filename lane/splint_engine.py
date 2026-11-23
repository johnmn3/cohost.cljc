#!/usr/bin/env python3
"""Splint fix engine: mini Clojure reader + per-rule tree transforms.

Reads diagnostics from /tmp/splint-diags.json (splint -o json-pretty),
applies transforms for the rules named on the command line, or inserts
#_{:splint/disable [rule]} annotations for rules named with a '!' prefix.
Every replacement is verified: the node at the diagnostic span must
match the transform's expected shape, else the site is reported as
SKIP for hand treatment.
"""
import json, re, sys
sys.path.insert(0, 'lane')
from fmatch import scan

WS = ' \t\r\n,'

class Node:
    __slots__ = ('kind', 'text', 'children', 'start', 'end', 'prefix')
    def __init__(self, kind, start):
        self.kind = kind; self.start = start; self.end = None
        self.text = None; self.children = None; self.prefix = ''

def parse_forms(s):
    """Parse all top-level forms; return list of Nodes with spans."""
    toks = []  # (index, char) structural + atoms handled inline
    return parse_seq(s, 0, None)[0]

def _atom_end(s, i):
    j = i
    while j < len(s) and s[j] not in WS + '()[]{}";':
        j += 1
    return j

def parse_seq(s, i, closer):
    nodes = []
    n = len(s)
    while i < n:
        c = s[i]
        if c in WS:
            i += 1; continue
        if c == ';':
            while i < n and s[i] != '\n': i += 1
            continue
        if closer and c == closer:
            return nodes, i + 1
        if c in ')]}':
            return nodes, i  # caller handles / error upstream
        node, i = parse_one(s, i)
        nodes.append(node)
    return nodes, i

PREFIX_CHARS = "'`~@^"

def parse_one(s, i):
    start = i
    prefix = ''
    n = len(s)
    # reader prefixes: #_ #? #?@ #" ## ' ` ~ ~@ @ ^ (and combinations)
    while i < n:
        two = s[i:i+2]
        if s[i] == '#' and i + 1 < n and s[i+1] in '{([':
            prefix += '#'; i += 1
            continue
        if two == '#"':
            # regex literal: consume only '#'; the string branch below
            # scans from the quote
            prefix += '#'; i += 1
            break
        if two in ('#_', '#?', '##'):
            prefix += two; i += 2
            if two == '#?' and i < n and s[i] == '@':
                prefix += '@'; i += 1
            # skip whitespace/comments after prefix
            while i < n and (s[i] in WS or s[i] == ';'):
                if s[i] == ';':
                    while i < n and s[i] != '\n': i += 1
                else: i += 1
            continue
        if s[i] in PREFIX_CHARS:
            prefix += s[i]; i += 1
            if prefix.endswith('~') and i < n and s[i] == '@':
                prefix += '@'; i += 1
            while i < n and (s[i] in WS or s[i] == ';'):
                if s[i] == ';':
                    while i < n and s[i] != '\n': i += 1
                else: i += 1
            continue
        break
    c = s[i]
    if c == '"':
        j = i + 1
        while j < n:
            if s[j] == '\\': j += 2; continue
            if s[j] == '"': break
            j += 1
        node = Node('string', start); node.text = s[i:j+1]; node.end = j + 1; node.prefix = prefix
        return node, j + 1
    if c == '\\':
        # char literal: backslash + one char; a following alphanumeric
        # run belongs to named chars (\newline, \u0041, \o101)
        j = i + 2
        if j <= n and i + 1 < n and s[i+1].isalnum():
            while j < n and s[j].isalnum():
                j += 1
        node = Node('char', start); node.text = s[i:j]; node.end = j; node.prefix = prefix
        return node, j
    if c in '([{':
        kind = {'(': 'list', '[': 'vec', '{': 'map'}[c]
        close = {'(': ')', '[': ']', '{': '}'}[c]
        children, j = parse_seq(s, i + 1, close)
        node = Node(kind, start); node.children = children; node.end = j; node.prefix = prefix
        return node, j
    j = _atom_end(s, i)
    node = Node('atom', start); node.text = s[i:j]; node.end = j; node.prefix = prefix
    return node, j

def render(node):
    if node.children is not None:
        open_c, close_c = {'list': '()', 'vec': '[]', 'map': '{}'}[node.kind]
        inner = ' '.join(render(ch) for ch in node.children)
        return node.prefix + open_c[0] + inner + close_c[1]
    return node.prefix + node.text

def raw(s, node):
    return s[node.start:node.end]

def head_sym(node):
    if node.kind == 'list' and node.children and node.children[0].kind == 'atom':
        return node.children[0].text
    return None

def find_at(nodes, offset):
    for nd in nodes:
        if nd.start == offset:
            return nd
        if nd.children and nd.start < offset < nd.end:
            r = find_at(nd.children, offset)
            if r is not None:
                return r
    return None

def offset_of(s, line, col):
    off = 0
    for _ in range(line - 1):
        off = s.index('\n', off) + 1
    return off + col - 1

# ---------------- transforms: (s, node, ctx) -> new_text or None ----------

def t_fn_wrapper(s, node, ctx):
    if node.kind != 'list' or head_sym(node) not in ('fn', 'fn*'):
        return None
    ch = node.children
    if len(ch) != 3 or ch[1].kind != 'vec' or len(ch[1].children) != 1:
        return None
    param = ch[1].children[0]
    if param.kind != 'atom':
        return None
    body = ch[2]
    if body.kind != 'list' or len(body.children) != 2:
        return None
    if body.children[1].kind != 'atom' or body.children[1].text != param.text:
        return None
    h = body.children[0]
    if h.kind == 'atom':
        # constructor thunks (Class.) and method heads (.foo) are not
        # values in the driver — gate-proven BOOT-S1 (CNFE LazySeq)
        if h.text.endswith('.') or h.text.startswith('.'):
            return None
        return raw(s, h)
    if h.prefix == '@':
        return raw(s, h)
    return None

def t_redundant_call(s, node, ctx):
    if node.kind == 'list' and head_sym(node) in ('merge', 'comp') and len(node.children) >= 2:
        arg = s[node.children[1].start:node.children[-1].end]
        inner = parse_forms(arg)
        if len(inner) == 1 and inner[0].start == 0 and inner[0].end == len(arg):
            return arg
    return None

def t_redundant_nested(s, node, ctx):
    if node.kind != 'list' or head_sym(node) not in ('and', 'or'):
        return None
    h = head_sym(node)
    out = [node.children[0]]
    changed = False
    for ch in node.children[1:]:
        if ch.kind == 'list' and head_sym(ch) == h:
            out.extend(ch.children[1:]); changed = True
        else:
            out.append(ch)
    if not changed:
        return None
    return '(' + ' '.join(raw(s, x) for x in out) + ')'

def t_assoc_fn(s, node, ctx):
    if node.kind != 'list' or head_sym(node) != 'assoc' or len(node.children) < 4:
        return None
    m, k, v = node.children[1], node.children[2], node.children[3]
    rest = node.children[4:]
    if v.kind != 'list' or not v.children:
        return None
    f = v.children[0]
    args = v.children[1:]
    if not args:
        return None
    getter = args[0]
    ok = False
    if getter.kind == 'list' and head_sym(getter) == 'get' and len(getter.children) == 3 \
            and raw(s, getter.children[1]) == raw(s, m) and raw(s, getter.children[2]) == raw(s, k):
        ok = True
    if getter.kind == 'list' and len(getter.children) == 2 and getter.children[0].kind == 'atom' \
            and getter.children[0].text.startswith(':') and raw(s, getter.children[1]) == raw(s, m) \
            and getter.children[0].text == (k.text if k.kind == 'atom' else None):
        ok = True
    if not ok:
        return None
    parts = ['update', raw(s, m), raw(s, k), raw(s, f)] + [raw(s, a) for a in args[1:]] + [raw(s, r) for r in rest]
    return '(' + ' '.join(parts) + ')'

def t_not_some(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'not' and len(node.children) == 2:
        inner = node.children[1]
        if inner.kind == 'list' and head_sym(inner) == 'some' and len(inner.children) == 3:
            return '(not-any? ' + raw(s, inner.children[1]) + ' ' + raw(s, inner.children[2]) + ')'
    return None

def t_tostring(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == '.toString':
        if len(node.children) == 2:
            return '(str ' + raw(s, node.children[1]) + ')'
        if len(node.children) == 3 and node.children[1].prefix == '^':
            return '(str ' + raw(s, node.children[1]) + ' ' + raw(s, node.children[2]) + ')'
    return None

def t_minus_one(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == '-' and len(node.children) == 3 \
            and node.children[2].kind == 'atom' and node.children[2].text == '1':
        return '(dec ' + raw(s, node.children[1]) + ')'
    return None

def t_plus_one(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == '+' and len(node.children) == 3:
        a, b = node.children[1], node.children[2]
        if a.kind == 'atom' and a.text == '1':
            return '(inc ' + raw(s, b) + ')'
        if b.kind == 'atom' and b.text == '1':
            return '(inc ' + raw(s, a) + ')'
    return None

def t_neg_checks(s, node, ctx):
    if node.kind == 'list' and len(node.children) == 3:
        h = head_sym(node)
        a, b = node.children[1], node.children[2]
        if h == '<' and b.kind == 'atom' and b.text == '0':
            return '(neg? ' + raw(s, a) + ')'
        if h == '>' and a.kind == 'atom' and a.text == '0':
            return '(neg? ' + raw(s, b) + ')'
    return None

def t_filterv(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'vec' and len(node.children) == 2:
        inner = node.children[1]
        if inner.kind == 'list' and head_sym(inner) == 'filter' and len(inner.children) == 3:
            return '(filterv ' + raw(s, inner.children[1]) + ' ' + raw(s, inner.children[2]) + ')'
    return None

def t_into_literal(s, node, ctx):
    if node.kind != 'list' or head_sym(node) != 'into':
        return None
    ch = node.children
    target = ch[1]
    is_set = target.kind == 'set' or (target.kind == 'list' and False)
    ttext = raw(s, target)
    if ttext not in ('#{}', '[]', '{}'):
        return None
    fn = {'#{}': 'set', '[]': 'vec', '{}': None}[ttext]
    if fn is None:
        return None
    if len(ch) == 3:
        return '(' + fn + ' ' + raw(s, ch[2]) + ')'
    if len(ch) == 4 and ch[2].kind == 'atom' and ch[2].text == 'nil':
        return '(' + fn + ' ' + raw(s, ch[3]) + ')'
    return None

def t_vary_meta(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'with-meta' and len(node.children) == 3:
        x, m = node.children[1], node.children[2]
        if m.kind == 'list' and len(m.children) == 2:
            inner = m.children[1]
            if inner.kind == 'list' and head_sym(inner) == 'meta' and len(inner.children) == 2 \
                    and raw(s, inner.children[1]) == raw(s, x):
                return '(vary-meta ' + raw(s, x) + ' ' + raw(s, m.children[0]) + ')'
    return None

def _str_alias(s, ctx):
    return ctx['str_alias']  # e.g. 'str' or 'clojure.string' or None

def t_apply_str(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'apply' and len(node.children) == 3 \
            and node.children[1].kind == 'atom' and node.children[1].text == 'str':
        a = _str_alias(s, ctx)
        if a is None: return None
        return '(' + a + '/join ' + raw(s, node.children[2]) + ')'
    return None

def t_apply_str_interpose(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'apply' and len(node.children) == 3 \
            and node.children[1].kind == 'atom' and node.children[1].text == 'str':
        inner = node.children[2]
        if inner.kind == 'list' and head_sym(inner) == 'interpose' and len(inner.children) == 3:
            a = _str_alias(s, ctx)
            if a is None: return None
            return '(' + a + '/join ' + raw(s, inner.children[1]) + ' ' + raw(s, inner.children[2]) + ')'
    return None

def t_apply_str_reverse(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'apply' and len(node.children) == 3 \
            and node.children[1].kind == 'atom' and node.children[1].text == 'str':
        inner = node.children[2]
        if inner.kind == 'list' and head_sym(inner) == 'reverse' and len(inner.children) == 2:
            a = _str_alias(s, ctx)
            if a is None: return None
            return '(' + a + '/join (reverse ' + raw(s, inner.children[1]) + '))'
    return None

STR_METHODS = {'startsWith': 'starts-with?', 'endsWith': 'ends-with?',
               'contains': 'includes?', 'toUpperCase': 'upper-case',
               'toLowerCase': 'lower-case'}

def t_prefer_clj_string(s, node, ctx):
    if node.kind != 'list' or not node.children or node.children[0].kind != 'atom':
        return None
    h = node.children[0].text
    if not h.startswith('.'):
        return None
    m = h[1:]
    if m not in STR_METHODS:
        return None
    a = _str_alias(s, ctx)
    if a is None: return None
    args = ' '.join(raw(s, x) for x in node.children[1:])
    return '(' + a + '/' + STR_METHODS[m] + ' ' + args + ')'

def t_prefer_clj_math(s, node, ctx):
    if node.kind == 'atom' and node.text.startswith('Math/'):
        return 'clojure.math/' + node.text[5:]
    return None

def t_let_if(s, node, ctx):
    if node.kind != 'list' or head_sym(node) != 'let' or len(node.children) != 3:
        return None
    binds = node.children[1]
    if binds.kind != 'vec' or len(binds.children) != 2:
        return None
    sym = binds.children[0]
    body = node.children[2]
    if body.kind != 'list' or head_sym(body) not in ('if', 'when'):
        return None
    test = body.children[1]
    if test.kind != 'atom' or test.text != sym.text:
        return None
    new_head = 'if-let' if head_sym(body) == 'if' else 'when-let'
    parts = [new_head, raw(s, binds)] + [raw(s, x) for x in body.children[2:]]
    return '(' + ' '.join(parts) + ')'

def t_if_nil(s, node, ctx):
    if node.kind != 'list' or head_sym(node) != 'if':
        return None
    discarded = [c for c in node.children[1:] if c.prefix.startswith('#_')]
    eff = [c for c in node.children if not c.prefix.startswith('#_')]
    if len(eff) != 4:
        return None
    test, a, b = eff[1], eff[2], eff[3]
    pre = (' '.join(raw(s, d) for d in discarded) + ' ') if discarded else ''
    if b.kind == 'atom' and b.text == 'nil':
        return '(when ' + pre + raw(s, test) + ' ' + raw(s, a) + ')'
    if a.kind == 'atom' and a.text == 'nil':
        return '(when-not ' + pre + raw(s, test) + ' ' + raw(s, b) + ')'
    return None

def t_next_next(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'next' and len(node.children) == 2:
        inner = node.children[1]
        if inner.kind == 'list' and head_sym(inner) == 'next' and len(inner.children) == 2:
            return '(nnext ' + raw(s, inner.children[1]) + ')'
    return None

def t_loop_empty_when(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'loop' and len(node.children) == 3:
        binds, body = node.children[1], node.children[2]
        if binds.kind == 'vec' and len(binds.children) == 0 and body.kind == 'list' \
                and head_sym(body) == 'when' and len(body.children) >= 3:
            last = body.children[-1]
            if last.kind == 'list' and head_sym(last) == 'recur' and len(last.children) == 1:
                parts = ['while', raw(s, body.children[1])] + [raw(s, x) for x in body.children[2:-1]]
                return '(' + ' '.join(parts) + ')'
    return None

def t_if_same_truthy(s, node, ctx):
    if node.kind == 'list' and head_sym(node) == 'if' and len(node.children) == 4:
        test, a, b = node.children[1], node.children[2], node.children[3]
        if raw(s, test) == raw(s, a):
            return '(or ' + raw(s, test) + ' ' + raw(s, b) + ')'
    return None

TRANSFORMS = {
    'lint/fn-wrapper': t_fn_wrapper,
    'lint/redundant-call': t_redundant_call,
    'style/redundant-nested-call': t_redundant_nested,
    'lint/assoc-fn': t_assoc_fn,
    'style/not-some-pred': t_not_some,
    'style/tostring': t_tostring,
    'style/minus-one': t_minus_one,
    'style/plus-one': t_plus_one,
    'style/neg-checks': t_neg_checks,
    'style/filter-vec-filterv': t_filterv,
    'lint/into-literal': t_into_literal,
    'style/prefer-vary-meta': t_vary_meta,
    'style/apply-str': t_apply_str,
    'style/apply-str-interpose': t_apply_str_interpose,
    'style/apply-str-reverse': t_apply_str_reverse,
    'style/prefer-clj-string': t_prefer_clj_string,
    'style/prefer-clj-math': t_prefer_clj_math,
    'lint/let-if': t_let_if,
    'lint/let-when': t_let_if,
    'lint/if-nil-else': t_if_nil,
    'lint/if-else-nil': t_if_nil,
    'style/next-next': t_next_next,
    'lint/loop-empty-when': t_loop_empty_when,
    'lint/if-same-truthy': t_if_same_truthy,
}

def str_alias_for(src, filename):
    """Find clojure.string alias in the ns form; None if absent."""
    m = re.search(r'\[clojure\.string\s+:as\s+([\w.-]+)\]', src[:4000])
    if m: return m.group(1)
    if re.search(r'\[clojure\.string\]', src[:4000]): return 'clojure.string'
    return None

DISABLE_NOTES = {
    'lint/catch-throwable': 'catching Throwable is the isolation contract: host/seat failures must not escape this boundary',
    'lint/locking-object': 'the lock object is minted once with the seat state and shared by design (see the kondo note)',
    'style/new-object': 'the (new (aget ..) f) spelling is the squint-seat-proven constructor form',
    'style/prefer-clj-string': 'not equivalent: .split/.replace take a regex string with JS semantics; clojure.string/split wants a Pattern and replace differs on string matches',
    'style/apply-str': 'shim is the substrate the stock string lib itself calls into; it cannot depend on clojure.string',
    'style/apply-str-interpose': 'shim is the substrate the stock string lib itself calls into; it cannot depend on clojure.string',
    'style/apply-str-reverse': 'shim is the substrate the stock string lib itself calls into; it cannot depend on clojure.string',
    'lint/fn-wrapper': 'the eta-expansion is load-bearing (late var resolution during staged boot, or a constructor, which is not a value here)',
}

def main():
    args = sys.argv[1:]
    dry = '--dry' in args
    rules = [a for a in args if not a.startswith('--')]
    transform_rules = [r for r in rules if not r.startswith('!')]
    disable_rules = [r[1:] for r in rules if r.startswith('!')]
    skip_files = set()
    if '--skip' in args:
        i = args.index('--skip'); skip_files = set(args[i+1].split(','))
    diags = json.load(open('/tmp/splint-diags.json'))
    by_file = {}
    for d in diags:
        if d['rule-name'] in transform_rules or d['rule-name'] in disable_rules:
            by_file.setdefault(d['filename'], []).append(d)
    total_ok = total_skip = 0
    for filename, ds in sorted(by_file.items()):
        if filename in skip_files:
            print(f'SKIPFILE {filename} ({len(ds)} diags)'); total_skip += len(ds); continue
        s = open(filename).read()
        tops = parse_forms(s)
        alias = str_alias_for(s, filename)
        ctx = {'str_alias': alias if alias else ('str' if transform_rules and any(r in ('style/apply-str','style/apply-str-interpose','style/apply-str-reverse','style/prefer-clj-string') for r in transform_rules) and '--add-require' in args else None),
               'needs_require': False}
        if ctx['str_alias'] == 'str' and alias is None:
            ctx['needs_require'] = True
        repls = []  # (start, end, newtext, rule)
        for d in ds:
            off = offset_of(s, d['line'], d['column'])
            node = find_at(tops, off)
            if node is None:
                print(f'  NONODE {filename}:{d["line"]} {d["rule-name"]}'); total_skip += 1; continue
            if d['rule-name'] in disable_rules:
                indent = s[s.rfind('\n', 0, off) + 1:off]
                if indent.strip():
                    indent = ''
                note = DISABLE_NOTES.get(d['rule-name'], '')
                note_txt = (';; splint: ' + note + '\n' + indent) if note else ''
                repls.append((off, off, note_txt + '#_{:splint/disable [' + d['rule-name'] + ']}\n' + indent, d['rule-name'] + ' (disable)'))
                total_ok += 1
                continue
            fn = TRANSFORMS[d['rule-name']]
            try:
                new = fn(s, node, ctx)
            except Exception as e:
                new = None
                print(f'  EXC {filename}:{d["line"]} {d["rule-name"]}: {e}')
            if new is None:
                print(f'  SKIP {filename}:{d["line"]} {d["rule-name"]} :: {raw(s, node)[:70]!r}')
                total_skip += 1
            else:
                repls.append((node.start, node.end, new, d['rule-name']))
                total_ok += 1
        if not repls:
            continue
        repls.sort(key=lambda r: -r[0])
        out = s
        last_start = len(s) + 1
        for st, en, new, rule in repls:
            if en > last_start:
                print(f'  OVERLAP {filename} {rule}'); continue
            out = out[:st] + new + out[en:]
            last_start = st
        if ctx['needs_require'] and any(r[3].startswith(('style/apply-str', 'style/prefer-clj-string')) for r in repls):
            tops2 = parse_forms(out)
            ns = next((t for t in tops2 if t.kind == 'list' and head_sym(t) == 'ns'), None)
            done = False
            if ns is not None:
                req = next((c for c in ns.children if c.kind == 'list' and c.children
                            and c.children[0].kind == 'atom' and c.children[0].text == ':require'), None)
                if req is not None:
                    # insert as first entry after the :require head
                    pos = req.children[0].end
                    out = out[:pos] + '\n    [clojure.string :as str]' + out[pos:]
                    done = True
                else:
                    pos = ns.end - 1
                    out = out[:pos] + '\n  (:require [clojure.string :as str])' + out[pos:]
                    done = True
            print(('  REQUIRE+ ' if done else '  REQUIRE-FAIL ') + filename)
        if not dry:
            open(filename, 'w').write(out)
        print(f'{filename}: {len(repls)} applied')
    print(f'TOTAL ok={total_ok} skip={total_skip}')

if __name__ == '__main__':
    main()
