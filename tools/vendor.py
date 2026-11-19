#!/usr/bin/env python3
"""vendor.py — cohost's vendoring: a dependency declared in deps.edn, materialized as SOURCE in the consumer's tree, edited
there, and contributed back upstream. One tool for every layer that vendors another (VS <- eve, cohost <- eve, … ).

THE DECLARATION — deps.edn, under :cohost/vendor (never under :deps: a vendored lib is the consumer's own source, never
a classpath dependency it hands its users):

  :cohost/vendor
  {eve/eve {:git/url "https://github.com/SeniorCareMarket/eve"
            :git/sha "e6cddc906da3620b912287f30046ffb67058ac4e"
            :dest "vendor/eve"                       ; where the source lands, relative to the project root
            :paths ["src" "hosts.edn" "LICENSE"]     ; the upstream paths taken (files or directories)
            :paths-from {"hosts.edn" :vs}}}          ; … plus the paths an EDN file at the pin lists under a key

THE BASE RECORD — <dest>/PIN: `lib`, `repo`, `commit` (and `version`) header lines, then `<git-blob-sha1> <path>` per
file, exactly as `git ls-tree -r <commit>` prints them upstream. It names the upstream commit the tree was last
synced to; a file whose bytes differ from its PIN blob is a LOCAL EDIT.

EDITS ARE MADE IN PLACE, in the vendored files, and every edit is meant for upstream. An edit that must hold only for
the consumer's host goes inside a reader conditional (`#?(:vs …)` in a .cljc file), so the same file is right on every
host and the edit ships upstream like any other. There is no overlay and no patch queue: the vendored tree on disk is
the truth, the PIN is its base.

  vendor.py sync [lib…]          materialize each declared lib at its pin; when the declared pin moved past the PIN's
                                 commit, carry the local edits onto it — a three-way merge per file (base: the PIN's
                                 commit, ours: the tree, theirs: the new pin; `git merge-file`). Conflicts are left
                                 in the files with markers and named; exit 4. Then PIN records the new base.
  vendor.py status [lib…]        the local edits against the PIN (M modified · A added · D deleted), and whether the
                                 declaration and the PIN name the same commit
  vendor.py diff [lib…]          the local edits as a unified diff against the PIN's commit
  vendor.py contribute <lib> (--branch <name> [--remote <url>] | --patch <file>)
                                 the local edits as ONE commit on top of the PIN's commit: pushed to <remote> (default:
                                 the lib's :git/url) as <name>, or written as a `git format-patch` file. Upstream
                                 merges it; the consumer then bumps :git/sha and syncs (its edits are now upstream's).
  vendor.py check [lib…]         CI: the PIN names the declared commit, and no vendored file holds a conflict marker

  --root <dir>   the project root (default: the current directory) · -m <message>  contribute's commit message
Transport: plain git (`git fetch <url> <sha>` into a cache under ~/.cache/cohost/vendor, or $COHOST_VENDOR_CACHE). Python 3 standard library.
Exit codes: 0 ok · 1 check failed · 2 usage · 3 git / io · 4 merge conflicts.
"""
import argparse, hashlib, os, re, shutil, subprocess, sys, tempfile

