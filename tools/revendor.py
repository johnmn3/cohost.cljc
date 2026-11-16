#!/usr/bin/env python3
"""revendor.py — the TeaVM campaign's ONE re-vendor driver.

Implements the driver contract in docs VENDORING-SPEC.md §3 (corrected):
every campaign tree's vendored copies (eve/ subtree; route2
vendor/cohost) are DERIVED state — byte-identical to a named source
pin plus the named overlay (7 eve/adapters/*/teavm files + the
hosts.edn :teavm vector carried as a PATCH STEP) — and provably so
by content census at any moment.

Sources of truth: Eve = johnmn3/atom.broker branch
release/eve-0.9.3.2 (private working line; SCM/eve is the export
destination, never a source). Cohost = johnmn3/cohost.cljc main.

Manifest-fed: one manifest per target names both source pins, the
target + its expected tip, the vendored path roots, the overlay
file list with expected blob SHAs, the hosts.edn patch step, and
the working-line-files policy (carry|exclude — the 223 dev-side
files are an explicit manifest field, never a driver default).
A manifest may also carry census_dispositions: operator-judged
census deltas (upstreamed fixes, dissolvers), each keyed to the
target file's exact current blob SHA — a disposition whose SHA no
longer matches stops the run again, and missing files cannot be
dispositioned.

Steps (each resumable: output present + hash-correct -> SKIP):
  0 preflight  pins resolve; overlay blobs at canonical source;
               target tip == manifest expected tip
  1 stage      path->blob-sha listings for sources + target
  2 census     current copy vs its RECORDED pin (+ overlay):
               any delta not in the overlay manifest STOPS the
               run and names it (suspected marriage violation);
               no recorded pin on target -> that artifact's
               census skips (first materialization, 2026-11-16)
  3 write      new copy = source pin + overlay + pin files
               (per-file hash-skip; a mid-write death costs the
               files not yet written, nothing else)
  4 verify     vendored roots == pin + overlay, blob for blob,
               both directions; optional skew check (4b)
  5 bank       remote: the write IS one Git Data API commit,
               read-back verified. local: a bank record.

--dry-run: steps 0-2 + the full write PLAN printed; nothing written.
--max-step N: stop after step N (resume-test hook).

Exit codes: 0 pass · 2 preflight stop · 3 usage/io · 4 census stop
(named deltas) · 5 verify fail · 6 target-tip mismatch.
"""
import sys, os, json, hashlib, base64, subprocess, urllib.parse, fnmatch

# ---------------------------------------------------------------- hashes
def blob_sha(content: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()

def listing_hash(m: dict) -> str:
    h = hashlib.sha256()
    for p in sorted(m):
        h.update(p.encode()); h.update(b" "); h.update(m[p].encode()); h.update(b"\n")
    return h.hexdigest()

def file_blob_sha(path: str) -> str:
    with open(path, "rb") as f:
        return blob_sha(f.read())

# ---------------------------------------------------------------- github
GH = os.path.expanduser("~/workspace/custom-connectors/custom.legacy-github/bin/gh-api")
GH_ENV = dict(os.environ, PYTHONPATH="/opt/muse/skills/custom-connector-setup/bin")

def _gh_get_api(repo: str, path: str):
    """In-process GitHub GET (Lane J6 transport extension, proven
    in run_transport_proof.py against the helper route below).
    The subprocess-per-call route costs ~1.7 s/call in gh-api
    helper startups; a driver invocation's ~12 calls put it past
    the operator's 10 s local timer inside preflight. This is
    remote_bank's urllib route (same file — the route every
    remote bank already runs) applied to reads."""
    sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
    from dynamic_credentials import add_surrogate_to_request, read_json_response
    import urllib.request, urllib.error
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}{path}",
                                 headers={"Accept": "application/vnd.github+json"})
    add_surrogate_to_request(req, "custom.github", allowed_hosts=["api.github.com"])
    try:
        with urllib.request.urlopen(req) as resp: return read_json_response(resp)
    except Exception as e:
        raise IOError(f"gh GET /repos/{repo}{path} failed: {e}")

# The git route (2026-11-16): the same four reads — a branch tip, a
# recursive tree, one tree level, a blob — answered by plain git
# against the repo's remote, for seats that have git access to the
# source repos and no API credential helper. Objects are named by
# SHA either way, so the two routes return the same listings and
# the same bytes; everything above this function is route-blind.
# COHOST_VENDOR_TRANSPORT=api|git forces a route; unset, the API
# route is used where its helper is installed, git elsewhere.
# COHOST_VENDOR_GIT_REMOTES="owner/repo=<url or path>,..." names a
# remote other than https://github.com/<owner>/<repo>.git.
GIT_CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ghcache", "git")

def _git_remote(repo: str) -> str:
    for ent in os.environ.get("COHOST_VENDOR_GIT_REMOTES", "").split(","):
        k, _, v = ent.partition("=")
        if k.strip() == repo and v.strip(): return v.strip()
    return f"https://github.com/{repo}.git"

def _git(repo: str, *args, check=True) -> subprocess.CompletedProcess:
    d = os.path.join(GIT_CACHE, repo.replace("/", "_") + ".git")
    if not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
        subprocess.run(["git", "init", "-q", "--bare", d], check=True)
    r = subprocess.run(["git", "-C", d, *args], capture_output=True)
    if check and r.returncode != 0:
        raise IOError(f"git {' '.join(args[:3])} ({repo}) failed: {r.stderr.decode(errors='replace').strip()[:300]}")
    return r

def _git_have(repo: str, sha: str) -> bool:
    return _git(repo, "cat-file", "-e", sha, check=False).returncode == 0

def _git_ensure(repo: str, sha: str):
    """The object named sha is in the cache: a commit is fetched by
    SHA, whole (depth 1: its tree and every blob in one pack); trees
    and blobs arrive with the commit that was fetched for them."""
    if _git_have(repo, sha): return
    _git(repo, "fetch", "-q", "--depth", "1", _git_remote(repo), sha)
    if not _git_have(repo, sha):
        raise IOError(f"{repo}: object {sha} not present after fetch")

