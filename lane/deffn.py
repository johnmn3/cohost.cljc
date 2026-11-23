#!/usr/bin/env python3
"""def-fn converter: (def NAME (fn ...)) -> (defn NAME ...) /
(defn- NAME ...) preserving docstrings, attr-maps and multi-arity
bodies. Skips (reported) any shape it cannot prove:
  - fn node carries a prefix (reader conditional, quote, meta)
  - named fn whose self-name differs from the def name
  - name meta other than a lone ^:private
  - attr-map other than exactly {:private true} is KEPT as a defn
    attr-map (legal), {:private true} folds into defn-
Only nodes whose span matches a splint style/def-fn diagnostic are
converted.
"""
import json, sys
sys.path.insert(0, 'lane')
import splint_engine as E

def convert(s, node):
    if node.kind != 'list' or E.head_sym(node) != 'def' or node.prefix:
        return None
    ch = list(node.children[1:])
    meta = None
    if ch and ch[0].prefix == '^' and ch[0].kind == 'atom':
        meta = ch.pop(0)
    if len(ch) < 2:
        return None
    name = ch[0]
    if name.kind != 'atom' or name.prefix:
        return None
    rest = ch[1:]
    doc = None
    if rest and rest[0].kind == 'string':
        doc = rest.pop(0)
    attr = None
    if rest and rest[0].kind == 'map':
        attr = rest.pop(0)
    if len(rest) != 1:
        return None
    fn = rest[0]
    if fn.prefix or fn.kind != 'list' or E.head_sym(fn) not in ('fn', 'fn*'):
        return None
    fch = fn.children[1:]
    # named fn?
    if fch and fch[0].kind == 'atom':
        if fch[0].text != name.text:
            return None
        fch = fch[1:]
    if not fch:
        return None
    private = False
    meta_txt = None
    if meta is not None:
        if meta.text == ':private':
            private = True
        elif meta.text.startswith(':'):
            meta_txt = '^' + meta.text
        else:
            return None
    attr_txt = None
    if attr is not None:
        atext = E.raw(s, attr)
        if atext.replace(' ', '') == '{:privatetrue}':
            private = True
        else:
            attr_txt = atext
    head = 'defn-' if private else 'defn'
    parts = [head, (meta_txt + ' ' if meta_txt else '') + name.text]
    if doc is not None:
        parts.append(E.raw(s, doc))
    if attr_txt is not None:
        parts.append(attr_txt)
    if fch[0].kind == 'vec':
        # single arity: [args] body...
        parts.append(E.raw(s, fch[0]))
        parts.extend(E.raw(s, b) for b in fch[1:])
    elif all(c.kind == 'list' for c in fch):
        # multi arity: ([args] body) ...
        parts.extend(E.raw(s, c) for c in fch)
    else:
        return None
    return '(' + ' '.join(parts) + ')'

def main():
    dry = '--dry' in sys.argv
    diags = json.load(open('/tmp/splint-diags.json'))
    by_file = {}
    for d in diags:
        if d['rule-name'] == 'style/def-fn':
            by_file.setdefault(d['filename'], []).append(d)
    total_ok = total_skip = 0
    for filename, ds in sorted(by_file.items()):
        s = open(filename).read()
        tops = E.parse_forms(s)
        repls = []
        for d in ds:
            off = E.offset_of(s, d['line'], d['column'])
            found = E.find_at(tops, off)
            node = found[0] if found else None
            if node is None:
                print(f'  NONODE {filename}:{d["line"]}'); total_skip += 1; continue
            new = convert(s, node)
            if new is None:
                print(f'  SKIP {filename}:{d["line"]} :: {E.raw(s, node)[:70]!r}')
                total_skip += 1
            else:
                repls.append((node.start, node.end, new))
                total_ok += 1
        repls.sort(key=lambda r: -r[0])
        out = s
        last = len(s) + 1
        for st, en, new in repls:
            if en > last:
                print(f'  OVERLAP {filename}'); continue
            out = out[:st] + new + out[en:]
            last = st
        if not dry and repls:
            open(filename, 'w').write(out)
        print(f'{filename}: {len(repls)} converted')
    print(f'TOTAL ok={total_ok} skip={total_skip}')

if __name__ == '__main__':
    main()