CACHE = os.environ.get("COHOST_VENDOR_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "cohost", "vendor")


def die(msg, rc=3):
    print(f"vendor: {msg}", file=sys.stderr)
    sys.exit(rc)


# ── EDN (the subset deps.edn and hosts.edn use) ──────────────────────────────────────────────────────────────────────
class Kw(str):
    """a keyword, its text without the colon"""


def edn_read(text):
    i = 0
    n = len(text)

    def ws():
        nonlocal i
        while i < n:
            c = text[i]
            if c in " \t\r\n,":
                i += 1
            elif c == ";":
                while i < n and text[i] != "\n":
                    i += 1
            elif text.startswith("#_", i):
                i += 2
                form()
            else:
                break

    def form():
        nonlocal i
        ws()
        if i >= n:
            raise ValueError("EDN: unexpected end")
        c = text[i]
        if c in "([{":
            close = {"(": ")", "[": "]", "{": "}"}[c]
            i += 1
            items = []
            while True:
                ws()
                if i >= n:
                    raise ValueError("EDN: unclosed " + c)
                if text[i] == close:
                    i += 1
                    break
                items.append(form())
            if c == "{":
                return {items[k]: items[k + 1] for k in range(0, len(items) - 1, 2)}
            return items
        if text.startswith("#{", i):
            i += 1
            return set(map(lambda x: x if not isinstance(x, list) else tuple(x), form()))
        if c == '"':
            i += 1
            out = []
            while text[i] != '"':
                if text[i] == "\\":
                    i += 1
                    out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(text[i], text[i]))
                else:
                    out.append(text[i])
                i += 1
            i += 1
            return "".join(out)
        j = i
        while j < n and text[j] not in " \t\r\n,;()[]{}\"":
            j += 1
        tok = text[i:j]
        i = j
        if tok.startswith(":"):
            return Kw(tok[1:])
        if tok in ("nil", "true", "false"):
            return {"nil": None, "true": True, "false": False}[tok]
        if re.fullmatch(r"-?\d+", tok):
            return int(tok)
        return tok  # a symbol

    v = form()
    return v


def declaration(root):
    p = os.path.join(root, "deps.edn")
    if not os.path.isfile(p):
        die(f"no deps.edn in {root}", 2)
    d = edn_read(open(p, encoding="utf-8").read())
    v = d.get(Kw("cohost/vendor")) if isinstance(d, dict) else None
    if not v:
        die("deps.edn declares no :cohost/vendor libs", 2)
    out = {}
    for lib, spec in v.items():
        g = lambda k: spec.get(Kw(k))
        url, sha, dest = g("git/url"), g("git/sha"), g("dest")
        if not (url and sha and re.fullmatch(r"[0-9a-f]{40}", sha) and dest):
            die(f"deps.edn :cohost/vendor {lib}: :git/url, a 40-hex :git/sha and :dest are required", 2)
        out[str(lib)] = {"url": url, "sha": sha, "dest": dest, "paths": list(g("paths") or []),
                         "paths-from": dict(g("paths-from") or {})}
    return out


# ── git ──────────────────────────────────────────────────────────────────────────────────────────────────────────────
def git(repo, *args, input=None, check=True):
    r = subprocess.run(["git", "-C", repo] + list(args), input=input, capture_output=True)
    if check and r.returncode != 0:
        die(f"git {' '.join(args[:3])}: {r.stderr.decode(errors='replace').strip()[:400]}")
    return r


def cache_for(url):
    d = os.path.join(CACHE, re.sub(r"[^A-Za-z0-9._-]+", "_", url) + ".git")
    if not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
        git(d, "init", "-q", "--bare")
    return d


def have(cache, sha):
    return git(cache, "cat-file", "-e", sha + "^{commit}", check=False).returncode == 0


def fetch(cache, url, sha):
    if not have(cache, sha):
        r = git(cache, "fetch", "-q", "--depth", "1", url, sha, check=False)
        if r.returncode != 0 or not have(cache, sha):
            git(cache, "fetch", "-q", url, sha)  # a server that refuses a shallow fetch by sha
    return cache


def blob_sha(b):
    return hashlib.sha1(b"blob " + str(len(b)).encode() + b"\0" + b).hexdigest()


def show(cache, sha, path):
    r = git(cache, "show", f"{sha}:{path}", check=False)
    return r.stdout if r.returncode == 0 else None


def selected(cache, spec, sha):
    """{path: blob} of the declared paths at commit sha — :paths, plus what each :paths-from file lists under its key"""
    roots = list(spec["paths"])
    for f, key in spec["paths-from"].items():
        t = show(cache, sha, f)
        if t is None:
            die(f"{f} is not in {spec['url']} at {sha[:12]} (:paths-from)")
        m = edn_read(t.decode("utf-8"))
        extra = m.get(Kw(str(key)) if not isinstance(key, Kw) else key) if isinstance(m, dict) else None
        if extra is None:
            die(f"{f} at {sha[:12]} lists nothing under :{key} (:paths-from)")
        roots += [x for x in extra if isinstance(x, str)]
    if not roots:
        die(f"{spec['url']}: no :paths declared")
    out = {}
    listed = git(cache, "ls-tree", "-r", "-z", "--full-tree", sha, "--", *roots).stdout.decode()
    for ent in listed.split("\0"):
        if not ent:
            continue
        meta, path = ent.split("\t", 1)
        mode, typ, obj = meta.split()
        if typ == "blob":
            out[path] = obj
    return out


