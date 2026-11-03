# A5-2: pair `src` / `   => value` lines of a cohost --eval log with the JVM's `src => value` lines.
#   python3 -I compare.py <jvm-log> <cohost-log>
import sys
jvm = {}
for line in open(sys.argv[1]):
    if ' => ' in line:
        s, v = line.rstrip('\n').split(' => ', 1); jvm[s] = v
got, cur = {}, None
for line in open(sys.argv[2]):
    l = line.rstrip('\n')
    if l.startswith('   => ') and cur is not None:
        got[cur] = l[6:]; cur = None
    elif l in jvm:
        cur = l
same = diff = 0
for s, v in jvm.items():
    if s not in got: print(f"MISSING  {s}  (JVM {v})"); diff += 1
    elif got[s] != v: print(f"DIFF     {s}  cohost {got[s]}  JVM {v}"); diff += 1
    else: same += 1
print(f"same {same} / {len(jvm)}, differ or missing {diff}")