def _git_tree_entries(repo: str, sha: str, recursive: bool) -> list:
    _git_ensure(repo, sha)
    r = _git(repo, "ls-tree", "-z", *(["-r"] if recursive else []), sha)
    out = []
    for rec in r.stdout.split(b"\0"):
        if not rec: continue
        meta, _, path = rec.partition(b"\t")
        _mode, typ, osha = meta.decode().split()
        out.append({"path": path.decode(), "type": typ, "sha": osha})
    return out

def _gh_get_git(repo: str, path: str):
    if path.startswith("/branches/"):
        branch = urllib.parse.unquote(path[len("/branches/"):])
        r = subprocess.run(["git", "ls-remote", _git_remote(repo), f"refs/heads/{branch}"], capture_output=True)
        line = r.stdout.decode().strip()
        if r.returncode != 0 or not line:
            raise IOError(f"git ls-remote {repo} refs/heads/{branch} failed: {r.stderr.decode(errors='replace').strip()[:300]}")
        return {"commit": {"sha": line.split()[0]}}
    if path.startswith("/git/trees/"):
        sha, _, q = path[len("/git/trees/"):].partition("?")
        return {"tree": _git_tree_entries(repo, sha, "recursive=1" in q), "truncated": False}
    if path.startswith("/git/blobs/"):
        sha = path[len("/git/blobs/"):]
        if not _git_have(repo, sha):
            raise IOError(f"{repo}: blob {sha} is not in the git cache (no commit carrying it was fetched)")
        return {"content": base64.b64encode(_git(repo, "cat-file", "blob", sha).stdout).decode()}
    raise IOError(f"git route: unsupported read {path}")

def _transport() -> str:
    t = os.environ.get("COHOST_VENDOR_TRANSPORT", "")
    if t in ("api", "git"): return t
    return "api" if os.path.isdir("/opt/muse/skills/custom-connector-setup/bin") else "git"

def gh_get(repo: str, path: str):
    """One read of the source repo, by the seat's route (above)."""
    try:
        return _gh_get_api(repo, path) if _transport() == "api" else _gh_get_git(repo, path)
    except IOError: raise
    except Exception as e:
        raise IOError(f"read /repos/{repo}{path} failed: {e}")

def gh_branch_tip(repo: str, branch: str) -> str:
    d = gh_get(repo, f"/branches/{urllib.parse.quote(branch, safe='')}")
    return d["commit"]["sha"]

GH_CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ghcache")

def _cache_read(path):
    try:
        with open(path, "rb") as f: data = f.read()
        return data if data else None  # a zeroed write is a miss, never content
    except Exception: return None

def _cache_write(path, data: bytes):
    try:
        os.makedirs(GH_CACHE, exist_ok=True)
        with open(path, "wb") as f: f.write(data)
        with open(path, "rb") as f: back = f.read()
        if back != data:  # the write fault zeroed it: leave no poison
            try: os.unlink(path)
            except Exception: pass
    except Exception:
        # a denied write can still create the inode: leave no
        # zero-byte artifact behind for a later read to meet.
        try: os.unlink(path)
        except Exception: pass

def gh_tree(repo: str, ref: str) -> dict:
    """path -> blob sha for the whole tree at ref (recursive).
    Cached by (repo, ref) (Lane J6): refs in every driver flow
    are content SHAs — pins, tips, and the gh_path_content walk
    — so a cached listing IS the named tree whenever it was
    fetched; the cache is what lets a driver invocation fit
    the operator's 10 s slices across its ~12 reads."""
    cp = os.path.join(GH_CACHE, "tree-" + repo.replace("/", "_") + "-" + ref + ".json")
    raw = _cache_read(cp)
    if raw is not None:
        try: return json.loads(raw)
        except Exception: pass
    d = gh_get(repo, f"/git/trees/{ref}?recursive=1")
    if d.get("truncated"):
        raise IOError(f"tree listing truncated for {repo}@{ref} — census would be unsound")
    out = {e["path"]: e["sha"] for e in d["tree"] if e["type"] == "blob"}
    _cache_write(cp, json.dumps(out, sort_keys=True).encode())
    return out

def gh_blob(repo: str, sha: str) -> bytes:
    """Blob content by SHA (content-addressed; cached, Lane J6)."""
    cp = os.path.join(GH_CACHE, "blob-" + sha)
    raw = _cache_read(cp)
    if raw is not None: return raw
    d = gh_get(repo, f"/git/blobs/{sha}")
    content = base64.b64decode(d["content"])
    _cache_write(cp, content)
    return content

def gh_path_content(repo: str, ref: str, path: str) -> bytes:
    """Content of one file at ref, walking subtree listings — safe
    on repos whose full recursive listing truncates (atom.broker
    main is one; the filter file lives there)."""
    sha = ref
    for seg in path.split("/"):
        d = gh_get(repo, f"/git/trees/{sha}")
        nxt = [e for e in d["tree"] if e["path"] == seg]
        if not nxt: raise IOError(f"{repo}@{ref}: no such path segment {seg} in {path}")
        sha = nxt[0]["sha"]
    return gh_blob(repo, sha)

# ---------------------------------------------------------------- local trees
def local_tree(root: str) -> dict:
    out = {}
    for dp, dns, fns in os.walk(root):
        dns.sort()
        for fn in sorted(fns):
            full = os.path.join(dp, fn)
            rel = os.path.relpath(full, root)
            out[rel] = file_blob_sha(full)
    return out

def local_pin(root: str) -> str:
    """A local fixture's 'pin' = the listing hash of its tree."""
    return listing_hash(local_tree(root))