# ── the PIN ──────────────────────────────────────────────────────────────────────────────────────────────────────────
def read_pin(dest):
    p = os.path.join(dest, "PIN")
    if not os.path.isfile(p):
        return None, {}
    head, files = {}, {}
    for line in open(p, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        k, v = line.split(" ", 1)
        if re.fullmatch(r"[0-9a-f]{40}", k):
            files[v] = k
        else:
            head[k] = v
    return head, files


def write_pin(dest, lib, spec, sha, files, version=None):
    with open(os.path.join(dest, "PIN"), "w", encoding="utf-8") as f:
        f.write("# PIN — written by cohost's tools/vendor.py: the upstream commit this vendored tree was last synced to,\n"
                "# file by file (git blob ids). A file whose bytes differ is a local edit (vendor.py status / contribute).\n")
        f.write(f"lib {lib}\nrepo {spec['url']}\ncommit {sha}\n")
        if version:
            f.write(f"version {version}\n")
        for p in sorted(files):
            f.write(f"{files[p]} {p}\n")


def tree_files(dest):
    out = []
    for d, dirs, fs in os.walk(dest):
        dirs[:] = [x for x in dirs if x != ".git"]
        for f in fs:
            rel = os.path.relpath(os.path.join(d, f), dest).replace(os.sep, "/")
            if rel != "PIN":
                out.append(rel)
    return sorted(out)


def edits(dest, files):
    """the local edits: {path: 'M' | 'A' | 'D'} against the PIN's blobs"""
    out = {}
    for p in tree_files(dest):
        if p not in files:
            out[p] = "A"
        elif blob_sha(open(os.path.join(dest, p), "rb").read()) != files[p]:
            out[p] = "M"
    for p in files:
        if not os.path.isfile(os.path.join(dest, p)):
            out[p] = "D"
    return out


def write_file(dest, p, b):
    fp = os.path.join(dest, p)
    os.makedirs(os.path.dirname(fp), exist_ok=True)
    with open(fp, "wb") as f:
        f.write(b)


def remove_file(dest, p):
    fp = os.path.join(dest, p)
    if os.path.isfile(fp):
        os.remove(fp)
        d = os.path.dirname(fp)
        while d != dest and os.path.isdir(d) and not os.listdir(d):
            os.rmdir(d)
            d = os.path.dirname(d)


def version_of(cache, sha):
    for f in ("VERSION", "version.edn"):
        t = show(cache, sha, f)
        if t:
            return t.decode(errors="replace").strip().splitlines()[0]
    r = git(cache, "describe", "--tags", "--exact-match", sha, check=False)
    return r.stdout.decode().strip() or None


# ── the commands ─────────────────────────────────────────────────────────────────────────────────────────────────────
def merge3(base, ours, theirs):
    """git merge-file over three byte strings -> (merged, conflicted)"""
    with tempfile.TemporaryDirectory() as t:
        paths = []
        for name, b in (("ours", ours), ("base", base), ("theirs", theirs)):
            p = os.path.join(t, name)
            open(p, "wb").write(b)
            paths.append(p)
        r = subprocess.run(["git", "merge-file", "-p", "-L", "vendored (ours)", "-L", "base", "-L", "upstream",
                            paths[0], paths[1], paths[2]], capture_output=True)
        if r.returncode < 0 or r.returncode > 127:
            die("git merge-file failed")
        return r.stdout, r.returncode > 0


def sync(root, lib, spec):
    dest = os.path.join(root, spec["dest"])
    cache = fetch(cache_for(spec["url"]), spec["url"], spec["sha"])
    new = selected(cache, spec, spec["sha"])
    head, base_files = read_pin(dest)
    version = version_of(cache, spec["sha"])
    if head is None:   # first materialization: the tree must be empty of vendored files
        if os.path.isdir(dest) and tree_files(dest):
            die(f"{spec['dest']} holds files but no PIN — move them aside or write its PIN first", 3)
        for p, obj in new.items():
            write_file(dest, p, git(cache, "cat-file", "blob", obj).stdout)
        write_pin(dest, lib, spec, spec["sha"], new, version)
        print(f"vendor: {lib} materialized at {spec['sha'][:12]} -> {spec['dest']}/ ({len(new)} files)")
        return 0
    old = head.get("commit", "")
    if old == spec["sha"]:
        e = edits(dest, base_files)
        print(f"vendor: {lib} at {old[:12]} (current) · {len(e)} local edit(s)")
        return 0
    fetch(cache, head.get("repo", spec["url"]), old) if old else None
    mine = edits(dest, base_files)
    conflicts, carried, taken = [], 0, 0
    for p in sorted(set(base_files) | set(new) | set(mine)):
        b = git(cache, "cat-file", "blob", base_files[p]).stdout if p in base_files else None
        t = git(cache, "cat-file", "blob", new[p]).stdout if p in new else None
        fp = os.path.join(dest, p)
        o = open(fp, "rb").read() if os.path.isfile(fp) else None
        if p not in mine:            # untouched here: upstream's version (or its deletion)
            if t is None:
                remove_file(dest, p)
            elif t != o:
                write_file(dest, p, t)
            taken += 1
        elif t == b or t == o:       # upstream did not touch it, or made the same change: ours stands
            carried += 1
        elif o is None or t is None or b is None:
            conflicts.append((p, "deleted on one side, changed on the other — ours kept"))
        else:
            merged, conflicted = merge3(b, o, t)
            write_file(dest, p, merged)
            carried += 1
            if conflicted:
                conflicts.append((p, "conflict markers written"))
    write_pin(dest, lib, spec, spec["sha"], new, version)
    print(f"vendor: {lib} {old[:12]} -> {spec['sha'][:12]} · upstream files taken {taken} · local edits carried "
          f"{carried} · conflicts {len(conflicts)}")
    for p, why in conflicts:
        print(f"vendor: CONFLICT {spec['dest']}/{p}: {why}", file=sys.stderr)
    return 4 if conflicts else 0


def status(root, lib, spec):
    dest = os.path.join(root, spec["dest"])
    head, files = read_pin(dest)
    if head is None:
        print(f"vendor: {lib} not materialized ({spec['dest']}/PIN absent) — vendor.py sync")
        return 1
    e = edits(dest, files)
    cur = head.get("commit", "")
    state = "current" if cur == spec["sha"] else f"BEHIND the declared {spec['sha'][:12]} — vendor.py sync"
    print(f"vendor: {lib} at {cur[:12]} ({state}) · {len(files)} files · {len(e)} local edit(s)")
    for p in sorted(e):
        print(f"  {e[p]} {spec['dest']}/{p}")
    return 0


def diff(root, lib, spec):
    dest = os.path.join(root, spec["dest"])
    head, files = read_pin(dest)
    if head is None:
        die(f"{lib} is not materialized", 1)
    cache = fetch(cache_for(head.get("repo", spec["url"])), head.get("repo", spec["url"]), head["commit"])
    e = edits(dest, files)
    with tempfile.TemporaryDirectory() as t:
        a, b = os.path.join(t, "a"), os.path.join(t, "b")
        for p, k in e.items():
            if p in files:
                write_file(a, p, git(cache, "cat-file", "blob", files[p]).stdout)
            if k != "D":
                write_file(b, p, open(os.path.join(dest, p), "rb").read())
        os.makedirs(a, exist_ok=True); os.makedirs(b, exist_ok=True)
        r = subprocess.run(["git", "diff", "--no-index", "--no-color", "--no-prefix", "a", "b"], cwd=t, capture_output=True)
        sys.stdout.write(r.stdout.decode(errors="replace"))
    return 0


def contribute(root, lib, spec, branch, remote, patch, message):
    dest = os.path.join(root, spec["dest"])
    head, files = read_pin(dest)
    if head is None:
        die(f"{lib} is not materialized", 1)
    e = edits(dest, files)
    if not e:
        print(f"vendor: {lib} has no local edits — nothing to contribute")
        return 0
    url = head.get("repo", spec["url"])
    cache = fetch(cache_for(url), url, head["commit"])
    with tempfile.TemporaryDirectory() as t:
        wt = os.path.join(t, "wt")
        git(cache, "worktree", "add", "-q", "--detach", wt, head["commit"])
        try:
            for p, k in e.items():
                if k == "D":
                    git(wt, "rm", "-q", "--", p)
                else:
                    write_file(wt, p, open(os.path.join(dest, p), "rb").read())
                    git(wt, "add", "--", p)
            msg = message or f"{lib}: edits made in a vendored copy ({len(e)} file(s)), contributed with cohost vendor.py"
            git(wt, "-c", "user.name=cohost vendor", "-c", "user.email=vendor@cohost.invalid",
                "commit", "-q", "-m", msg)
            if patch:
                out = git(wt, "format-patch", "--stdout", "-1", "HEAD").stdout
                open(patch, "wb").write(out)
                print(f"vendor: {lib} — {len(e)} edit(s) on {head['commit'][:12]} written to {patch}")
            else:
                r = git(wt, "push", remote or url, f"HEAD:refs/heads/{branch}", check=False)
                if r.returncode != 0:
                    die(f"push to {remote or url} {branch}: {r.stderr.decode(errors='replace').strip()[:400]}")
                print(f"vendor: {lib} — {len(e)} edit(s) on {head['commit'][:12]} pushed to {remote or url} as {branch}")
        finally:
            git(cache, "worktree", "remove", "--force", wt, check=False)
    return 0


def check(root, lib, spec):
    dest = os.path.join(root, spec["dest"])
    head, files = read_pin(dest)
    bad = []
    if head is None:
        bad.append("not materialized")
    else:
        if head.get("commit") != spec["sha"]:
            bad.append(f"PIN {head.get('commit', '?')[:12]} != declared {spec['sha'][:12]} (vendor.py sync)")
        for p in tree_files(dest):
            b = open(os.path.join(dest, p), "rb").read()
            if re.search(rb"^<<<<<<< vendored \(ours\)$", b, re.M):
                bad.append(f"conflict markers in {spec['dest']}/{p}")
    for x in bad:
        print(f"vendor: CHECK {lib}: {x}", file=sys.stderr)
    print(f"vendor: {lib} {'OK' if not bad else 'FAILED'}")
    return 1 if bad else 0


def main(argv):
    ap = argparse.ArgumentParser(prog="vendor.py", description=__doc__.split("\n\n")[0])
    ap.add_argument("cmd", choices=["sync", "status", "diff", "contribute", "check"])
    ap.add_argument("libs", nargs="*")
    ap.add_argument("--root", default=os.getcwd())
    ap.add_argument("--branch")
    ap.add_argument("--remote")
    ap.add_argument("--patch")
    ap.add_argument("-m", "--message")
    a = ap.parse_args(argv)
    root = os.path.abspath(a.root)
    decl = declaration(root)
    libs = a.libs or sorted(decl)
    for lib in libs:
        if lib not in decl:
            die(f"{lib} is not declared under :cohost/vendor in {root}/deps.edn", 2)
    if a.cmd == "contribute":
        if len(libs) != 1 or not (a.branch or a.patch):
            die("contribute <lib> --branch <name> [--remote <url>] | --patch <file>", 2)
        return contribute(root, libs[0], decl[libs[0]], a.branch, a.remote, a.patch, a.message)
    rc = 0
    for lib in libs:
        rc = max(rc, {"sync": sync, "status": status, "diff": diff, "check": check}[a.cmd](root, lib, decl[lib]))
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
