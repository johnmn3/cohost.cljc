#!/usr/bin/env python3
"""Clojure-aware bracket matching for lane surgery.

Skips: string literals (with \\ escapes, incl. regex #\"...\"),
line comments (; ...), char literals (\\x, \\newline, \\uXXXX).
Counts () [] {} only. Good enough for extracting a form and for
the merge-let surgery; every result is verified afterwards by
cljfmt (parse) + clj-kondo + the boot gate before it counts.
"""
import sys

OPEN = {'(': ')', '[': ']', '{': '}'}
CLOSE = {v: k for k, v in OPEN.items()}

def scan(s, i):
    """Yield (index, char) for structural chars starting at i,
    skipping strings, chars and comments."""
    n = len(s)
    while i < n:
        c = s[i]
        if c == ';':
            j = s.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        if c == '"':
            i += 1
            while i < n:
                if s[i] == '\\':
                    i += 2
                    continue
                if s[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == '\\':
            # char literal: \uXXXX, \newline-style word, or single char
            j = i + 1
            if j < n and s[j] == 'u' and j + 4 < n and all(ch in '0123456789abcdefABCDEF' for ch in s[j+1:j+5]):
                i = j + 5
                continue
            if j < n and s[j].isalpha():
                k = j
                while k < n and s[k].isalpha():
                    k += 1
                if k - j > 1:  # named char like \newline \space \tab
                    i = k
                    continue
            i = j + 1
            continue
        yield i, c
        i += 1

def match_end(s, start):
    """Index just past the bracket that closes the one at start."""
    stack = []
    for i, c in scan(s, start):
        if c in OPEN:
            stack.append(c)
        elif c in CLOSE:
            if not stack or stack[-1] != CLOSE[c]:
                raise ValueError(f'unbalanced at {i}: {c!r}')
            stack.pop()
            if not stack:
                return i + 1
    raise ValueError('no closing bracket found')

def form_span_on_line(s, line_no):
    """(start, end) of the first bracketed form starting on line_no (1-based)."""
    lines = s.split('\n')
    off = sum(len(l) + 1 for l in lines[:line_no - 1])
    for i, c in scan(s, off):
        if c in OPEN:
            return i, match_end(s, i)
        if s[i] == '\n':
            break
    raise ValueError(f'no form on line {line_no}')

if __name__ == '__main__':
    path, line = sys.argv[1], int(sys.argv[2])
    s = open(path).read()
    a, b = form_span_on_line(s, line)
    sys.stdout.write(s[a:b])