# ---------------------------------------------------------------- sources
class Source:
    """A pinned tree: path->sha map + lazy content access.
    tree_root: prefix inside the source tree that maps to the dest
    ('' = whole tree; 'src' = cohost.cljc's src/ root)."""
    def __init__(self, spec: dict, label: str):
        self.label = label
        self.kind = spec["kind"]
        self.tree_root = spec.get("tree_root", "")
        self.pin = spec.get("ref")  # None = a live locator (overlay source); identity is per-file SHAs
        self._tree = None
        if self.kind == "github":
            self.repo = spec["repo"]
        elif self.kind == "local-dir":
            self.root = spec["root"]
        else:
            raise ValueError(f"{label}: unknown source kind {self.kind}")

    def resolve(self) -> bool:
        """Pin resolves: the named tree exists and IS the pin.
        A pin-less locator (overlay source) resolves on existence."""
        if self.kind == "github":
            if self.pin is None: raise ValueError(f"{self.label}: github source needs a ref")
            t = gh_tree(self.repo, self.pin)  # raises if ref unknown
            self._tree = t
            return True
        else:
            return os.path.isdir(self.root) and (self.pin is None or local_pin(self.root) == self.pin)

    def tree(self) -> dict:
        """path->sha, tree_root stripped."""
        if self._tree is None:
            self._tree = gh_tree(self.repo, self.pin) if self.kind == "github" else local_tree(self.root)
        if not self.tree_root:
            return dict(self._tree)
        pre = self.tree_root + "/"
        return {p[len(pre):]: s for p, s in self._tree.items() if p.startswith(pre)}

    def content(self, relpath: str) -> bytes:
        if self.kind == "github":
            sha = self.tree()[relpath]
            return gh_blob(self.repo, sha)
        with open(os.path.join(self.root, self.tree_root, relpath) if self.tree_root
                  else os.path.join(self.root, relpath), "rb") as f:
            return f.read()

# ---------------------------------------------------------------- export filter
def glob_to_regex(pat: str):
    """One exclude-file glob (relative to the Eve tree root) -> regex.
    Trailing '/' = directory prefix. A pattern with no '/' matches a
    basename at any depth (gitignore-style, as the file's own entries
    are written). '**' crosses directories; '*' and '?' do not."""
    import re
    dir_prefix = pat.endswith("/")
    if dir_prefix:
        pat = pat[:-1]
    anchored = "/" in pat
    out, i = [], 0
    while i < len(pat):
        c = pat[i]
        if pat.startswith("**/", i):
            out.append("(?:.*/)?"); i += 3
        elif pat.startswith("/**", i) and i + 3 == len(pat):
            out.append("(?:/.*)?"); i += 3
        elif c == "*":
            out.append("[^/]*"); i += 1
        elif c == "?":
            out.append("[^/]"); i += 1
        else:
            out.append(re.escape(c)); i += 1
    body = "".join(out)
    if dir_prefix:
        body += "/.*"
    if not anchored:
        body = "(?:.*/)?" + body
    import re as _re
    return _re.compile("^" + body + "$")

class ExcludeFilter:
    """The release export's path filter (atom.broker main:
    release/eve/exclude). NOTE: the sibling deny file is a set of
    CONTENT regexes the export process scans for — it is not a path
    filter and this driver never drops a file for its content."""
    def __init__(self, text: str):
        self.patterns = []
        for line in text.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                self.patterns.append((line, glob_to_regex(line)))
    def drops(self, relpath: str) -> bool:
        # gitignore semantics: a pattern matching a directory drops
        # everything under it, so test every ancestor prefix too.
        parts = relpath.split("/")
        cands = ["/".join(parts[:k]) for k in range(1, len(parts) + 1)]
        return any(rx.match(c) for _, rx in self.patterns for c in cands)
    def dropped_from(self, tree: dict):
        return sorted(p for p in tree if self.drops(p))

# ---------------------------------------------------------------- hosts.edn patch
def _form_span(text: str, i: int):
    """(start, end) of the EDN form beginning at/after index i.
    Handles strings, chars, comments, and []{}() nesting."""
    n = len(text)
    while i < n:
        if text[i] in " \t\r\n,":
            i += 1
        elif text[i] == ";":
            while i < n and text[i] != "\n": i += 1
        else:
            break
    start = i
    if i >= n: raise ValueError("no form found")
    if text[i] == '"':
        i += 1
        while i < n:
            if text[i] == "\\": i += 2; continue
            if text[i] == '"': return start, i + 1
            i += 1
        raise ValueError("unterminated string")
    if text[i] in "[{(":
        close = {"[": "]", "{": "}", "(": ")"}[text[i]]
        depth, i = 1, i + 1
        while i < n and depth:
            c = text[i]
            if c == '"':
                _, i = _form_span(text, i); continue
            if c == ";":
                while i < n and text[i] != "\n": i += 1
                continue
            if c in "[{(": depth += 1
            elif c in "]})": depth -= 1
            i += 1
        if depth: raise ValueError("unbalanced form")
        return start, i
    # atom: keyword/symbol/number/char literal
    while i < n and text[i] not in " \t\r\n,[]{}();":
        i += 1
    return start, i

def strip_comments(text: str) -> str:
    """EDN text with ;-comments removed (strings and char literals
    honored). Comments are not data: the hosts.edn census compares
    bases comment-insensitively, and the patch step's own comment
    block (the seat annotation) rides with the patch, named in the
    manifest — it is not a base delta."""
    outl, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            _, j = _form_span(text, i); outl.append(text[i:j]); i = j
        elif c == "\\":
            outl.append(text[i:i + 2]); i += 2
        elif c == ";":
            while i < n and text[i] != "\n": i += 1
        else:
            outl.append(c); i += 1
    return "".join(outl)

