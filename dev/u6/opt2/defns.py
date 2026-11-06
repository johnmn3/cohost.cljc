import re, sys, json
src = open(sys.argv[1]).read()
# top-level forms by paren balance, skipping strings, chars and comments
forms = []; i = 0; n = len(src); depth = 0; start = None; line = 1; start_line = None
while i < n:
    c = src[i]
    if c == '\n': line += 1
    if c == ';' :
        while i < n and src[i] != '\n': i += 1
        continue
    if c == '"':
        i += 1
        while i < n and src[i] != '"':
            if src[i] == '\\': i += 1
            elif src[i] == '\n': line += 1
            i += 1
        i += 1; continue
    if c == '\\' and i + 1 < n: i += 2; continue
    if c in '([{':
        if depth == 0: start, start_line = i, line
        depth += 1
    elif c in ')]}':
        depth -= 1
        if depth == 0 and start is not None:
            forms.append((start_line, src[start:i+1])); start = None
    i += 1
out = []
for ln, f in forms:
    m = re.match(r'\((defn-?|defmacro|def)\s+', f)
    if not m or m.group(1) == 'defmacro': continue
    j = m.end()
    # skip metadata: ^{...} (balanced), ^:kw or ^Sym, repeated
    while j < len(f) and f[j] == '^':
        if f[j+1] == '{':
            d = 0; k = j + 1
            while True:
                if f[k] == '{': d += 1
                elif f[k] == '}':
                    d -= 1
                    if d == 0: break
                k += 1
            j = k + 1
        else:
            k = j
            while k < len(f) and not f[k].isspace(): k += 1
            j = k
        while j < len(f) and f[j].isspace(): j += 1
    nm = re.match(r'[^\s\[\]()]+', f[j:]).group(0)
    calls = sorted(set(re.findall(r'cpp/jank\.runtime\.([a-z_0-9]+)', f)))
    if m.group(1) == 'def' and '(fn' not in f: continue
    if calls: out.append({"name": nm, "line": ln, "kind": m.group(1), "runtime": calls})
json.dump(out, open(sys.argv[2], 'w'), indent=0)
print(len(forms), "forms;", len(out), "defns calling cpp/jank.runtime.*")
