#!/usr/bin/env python3
"""tests for tools/vendor.py, over local git repositories (no network): python3 tools/test_vendor.py"""
import os, subprocess, sys, tempfile, unittest

TOOL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor.py")


def sh(cwd, *args):
    r = subprocess.run(list(args), cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(f"{args}: {r.stderr}")
    return r.stdout


class Upstream:
    def __init__(self, d):
        self.d = d
        os.makedirs(d)
        sh(d, "git", "init", "-q", "-b", "main")
        sh(d, "git", "config", "user.email", "t@t")
        sh(d, "git", "config", "user.name", "t")
        sh(d, "git", "config", "uploadpack.allowAnySHA1InWant", "true")

    def commit(self, files, msg="c", delete=()):
        for p, t in files.items():
            fp = os.path.join(self.d, p)
            os.makedirs(os.path.dirname(fp), exist_ok=True)
            with open(fp, "w") as h:
                h.write(t)
        for p in delete:
            sh(self.d, "git", "rm", "-q", p)
        sh(self.d, "git", "add", "-A")
        sh(self.d, "git", "commit", "-q", "-m", msg)
        return sh(self.d, "git", "rev-parse", "HEAD").strip()


LIB = "(ns lib.core)\n\n(defn a [] 1)\n\n(defn b [] 2)\n\n(defn c [] 3)\n"


class VendorTest(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.mkdtemp()
        os.environ["COHOST_VENDOR_CACHE"] = os.path.join(self.t, "cache")
        self.up = Upstream(os.path.join(self.t, "up"))
        self.c1 = self.up.commit({"src/lib/core.cljc": LIB, "src/lib/util.cljc": "(ns lib.util)\n",
                                  "hosts.edn": '{:vs ["adapters/vs"] :jvm ["adapters/jvm"]}\n',
                                  "adapters/vs/a.vs": "vs\n", "adapters/jvm/a.clj": "jvm\n",
                                  "README.md": "not vendored\n"})
        self.root = os.path.join(self.t, "consumer")
        os.makedirs(self.root)

    def declare(self, sha):
        open(os.path.join(self.root, "deps.edn"), "w").write(
            '{:paths ["src"]\n :deps {}\n ;; vendored as source\n :cohost/vendor\n'
            f' {{up/lib {{:git/url "{self.up.d}" :git/sha "{sha}" :dest "vendor/lib"\n'
            '            :paths ["src" "hosts.edn"] :paths-from {"hosts.edn" :vs}}}}}\n')

    def run_tool(self, *args, rc=0):
        r = subprocess.run([sys.executable, TOOL, *args, "--root", self.root], capture_output=True, text=True)
        self.assertEqual(r.returncode, rc, r.stdout + r.stderr)
        return r.stdout + r.stderr

    def v(self, p):
        return open(os.path.join(self.root, "vendor/lib", p)).read()

    def edit(self, p, f):
        fp = os.path.join(self.root, "vendor/lib", p)
        with open(fp) as h:
            t = f(h.read())
        with open(fp, "w") as h:
            h.write(t)

    def test_materialize_status_check(self):
        self.declare(self.c1)
        self.run_tool("sync")
        self.assertEqual(self.v("src/lib/core.cljc"), LIB)
        self.assertTrue(os.path.isfile(os.path.join(self.root, "vendor/lib/adapters/vs/a.vs")))
        self.assertFalse(os.path.exists(os.path.join(self.root, "vendor/lib/adapters/jvm")))
        self.assertFalse(os.path.exists(os.path.join(self.root, "vendor/lib/README.md")))
        self.assertIn("0 local edit(s)", self.run_tool("status"))
        self.assertIn("OK", self.run_tool("check"))

    def test_carry_edits_onto_new_pin(self):
        self.declare(self.c1)
        self.run_tool("sync")
        # ours: a VS-only edit under a reader conditional, a new file, a deleted file
        self.edit("src/lib/core.cljc", lambda s: s.replace("(defn a [] 1)", "(defn a [] #?(:vs 10 :default 1))"))
        open(os.path.join(self.root, "vendor/lib/src/lib/new.cljc"), "w").write("(ns lib.new)\n")
        os.remove(os.path.join(self.root, "vendor/lib/src/lib/util.cljc"))
        out = self.run_tool("status")
        self.assertIn("M vendor/lib/src/lib/core.cljc", out)
        self.assertIn("A vendor/lib/src/lib/new.cljc", out)
        self.assertIn("D vendor/lib/src/lib/util.cljc", out)
        self.assertIn("+(defn a [] #?(:vs 10 :default 1))", self.run_tool("diff"))
        # theirs: a change elsewhere in the same file, plus a new upstream file
        c2 = self.up.commit({"src/lib/core.cljc": LIB.replace("(defn c [] 3)", "(defn c [] 33)"),
                             "src/lib/more.cljc": "(ns lib.more)\n"})
        self.declare(c2)
        self.assertIn("BEHIND", self.run_tool("status"))
        self.run_tool("check", rc=1)
        self.run_tool("sync")
        core = self.v("src/lib/core.cljc")
        self.assertIn("#?(:vs 10 :default 1)", core)
        self.assertIn("(defn c [] 33)", core)
        self.assertEqual(self.v("src/lib/more.cljc"), "(ns lib.more)\n")
        self.assertEqual(self.v("src/lib/new.cljc"), "(ns lib.new)\n")
        self.assertFalse(os.path.exists(os.path.join(self.root, "vendor/lib/src/lib/util.cljc")))
        self.assertIn(c2, open(os.path.join(self.root, "vendor/lib/PIN")).read())
        self.assertIn("OK", self.run_tool("check"))
        out = self.run_tool("status")
        self.assertIn("3 local edit(s)", out)   # the edits are still ours, now against the new base

    def test_conflict_named(self):
        self.declare(self.c1)
        self.run_tool("sync")
        self.edit("src/lib/core.cljc", lambda s: s.replace("(defn b [] 2)", "(defn b [] :ours)"))
        c2 = self.up.commit({"src/lib/core.cljc": LIB.replace("(defn b [] 2)", "(defn b [] :theirs)")})
        self.declare(c2)
        out = self.run_tool("sync", rc=4)
        self.assertIn("CONFLICT vendor/lib/src/lib/core.cljc", out)
        core = self.v("src/lib/core.cljc")
        self.assertIn("<<<<<<< vendored (ours)", core)
        self.assertIn(":ours", core)
        self.assertIn(":theirs", core)
        self.assertIn("conflict markers", self.run_tool("check", rc=1))

    def test_upstream_took_our_edit(self):
        self.declare(self.c1)
        self.run_tool("sync")
        self.edit("src/lib/core.cljc", lambda s: s.replace("(defn b [] 2)", "(defn b [] 22)"))
        c2 = self.up.commit({"src/lib/core.cljc": LIB.replace("(defn b [] 2)", "(defn b [] 22)")})
        self.declare(c2)
        self.run_tool("sync")
        self.assertIn("0 local edit(s)", self.run_tool("status"))

    def test_contribute_branch_and_patch(self):
        self.declare(self.c1)
        self.run_tool("sync")
        self.edit("src/lib/core.cljc", lambda s: s.replace("(defn b [] 2)", "(defn b [] #?(:vs 2.0 :default 2))"))
        open(os.path.join(self.root, "vendor/lib/src/lib/new.cljc"), "w").write("(ns lib.new)\n")
        self.run_tool("contribute", "up/lib", "--branch", "fix/b", "-m", "lib: b on vs")
        log = sh(self.up.d, "git", "log", "--format=%s %P", "fix/b", "-1")
        self.assertTrue(log.startswith("lib: b on vs " + self.c1), log)
        self.assertIn("#?(:vs 2.0", sh(self.up.d, "git", "show", "fix/b:src/lib/core.cljc"))
        self.assertEqual(sh(self.up.d, "git", "show", "fix/b:src/lib/new.cljc"), "(ns lib.new)\n")
        # the patch form applies to upstream at the base
        patch = os.path.join(self.t, "edits.patch")
        self.run_tool("contribute", "up/lib", "--patch", patch)
        clone = os.path.join(self.t, "clone")
        sh(self.t, "git", "clone", "-q", self.up.d, clone)
        sh(clone, "git", "checkout", "-q", self.c1)
        sh(clone, "git", "-c", "user.email=t@t", "-c", "user.name=t", "am", "-q", patch)
        self.assertIn("#?(:vs 2.0", open(os.path.join(clone, "src/lib/core.cljc")).read())
        # upstream merges the branch; the consumer bumps the pin; nothing is left local
        sh(self.up.d, "git", "merge", "-q", "--ff-only", "fix/b")
        self.declare(sh(self.up.d, "git", "rev-parse", "HEAD").strip())
        self.run_tool("sync")
        self.assertIn("0 local edit(s)", self.run_tool("status"))

    def test_refuses_unpinned_tree(self):
        self.declare(self.c1)
        os.makedirs(os.path.join(self.root, "vendor/lib"))
        open(os.path.join(self.root, "vendor/lib/x"), "w").write("x")
        self.assertIn("no PIN", self.run_tool("sync", rc=3))


if __name__ == "__main__":
    unittest.main(verbosity=2)