def patch_hosts_edn(text: str, key: str, value_text: str, comment: str = "") -> str:
    """Insert or replace the top-level `key` entry of a hosts.edn map
    with value_text — the :teavm vector carried as a PATCH STEP,
    never a whole-file carry (the t25 whole-vector revert is the
    standing failure this exists to prevent). On insert, the
    manifest's comment block (the seat annotation) is emitted with
    the entry. Deterministic: the same (text, key, value, comment)
    always yields the same bytes."""
    s, e = _form_span(text, 0)
    if text[s] != "{":
        raise ValueError("hosts.edn is not a map literal")
    i = s + 1
    while True:
        # skip whitespace/comments; stop at the closing brace
        j = i
        while j < e - 1 and (text[j] in " \t\r\n," or text[j] == ";"):
            if text[j] == ";":
                while j < e - 1 and text[j] != "\n": j += 1
            else: j += 1
        if j >= e - 1:
            # key absent: insert before the closing brace
            pre = ("\n " + comment.replace("\n", "\n ") ) if comment else ""
            return text[:e - 1].rstrip() + pre + "\n " + key + " " + value_text + text[e - 1:]
        ks, ke = _form_span(text, j)
        vs, ve = _form_span(text, ke)
        if text[ks:ke] == key:
            return text[:vs] + value_text + text[ve:]
        i = ve

def remove_entry(text: str, key: str) -> str:
    """Text with the top-level key+value spans removed (unchanged if
    the key is absent)."""
    s, e = _form_span(text, 0)
    i = s + 1
    while True:
        j = i
        while j < e - 1 and (text[j] in " \t\r\n," or text[j] == ";"):
            if text[j] == ";":
                while j < e - 1 and text[j] != "\n": j += 1
            else: j += 1
        if j >= e - 1: return text
        ks, ke = _form_span(text, j)
        vs, ve = _form_span(text, ke)
        if text[ks:ke] == key:
            return text[:ks] + text[ve:]
        i = ve

def norm_ws(text: str) -> str:
    """Whitespace-canonical form for EDN-level comparison: collapse
    runs, and drop spaces adjacent to collection brackets (so a
    removed entry's leftover indentation cannot false-fail the
    base comparison)."""
    import re
    t = re.sub(r"\s+", " ", text).strip()
    t = re.sub(r" ([}\]\)])", r"\1", t)
    t = re.sub(r"([{\[\(]) ", r"\1", t)
    return t

def extract_entry(text: str, key: str):
    """The verbatim value text of a top-level key, or None."""
    s, e = _form_span(text, 0)
    i = s + 1
    while True:
        j = i
        while j < e - 1 and (text[j] in " \t\r\n," or text[j] == ";"):
            if text[j] == ";":
                while j < e - 1 and text[j] != "\n": j += 1
            else: j += 1
        if j >= e - 1: return None
        ks, ke = _form_span(text, j)
        vs, ve = _form_span(text, ke)
        if text[ks:ke] == key:
            return text[vs:ve]
        i = ve

# ---------------------------------------------------------------- target
class Target:
    def __init__(self, spec: dict):
        self.kind = spec["kind"]
        self.expected_tip = spec.get("expected_tip")
        if self.kind == "github-branch":
            self.repo, self.branch = spec["repo"], spec["branch"]
            self.allow_write = spec.get("allow_write", False)
            self._tree = None
        elif self.kind == "local-dir":
            self.root = spec["root"]
            self._tree = None
        else:
            raise ValueError(f"unknown target kind {self.kind}")

    def tip(self) -> str:
        if self.kind == "github-branch":
            return gh_branch_tip(self.repo, self.branch)
        return listing_hash(local_tree(self.root))

    def tree(self) -> dict:
        if self._tree is None:
            self._tree = (gh_tree(self.repo, self.tip()) if self.kind == "github-branch"
                          else local_tree(self.root))
        return self._tree

    def content(self, path: str) -> bytes:
        if self.kind == "github-branch":
            return gh_blob(self.repo, self.tree()[path])
        with open(os.path.join(self.root, path), "rb") as f:
            return f.read()

    def sub(self, prefix: str) -> dict:
        pre = prefix + "/"
        return {p: s for p, s in self.tree().items() if p.startswith(pre)}

# ---------------------------------------------------------------- run state
class RunState:
    """Resume fingerprints: a step whose recorded output hash still
    matches is skipped. State is a manifest of hashes — never a
    journal of the target itself; the target's truth is its census."""
    def __init__(self, path: str):
        self.path = path
        self.data = {"steps": {}}
        if os.path.exists(path):
            try: self.data = json.load(open(path))
            except Exception: self.data = {"steps": {}}
    def get(self, step): return self.data["steps"].get(step, {})
    def put(self, step, h, info=None):
        self.data["steps"][step] = {"hash": h, "info": info or {}}
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path, "w") as f: json.dump(self.data, f, indent=1, sort_keys=True)

def out(msg=""): print(msg, flush=True)
def stop(code, msg): out(f"STOP: {msg}"); sys.exit(code)

# ---------------------------------------------------------------- remote bank
def remote_bank(target: Target, writes: dict, deletes: list, message: str):
    """One Git Data API commit (bank_files.py's proven route):
    blobs -> tree on base_tree -> commit -> ref move -> read-back."""
    sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
    from dynamic_credentials import add_surrogate_to_request, read_json_response
    import urllib.request, urllib.error
    API = "https://api.github.com"
    def api(method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{API}/repos/{target.repo}{path}", data=data, method=method,
                                     headers={"Accept": "application/vnd.github+json",
                                              "Content-Type": "application/json"})
        add_surrogate_to_request(req, "custom.github", allowed_hosts=["api.github.com"])
        with urllib.request.urlopen(req) as resp: return read_json_response(resp)
    parent = target.tip()
    pcommit = api("GET", f"/git/commits/{parent}")
    entries = []
    for path, content in writes.items():
        sha = blob_sha(content)
        got = api("POST", "/git/blobs", {"content": base64.b64encode(content).decode(), "encoding": "base64"})
        assert got["sha"] == sha
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": sha})
    for path in deletes:
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
    tree = api("POST", "/git/trees", {"base_tree": pcommit["tree"]["sha"], "tree": entries})
    commit = api("POST", "/git/commits", {"message": message, "tree": tree["sha"], "parents": [parent]})
    api("PATCH", f"/git/refs/heads/{urllib.parse.quote(target.branch, safe='')}",
        {"sha": commit["sha"], "force": False})
    back = api("GET", f"/git/ref/heads/{urllib.parse.quote(target.branch, safe='')}")
    if not back or back["object"]["sha"] != commit["sha"]:
        raise IOError("bank read-back failed: branch tip != new commit")
    return commit["sha"]

