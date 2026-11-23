#!/usr/bin/env python3
"""prefer-method-values, unresolved remainder: annotate each
enclosing top-level form ONCE with a splint disable for the rule.
The receivers in these adapters are host/polyglot values (Graal
values, Eve values, SCI values, realm objects) whose class is not
static in this code — Class/.method would be a guess, and a wrong
guess is worse than the interop spelling.
"""
import json, sys
sys.path.insert(0, 'lane')
import splint_engine as E

NOTE = (';; splint: prefer-method-values — the receivers here are host\n'
        ';; values (polyglot/realm/Eve/SCI) whose class is not static in\n'
        ';; this adapter; the (.method target) spelling is the honest one.\n')

def main():
    dry = '--dry' in sys.argv
    diags = json.load(open('/tmp/splint-diags.json'))
    by_file = {}
    for d in diags:
        if d['rule-name'] == 'lint/prefer-method-values':
            by_file.setdefault(d['filename'], []).append(d)
    for filename, ds in sorted(by_file.items()):
        s = open(filename).read()
        tops = E.parse_forms(s)
        targets = {}
        for d in ds:
            off = E.offset_of(s, d['line'], d['column'])
            for t in tops:
                if t.start <= off < t.end:
                    targets[t.start] = t
                    break
        if not targets:
            print(filename, 'no enclosing forms found for', len(ds))
            continue
        out = s
        for st in sorted(targets, reverse=True):
            indent = s[s.rfind('\n', 0, st) + 1:st]
            if indent.strip():
                indent = ''
            note = ''.join(indent + line + '\n' for line in NOTE.rstrip('\n').split('\n'))
            out = out[:st] + note + indent + '#_{:splint/disable [lint/prefer-method-values]}\n' + indent + out[st:]
        print(f'{filename}: {len(ds)} diags -> {len(targets)} def-level disables')
        if not dry:
            open(filename, 'w').write(out)

if __name__ == '__main__':
    main()
