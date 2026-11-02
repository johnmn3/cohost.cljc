#!/usr/bin/env python3
# D-4: run a command, print MAXRSS-KB <peak resident set of its largest child>
# and WALL-S <seconds>, then pass its exit code on.
#   python3 -I dev/perf/jank_gc_rss.py <cmd> [args...]
import resource, subprocess, sys, time
t = time.time()
r = subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
w = time.time() - t
print("MAXRSS-KB", resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss, "WALL-S", round(w, 2), "EXIT", r.returncode)
sys.exit(r.returncode)