# ---------------------------------------------------------------- overlay source
class OverlaySource:
    """Canonical overlay locator: the target itself (c1's own overlay
    is its reference), a github branch, or a local dir. File paths
    are target-repo paths, used verbatim in every locator."""
    def __init__(self, spec: dict, target: Target):
        self.kind = spec["kind"]
        if self.kind == "target":
            self.target = target
        else:
            self.src = Source({**spec, "tree_root": ""}, "overlay")
    def sha(self, path):
        return self.target.tree().get(path) if self.kind == "target" else self.src.tree().get(path)
    def content(self, path):
        return self.target.content(path) if self.kind == "target" else self.src.content(path)

# ---------------------------------------------------------------- compose
def compose_post(man, eve_src, coh_src, overlay, keep):
    """post: dest path -> blob sha; loc: dest path -> content locator;
    dropped: working-line files the manifest policy excludes.
    keep(rel) is the manifest's working_line_files policy, built in
    main: carry = everything; exclude = the export's path set minus
    the exclude globs (measured: the globs alone drop ONE file —
    the 223-file reduction is the export collation's path set)."""
    post, loc, dropped = {}, {}, []
    ep = man["eve"]["dest_prefix"]
    for rel, sha in sorted(eve_src.tree().items()):
        if not keep(rel):
            dropped.append(rel); continue
        post[f"{ep}/{rel}"] = sha; loc[f"{ep}/{rel}"] = ("eve", rel)
    for f in man["overlay"]["files"]:
        post[f["path"]] = f["sha"]; loc[f["path"]] = ("overlay", f["path"])
    hp = man["overlay"]["hosts_edn_patch"]
    patched = patch_hosts_edn(eve_src.content("hosts.edn").decode(), hp["key"], hp["value"], hp.get("comment", ""))
    pb = patched.encode()
    post[hp["path"]] = blob_sha(pb); loc[hp["path"]] = ("literal", pb)
    pin_b = (eve_src.pin + "\n").encode()
    post[man["eve"]["pin_path"]] = blob_sha(pin_b); loc[man["eve"]["pin_path"]] = ("literal", pin_b)
    cp = man["cohost"]["dest_prefix"]
    for rel, sha in sorted(coh_src.tree().items()):
        if rel.startswith("cohost/"):
            # src/cohost/** lands AT the dest prefix (spec §1.1)
            post[f"{cp}/{rel[len('cohost/'):]}"] = sha; loc[f"{cp}/{rel[len('cohost/'):]}"] = ("cohost", rel)
    cpin_b = (coh_src.pin + "\n").encode()
    post[man["cohost"]["pin_path"]] = blob_sha(cpin_b); loc[man["cohost"]["pin_path"]] = ("literal", cpin_b)
    return post, loc, dropped

def resolve_loc(loc, eve_src, coh_src, overlay):
    kind, arg = loc
    if kind == "eve": return eve_src.content(arg)
    if kind == "cohost": return coh_src.content(arg)
    if kind == "overlay": return overlay.content(arg)
    return arg  # literal bytes

def diff_maps(expected: dict, actual: dict, exempt=()):
    d = {"differ": [], "missing": [], "extra": []}
    for p in sorted(set(expected) | set(actual)):
        if p in exempt: continue
        if p not in actual: d["missing"].append(p)
        elif p not in expected: d["extra"].append(p)
        elif expected[p] != actual[p]: d["differ"].append(p)
    return d

