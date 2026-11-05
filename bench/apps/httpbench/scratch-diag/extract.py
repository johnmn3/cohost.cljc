import re, sys
src = open('/home/hatch/workspace/cohost-httpbench/bench/apps/httpbench/src/httpbench/native.cljc').read()
# find the (cpp/raw " ... ") block: starts after '(cpp/raw "'
i = src.index('(cpp/raw "') + len('(cpp/raw "')
out = []
while True:
    c = src[i]
    if c == '\\':
        nxt = src[i+1]
        out.append({'n':'\n','t':'\t','r':'\r','\\':'\\','"':'"'}.get(nxt, '\\'+nxt))
        i += 2
    elif c == '"':
        break
    else:
        out.append(c); i += 1
raw = ''.join(out)
hdr = '#include "App.h"\n#include "simdjson.h"\n'
open('/home/hatch/workspace/cohost-httpbench/bench/apps/httpbench/scratch-diag/raw_body.cpp','w').write(raw)
open('/home/hatch/workspace/cohost-httpbench/bench/apps/httpbench/scratch-diag/tu.cpp','w').write(hdr + raw)
print('raw bytes:', len(raw))