# ---------------------------------------------------------------- census
def run_census(man, L, target, eve_base):
    """Current copy vs its RECORDED pin (+ overlay). Any delta not in
    the overlay manifest is a suspected marriage violation — the
    census that would have caught V1/V2. Returns the deltas dict."""
    ep, cp = man["eve"]["dest_prefix"], man["cohost"]["dest_prefix"]
    exempt = {man["eve"]["pin_path"], man["cohost"]["pin_path"]}
    hp = man["overlay"]["hosts_edn_patch"]
    deltas = {}
    # Eve census reference (Lane J6 extension, proven in
    # run_dispo3_proof.py): a second-generation copy derives from
    # the release tree and records that derivation in its EVE-PIN
    # (J2's execution onward). When the manifest names
    # eve.recorded_source AND the target carries the pin, census
    # against the RECORDED pin's tree + overlay — the cohost arm's
    # pattern below. First-generation copies (no EVE-PIN; c10)
    # keep the baseline arm, byte-for-byte.
    eve_rec = None
    if man["eve"].get("recorded_source"):
        try: rec_pin = target.content(man["eve"]["pin_path"]).decode().strip()
        except Exception: rec_pin = None
        if rec_pin:
            eve_rec = Source({**man["eve"]["recorded_source"], "ref": rec_pin}, "eve-recorded")
            if not eve_rec.resolve(): stop(2, f"recorded eve pin on target does not resolve: {rec_pin}")
    if eve_rec is not None or eve_base:
        exp = ({f"{ep}/{r}": s for r, s in eve_rec.tree().items()} if eve_rec is not None
               else {f"{ep}/{r}": s for r, s in L["baseline"].items()})
        for f in man["overlay"]["files"]: exp[f["path"]] = f["sha"]
        act = {p: s for p, s in L["target"].items() if p.startswith(ep + "/")}
        exp.pop(hp["path"], None); act.pop(hp["path"], None)
        deltas["eve"] = diff_maps(exp, act, exempt)
        # hosts.edn is a PATCH STEP, not a carried file: verify at EDN
        # level — base (key removed both sides) equal modulo
        # whitespace, and the key's value == the manifest's vector.
        base_hosts = (eve_rec if eve_rec is not None else eve_base).content("hosts.edn").decode()
        try:
            act_hosts = target.content(hp["path"]).decode()
            val_ok = norm_ws(extract_entry(act_hosts, hp["key"]) or "") == norm_ws(hp["value"])
            rest_ok = (norm_ws(strip_comments(remove_entry(act_hosts, hp["key"])))
                       == norm_ws(strip_comments(remove_entry(base_hosts, hp["key"]))))
        except Exception:
            val_ok = rest_ok = False
        if val_ok and rest_ok:
            out("  eve hosts.edn: patch step verified at EDN level (base equal modulo whitespace+comments, value == manifest)")
        elif rest_ok:
            # Value-only failure: the upstream base is intact; the
            # target carries a STALE patch-step value (an older
            # branch's vector) that the write replaces wholesale.
            # Recorded under the plain path so the standard
            # exact-SHA census_dispositions matcher can judge it.
            # A base failure (rest_ok False) keeps the decorated,
            # undispostionable entry below: upstream base drift
            # can never be dispositioned. (Lane J3 extension,
            # proven in run_dispo2_proof.py legs H4/H5.)
            deltas["eve"]["differ"].append(hp["path"])
            out("  eve hosts.edn: EDN PATCH CHECK FAIL value-only (base equal, value != manifest) — differ recorded under the plain path")
        else:
            deltas["eve"]["differ"].append(hp["path"] + " (EDN patch check)")
            out(f"  eve hosts.edn: EDN PATCH CHECK FAIL (value_ok={val_ok} base_ok={rest_ok})")
    else:
        out("  eve: no baseline in manifest — census skipped (pin unrecorded; see spec §1.2)")
        deltas["eve"] = {"differ": [], "missing": [], "extra": []}
    # First materialization (vendoring-auto extension, 2026-11-16): a
    # target that carries no COHOST-PIN yet has nothing recorded, so
    # there is nothing to violate — the write step + verify establish
    # the copy. Mirrors the eve arm's recorded-pin guard above.
    # Seeded targets (every campaign branch, every toy target) carry
    # the pin and never take this path.
    try: recorded = target.content(man["cohost"]["pin_path"]).decode().strip()
    except Exception: recorded = None
    if recorded is None:
        out("  cohost: no pin on target — census skipped (first materialization)")
        deltas["cohost"] = {"differ": [], "missing": [], "extra": []}
        return deltas
    tmpl = man["cohost"].get("recorded_source") or man["cohost"]["source"]
    coh_old = Source({**tmpl, "ref": recorded}, "cohost-recorded")
    if not coh_old.resolve(): stop(2, f"recorded cohost pin on target does not resolve: {recorded}")
    expc = {f"{cp}/{r[len('cohost/'):]}": s for r, s in coh_old.tree().items() if r.startswith("cohost/")}
    actc = {p: s for p, s in L["target"].items() if p.startswith(cp + "/")}
    deltas["cohost"] = diff_maps(expc, actc, exempt)
    return deltas

# ---------------------------------------------------------------- main
def main():
    argv = sys.argv[1:]
    opts = {"dry_run": False, "max_step": None, "workdir": None}
    man_path = None
    i = 0
    while i < len(argv):
        t = argv[i]
        if t == "--manifest": man_path = argv[i + 1]; i += 2
        elif t == "--dry-run": opts["dry_run"] = True; i += 1
        elif t == "--max-step": opts["max_step"] = int(argv[i + 1]); i += 2
        elif t == "--workdir": opts["workdir"] = argv[i + 1]; i += 2
        else: out(f"unknown arg {t}"); return 3
    if not man_path: out("--manifest required"); return 3
    man = json.load(open(man_path))
    name = man.get("name", os.path.splitext(os.path.basename(man_path))[0])
    workdir = opts["workdir"] or os.path.join(os.path.dirname(os.path.abspath(man_path)), "..", "work", name)
    workdir = os.path.abspath(workdir); os.makedirs(workdir, exist_ok=True)
    state = RunState(os.path.join(workdir, "run-state.json"))
    dry = opts["dry_run"]
    out(f"REVENDOR {name} manifest={man_path} mode={'DRY-RUN' if dry else 'FULL'} workdir={workdir}")

    eve_src = Source(man["eve"]["source"], "eve")
    coh_src = Source(man["cohost"]["source"], "cohost")
    eve_base = Source(man["eve"]["baseline"], "eve-baseline") if man["eve"].get("baseline") else None
    target = Target(man["target"])
    overlay = OverlaySource(man["overlay"]["source"], target)
    filt = None
    if man["eve"].get("filter_source"):
        fsp = man["eve"]["filter_source"]
        if fsp["kind"] == "github":
            ftext = gh_path_content(fsp["repo"], fsp["ref"], fsp["path"]).decode()
        else:
            ftext = open(os.path.join(fsp["root"], fsp["path"])).read()
        filt = ExcludeFilter(ftext)
    esrc = None
    if man["eve"].get("filter_source", {}).get("export"):
        esrc = Source(man["eve"]["filter_source"]["export"], "export-set")

    def maxed(n):
        if opts["max_step"] is not None and opts["max_step"] == n:
            out(f"MAX-STEP {n} REACHED — stopping (resume-test hook)"); return True
        return False

    # Run fingerprint: the manifest + its FROZEN tip (never the live
    # tip — a mid-write death changes the live tip, and resume must
    # still recognize its own run).
    F = hashlib.sha256(json.dumps({"man": man, "tip": target.expected_tip},
                                  sort_keys=True).encode()).hexdigest()
    listings_path = os.path.join(workdir, "listings.json")
    resume = False

    # ---- STEP 0: preflight, not FATAL -------------------------------------
    out("STEP 0 preflight")
    for lbl, src in [("eve", eve_src), ("cohost", coh_src)] + ([("eve-baseline", eve_base)] if eve_base else []):
        if not src.resolve(): stop(2, f"{lbl} pin does not resolve: {src.pin}")
        out(f"  pin {lbl} = {src.pin} (resolves)")
    export_paths = None
    if esrc is not None:
        if not esrc.resolve(): stop(2, "export-set pin does not resolve")
        export_paths = set(esrc.tree())
        out(f"  export set = {len(export_paths)} paths at {esrc.pin[:12]} (policy reference)")
    if man["eve"]["working_line_files"] == "exclude" and filt is None and export_paths is None:
        stop(2, "policy=exclude but no filter_source in manifest")
    if filt and filt.drops("hosts.edn"):
        stop(2, "export filter drops hosts.edn — the patch step would have no target")
    tip = target.tip()
    if target.expected_tip and tip != target.expected_tip:
        if (state.get("stage").get("hash") == F
                and state.get("census").get("info", {}).get("clean")
                and os.path.exists(listings_path)):
            # Our own mid-write death: the census verdict was recorded
            # against the frozen state; re-enter at the write step.
            resume = True
            out(f"  target tip = {tip} (frozen: {target.expected_tip})")
            out("  RESUME: run-state shows census CLEAN + a write in progress for this exact "
                "manifest — re-entering; the write step hash-skips what already landed")
        else:
            out(f"  target tip = {tip}\n  expected   = {target.expected_tip}")
            stop(6, "target tip moved since the manifest froze — re-freeze the manifest; pins never move under a running lane")
    else:
        out(f"  target tip = {tip} (== manifest expected tip)")
    for f in man["overlay"]["files"]:
        got = overlay.sha(f["path"])
        if got != f["sha"]:
            stop(2, f"overlay blob absent/different at canonical source: {f['path']} expected {f['sha']} got {got}")
    out(f"  overlay: {len(man['overlay']['files'])} files present at canonical source, SHAs == manifest")
    if maxed(0): return 0

    # ---- STEP 1: stage listings -------------------------------------------
    fingerprint = F
    if state.get("stage").get("hash") == fingerprint and os.path.exists(listings_path):
        L = json.load(open(listings_path))
        # The source listings are content-addressed by the manifest
        # fingerprint and stay valid across runs. The TARGET listing
        # does not: it snapshots the target as the staging run found
        # it, and that run's own write (or any later edit) moves the
        # target. Census, dispositions, plan and the write loop all
        # judge the target, so re-read it live and persist the
        # refresh; the skip saves the source fetches, not this walk.
        # (Defect found 2026-11-16, vendoring-auto lane: a same-
        # workdir re-run censused the pre-write snapshot — it false-
        # stopped a pristine copy naming every file missing, named a
        # pin bump's own files as deltas, and could not see an edit
        # planted after the bump. Proven: toy_rerun_proof.py.)
        L["target"] = target.tree()
        json.dump(L, open(listings_path, "w"), indent=0, sort_keys=True)
        out("STEP 1 stage SKIP (source listings hash-correct from a previous run; target re-read live)")
    else:
        L = {"eve": eve_src.tree(), "cohost": coh_src.tree(), "target": target.tree(),
             "baseline": eve_base.tree() if eve_base else {}}
        json.dump(L, open(listings_path, "w"), indent=0, sort_keys=True)
        state.put("stage", fingerprint)
        out(f"STEP 1 stage: eve {len(L['eve'])} files, cohost-src {len(L['cohost'])}, "
            f"baseline {len(L['baseline'])}, target {len(L['target'])}")
    if maxed(1): return 0

    # ---- STEP 2: census of the current copy vs its recorded pin ------------
    out("STEP 2 census (current copy vs recorded pin + overlay)")
    ep, cp = man["eve"]["dest_prefix"], man["cohost"]["dest_prefix"]
    exempt = {man["eve"]["pin_path"], man["cohost"]["pin_path"]}
    if resume:
        out("STEP 2 census SKIP (verdict recorded CLEAN at the freeze; the copy is mid-write)")
        census_clean = True
    else:
        deltas = run_census(man, L, target, eve_base)
        # Census dispositions (schema extension, Lane J2): a delta
        # whose (artifact, kind, path) is named in the manifest AND
        # whose CURRENT target blob is byte-exactly the SHA the
        # disposition names is an operator-judged delta (an
        # upstreamed fix, a dissolver) — printed DISPOSITIONED, it
        # does not stop the run. Any other delta still stops, and a
        # "missing" delta can never be dispositioned (a vanished
        # file is always judged live). The SHA match is the guard:
        # if the file drifts after the disposition was written, the
        # delta stops the run again.
        # Missing-overlay carve-out (Lane J3 extension, proven in
        # run_dispo2_proof.py legs H1-H3): a "missing" delta whose
        # path is a manifest OVERLAY file, absent from the recorded
        # baseline and absent from the target, names a branch that
        # never carried the canonical overlay file — the write
        # adds it verbatim from the canonical source (preflight
        # verifies its blob there). current_sha must be null.
        # Every other missing still stops.
        overlay_paths = {f["path"] for f in man["overlay"]["files"]}
        ep_prefix = man["eve"]["dest_prefix"] + "/"
        for ent in man.get("census_dispositions") or []:
            art, kind, p = ent["artifact"], ent["kind"], ent["path"]
            cur = L["target"].get(p)
            if (kind in ("differ", "extra")
                    and p in deltas.get(art, {}).get(kind, [])
                    and ent.get("current_sha") is not None
                    and ent.get("current_sha") == cur):
                deltas[art][kind].remove(p)
                out(f"  DISPOSITIONED {art} {kind}: {p} — {ent.get('disposition', '')}")
            elif (kind == "missing" and art == "eve"
                    and p in deltas.get("eve", {}).get("missing", [])
                    and ent.get("current_sha") is None
                    and p in overlay_paths
                    and p[len(ep_prefix):] not in L["baseline"]
                    and cur is None):
                deltas["eve"]["missing"].remove(p)
                out(f"  DISPOSITIONED eve missing (overlay not yet carried): {p} — {ent.get('disposition', '')}")
        n_delta = 0
        for art in ("eve", "cohost"):
            d = deltas[art]; n = len(d["differ"]) + len(d["missing"]) + len(d["extra"]); n_delta += n
            out(f"  {art}: differ={len(d['differ'])} missing={len(d['missing'])} extra={len(d['extra'])}")
            for kind in ("differ", "missing", "extra"):
                for p in d[kind]:
                    out(f"    DELTA {art} {kind}: {p}")
        json.dump(deltas, open(os.path.join(workdir, "census.json"), "w"), indent=1)
        census_clean = n_delta == 0
        state.put("census", F, {"clean": census_clean})
    if census_clean: out("  census CLEAN — current copy is exactly recorded pin + overlay")
    if maxed(2): return 0 if census_clean else 4

    # ---- compose post-state + plan -----------------------------------------
    def exclude_keep(rel):
        if export_paths is not None and rel not in export_paths: return False
        if filt and filt.drops(rel): return False
        return True
    keep = exclude_keep if man["eve"]["working_line_files"] == "exclude" else (lambda rel: True)
    post, loc, dropped = compose_post(man, eve_src, coh_src, overlay, keep)
    roots = (ep + "/", cp + "/")
    replaced = [p for p in post if p in L["target"] and L["target"][p] != post[p]]
    added = [p for p in post if p not in L["target"]]
    removed = [p for p in L["target"] if p.startswith(roots) and p not in post and p not in exempt]
    plan_lines = ([f"REPLACED {p}" for p in sorted(replaced)] + [f"ADDED {p}" for p in sorted(added)]
                  + [f"REMOVED {p}" for p in sorted(removed)])
    with open(os.path.join(workdir, "plan.txt"), "w") as f: f.write("\n".join(plan_lines) + "\n")
    out(f"PLAN: replaced={len(replaced)} added={len(added)} removed={len(removed)} "
        f"(full list: {os.path.join(workdir, 'plan.txt')})")
    out(f"  eve policy={man['eve']['working_line_files']}: source files dropped by policy={len(dropped)}")
    if man["eve"]["working_line_files"] == "carry" and (filt or export_paths is not None):
        cf = [r for r in eve_src.tree() if not exclude_keep(r)]
        out(f"  counterfactual policy=exclude would drop {len(cf)} working-line files (export path set + exclude globs)")
    if not census_clean:
        out("CENSUS STOP: non-overlay deltas over the recorded pins, named above — "
            "suspected marriage violations; disposition upstream first (spec §2.1/§5 step 0)")
        return 4
    if dry:
        out("DRY-RUN COMPLETE — census clean, plan printed, nothing written. Verdict: WOULD PROCEED")
        return 0
    if maxed(2): return 0

    # ---- STEP 3: write ------------------------------------------------------
    out("STEP 3 write")
    if target.kind == "local-dir":
        written = skipped = deleted = 0
        delay_ms = int(os.environ.get("REVENDOR_FILE_DELAY_MS", "0"))  # test hook (kill/resume proofs); 0 in production
        for p in sorted(post):
            dest = os.path.join(target.root, p)
            if os.path.exists(dest) and file_blob_sha(dest) == post[p]:
                skipped += 1; continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f: f.write(resolve_loc(loc[p], eve_src, coh_src, overlay))
            written += 1
            if delay_ms: __import__("time").sleep(delay_ms / 1000.0)
        for p in removed:
            dp = os.path.join(target.root, p)
            if os.path.exists(dp): os.unlink(dp); deleted += 1
        state.put("write", listing_hash(post), {"written": written, "skipped": skipped, "deleted": deleted})
        out(f"  local write: written={written} hash-skipped={skipped} deleted={deleted}")
    else:
        out(f"  remote: write set computed (replaced={len(replaced)} added={len(added)} removed={len(removed)}); executed at bank")
    if maxed(3): return 0

    # ---- STEP 4: verify by content ------------------------------------------
    out("STEP 4 verify")
    if target.kind == "local-dir":
        now = Target({"kind": "local-dir", "root": target.root}).tree()
        act = {p: s for p, s in now.items() if p.startswith(roots) or p in exempt}
        exp = {p: s for p, s in post.items()}
        d = diff_maps(exp, act)
        n = len(d["differ"]) + len(d["missing"]) + len(d["extra"])
        if n: out(f"  VERIFY FAIL: {d}"); return 5
        out(f"  VERIFY PASS — vendored roots == pin + overlay, blob for blob, both directions ({len(exp)} files)")
    else:
        out("  remote: verify executes on the bank read-back (step 5)")
    out("STEP 4b skew check: " + ("SKIP — no skew pair named in manifest" if not man.get("skew_check")
                                   else "named in manifest but not applicable to vendored roots"))
    if maxed(4): return 0

    # ---- STEP 5: bank ---------------------------------------------------------
    out("STEP 5 bank")
    if target.kind == "local-dir":
        rec = {"target": target.root, "post_listing_hash": listing_hash(post),
               "eve_pin": eve_src.pin, "cohost_pin": coh_src.pin}
        json.dump(rec, open(os.path.join(workdir, "bank-record.json"), "w"), indent=1)
        out(f"  BANKED (local bank record): post listing {rec['post_listing_hash'][:16]}")
    else:
        if not (replaced or added or removed):
            out("  SKIP — post-state already on the branch (write set empty)")
        elif not target.allow_write:
            stop(2, "manifest target.allow_write is false — refusing the remote bank")
        else:
            writes = {p: resolve_loc(loc[p], eve_src, coh_src, overlay) for p in replaced + added}
            sha = remote_bank(target, writes, removed,
                              f"revendor: eve -> {eve_src.pin[:12]}, cohost -> {coh_src.pin[:12]} (driver, spec §3)")
            now = gh_tree(target.repo, sha)
            act = {p: s for p, s in now.items() if p.startswith(roots) or p in exempt}
            d = diff_maps(post, act)
            n = len(d["differ"]) + len(d["missing"]) + len(d["extra"])
            if n: out(f"  BANK VERIFY FAIL: {d}"); return 5
            out(f"  BANKED {target.branch} -> {sha[:12]} read-back VERIFY PASS ({len(post)} files)")
    out("REVENDOR DONE")
    return 0

if __name__ == "__main__":
    sys.exit(main())
