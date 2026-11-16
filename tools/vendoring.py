#!/usr/bin/env python3
"""vendoring.py — cohost's vendoring: deps.edn declarations resolved
into vendored trees. Entry point: `bin/cohost vendor`, run in a
consumer project (or this file directly, with the project root as
argv[1]).

The npm shape, for deps.edn: the consumer declares a coordinate
and a pinned sha in deps.edn; this module resolves the declaration
into the tree through the one re-vendor driver (tools/revendor.py,
the §3-contract engine in VENDORING-SPEC.md's line — census,
hash-verified write, verify, bank). The vendored sources are
materialized in the consumer's tree and compiled by the consumer
as its own vendored code — never a classpath library, never
loaded on top of the consumer.

One variable per number: the pins live ONLY in the consumer's
deps.edn. This module reads them from deps.edn, generates the
driver's manifest from them, and prints the pins it enforces.
Which libs are vendored, and where they land, is data — PROFILES
below; a new vendored dep is a new profile row, not new machinery.

Overlay (a consumer's own host seat on a vendored lib): the
coordinate may name a directory of the consumer,
`:cohost.vendor/overlay "<dir>"`. Files under `<dir>/<dest>/`
are carried into the vendored root at the same relative paths
(the driver's named overlay set: listed by blob sha, verified by
content, never part of the upstream census), and
`<dir>/<dest>.hosts-row.edn` — one hosts.edn key followed by its
value — replaces the profile's hosts.edn patch step with the
consumer's row. The overlay lives outside the vendored root, in
the consumer's own history; the vendored root stays derived
state. A fix to upstream content is still made upstream.

Resumability: the driver writes its bank record only when a run
completes. If a run dies without recording an exit (kill, power),
the next run removes the vendored roots — derived state — and
materializes from empty, finishing byte-identical. A run that
exits, on any code, never triggers the wipe: a census stop (a
local edit inside the vendored copy) re-stops on the next run,
naming the file, and changes nothing.

Exit codes: the driver's, propagated (0 pass · 2 preflight stop ·
3 usage/io · 4 census stop, deltas named · 5 verify fail).
Module-level failures (declaration unreadable, driver missing)
exit 3 with the reason on stderr.
"""
import importlib.util, json, os, re, shutil, subprocess, sys

# lib -> profile. "artifact" is the driver's manifest key; "dest"
# and "pin_path" are relative to the consumer root; "tree_root" is
# the vendored subtree inside the source repo ("" = repo root);
# "hosts_patch" names a file in the artifact whose one entry is
# carried at its pinned value as a patch step (the value is
# extracted from the pinned file itself at resolve time).
PROFILES = {
    "eve/eve": {
        "artifact": "eve", "dest": "eve", "pin_path": "eve/EVE-PIN",
        "tree_root": "", "working_line_files": "carry",
        "hosts_patch": {"file": "hosts.edn", "path": "eve/hosts.edn", "key": ":vs"},
    },
    "cohost/cohost": {
        "artifact": "cohost", "dest": "vendor/cohost",
        "pin_path": "vendor/cohost/COHOST-PIN", "tree_root": "src",
    },
}

HERE = os.path.dirname(os.path.abspath(__file__))
OVERLAYS = {}  # lib -> the consumer's overlay dir, when its coordinate declares one

def die(msg):
    print(f"cohost vendor: {msg}", file=sys.stderr)
    sys.exit(3)

# ---------------------------------------------------------------- deps.edn
def read_declaration(root):
    """deps.edn -> {lib: (repo, sha)} for every profiled lib.
    The declaration is the only pin file; anything unreadable or
    ambiguous stops loud. Libs with no profile are not vendored
    and are not read here."""
    path = os.path.join(root, "deps.edn")
    if not os.path.exists(path):
        die(f"no deps.edn in {root} — vendored deps are declared there and nowhere else")
    text = open(path).read()
    lines = [l for l in text.splitlines() if not l.lstrip().startswith(";")]
    body = "\n".join(lines)
    out = {}
    for lib in PROFILES:
        m = re.search(re.escape(lib) + r"\s*\{([^{}]*)\}", body)
        if not m:
            continue  # not declared in this project: not vendored here
        url = re.search(r':git/url\s+"([^"]+)"', m.group(1))
        sha = re.search(r':git/sha\s+"([0-9a-f]{40})"', m.group(1))
        if not url or not sha:
            die(f"deps.edn: {lib} must declare :git/url and a 40-hex :git/sha")
        repo = re.sub(r"^https://github\.com/", "", url.group(1))
        repo = re.sub(r"\.git$", "", repo)
        ov = re.search(r':cohost\.vendor/overlay\s+"([^"]+)"', m.group(1))
        out[lib] = (repo, sha.group(1))
        if ov: OVERLAYS[lib] = ov.group(1)
    if not out:
        die("deps.edn declares no vendored dep (profiled libs: "
            + ", ".join(sorted(PROFILES)) + ")")
    return out

# ---------------------------------------------------------------- driver
def load_driver():
    path = os.path.join(HERE, "revendor.py")
    if not os.path.exists(path):
        die("tools/revendor.py is missing beside vendoring.py — the driver "
            "ships with cohost's vendoring and is never fetched piecemeal")
    spec = importlib.util.spec_from_file_location("revendor", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, path

# ---------------------------------------------------------------- manifest
def build_manifest(root, decl, driver):
    man = {"name": os.path.basename(root.rstrip("/")) or "consumer",
           "target": {"kind": "local-dir", "root": root}}
    overlay = {"source": {"kind": "target"}, "files": []}
    for lib, (repo, sha) in decl.items():
        p = PROFILES[lib]
        art = {"source": {"kind": "github", "repo": repo, "ref": sha,
                          "tree_root": p["tree_root"]},
               "recorded_source": {"kind": "github", "repo": repo,
                                   "tree_root": p["tree_root"]},
               "dest_prefix": p["dest"], "pin_path": p["pin_path"]}
        if p.get("working_line_files"):
            art["working_line_files"] = p["working_line_files"]
        man[p["artifact"]] = art
        hp = p.get("hosts_patch")
        ov_dir = OVERLAYS.get(lib)
        row = None
        if ov_dir:
            ov_root = os.path.join(root, ov_dir)
            if not os.path.isdir(ov_root):
                die(f"deps.edn: {lib} declares :cohost.vendor/overlay {ov_dir!r}, which is not a directory of {root}")
            if overlay["source"].get("kind") == "local-dir" and overlay["source"]["root"] != ov_root:
                die("deps.edn: every lib's :cohost.vendor/overlay must name the same directory "
                    f"({overlay['source']['root']} vs {ov_root})")
            overlay["source"] = {"kind": "local-dir", "root": ov_root}
            sub = os.path.join(ov_root, p["dest"])
            for dp, dns, fns in os.walk(sub):
                dns.sort()
                for fn in sorted(fns):
                    full = os.path.join(dp, fn)
                    overlay["files"].append({"path": os.path.relpath(full, ov_root),
                                             "sha": driver.file_blob_sha(full)})
            row_path = os.path.join(ov_root, p["dest"] + ".hosts-row.edn")
            if os.path.exists(row_path):
                if not hp:
                    die(f"{ov_dir}/{p['dest']}.hosts-row.edn: {lib} has no hosts.edn patch step to carry a row")
                text = "\n".join(l for l in open(row_path).read().splitlines()
                                 if not l.lstrip().startswith(";")).strip()
                m = re.match(r"(:[^\s\[\]{}()]+)\s+(.+)\Z", text, re.S)
                if not m:
                    die(f"{ov_dir}/{p['dest']}.hosts-row.edn must hold one hosts.edn key followed by its value")
                row = (m.group(1), m.group(2).strip())
        if hp and row:
            overlay["hosts_edn_patch"] = {"path": hp["path"], "key": row[0], "value": row[1]}
        elif hp:
            try:
                pinned = driver.gh_path_content(repo, sha, hp["file"]).decode()
            except Exception as e:
                die(f"cannot read {hp['file']} at the declared {lib} pin {sha[:12]}: {e}")
            value = driver.extract_entry(pinned, hp["key"])
            if value is None:
                die(f"{hp['file']} at the declared {lib} pin carries no {hp['key']} row")
            overlay["hosts_edn_patch"] = {"path": hp["path"], "key": hp["key"],
                                          "value": value}
    man["overlay"] = overlay
    return man

# ---------------------------------------------------------------- recovery
def wipe_derived(root, workdir, decl):
    for lib in decl:
        p = os.path.join(root, PROFILES[lib]["dest"])
        if os.path.isdir(p):
            shutil.rmtree(p)
    drv = os.path.join(workdir, "driver")
    if os.path.isdir(drv):
        shutil.rmtree(drv)

def recover(root, workdir, decl):
    bank_path = os.path.join(workdir, "driver", "bank-record.json")
    banked = {}
    if os.path.exists(bank_path):
        try: banked = json.load(open(bank_path))
        except Exception: banked = {}
    complete = True
    for lib, (repo, sha) in decl.items():
        p = PROFILES[lib]
        pin_path = os.path.join(root, p["pin_path"])
        recorded = open(pin_path).read().strip() if os.path.exists(pin_path) else None
        if recorded != sha or banked.get(p["artifact"] + "_pin") != recorded:
            complete = False
    inflight = os.path.join(workdir, "inflight.json")
    if complete:
        if os.path.exists(inflight):
            os.unlink(inflight)  # stale: the run banked before the wrapper died
        return
    if os.path.exists(inflight):
        print("cohost vendor: a previous resolve died without recording an exit — "
              "removing the derived vendored roots and materializing from empty")
        wipe_derived(root, workdir, decl)
        os.unlink(inflight)

# ---------------------------------------------------------------- main
def main():
    root = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.getcwd()
    workdir = os.path.join(root, ".git", "vendor-work")
    if not os.path.isdir(os.path.join(root, ".git")):
        workdir = os.path.join(root, ".vendor-work")
    os.makedirs(workdir, exist_ok=True)

    decl = read_declaration(root)
    driver, driver_path = load_driver()
    summary = ", ".join(f"{lib} {repo}@{sha[:12]} -> {PROFILES[lib]['dest']}/"
                        for lib, (repo, sha) in sorted(decl.items()))
    print(f"cohost vendor: resolving deps.edn in {root} — {summary}")

    man = build_manifest(root, decl, driver)
    recover(root, workdir, decl)

    man_path = os.path.join(workdir, "manifest.runtime.json")
    json.dump(man, open(man_path, "w"), indent=1, sort_keys=True)

    inflight = os.path.join(workdir, "inflight.json")
    json.dump({lib: sha for lib, (repo, sha) in decl.items()}, open(inflight, "w"))
    r = subprocess.run([sys.executable, driver_path,
                        "--manifest", man_path,
                        "--workdir", os.path.join(workdir, "driver")])
    if os.path.exists(inflight):
        os.unlink(inflight)
    if r.returncode == 0:
        print(f"cohost vendor: vendored declarations are current "
              f"(driver-verified, blob for blob) — {summary}")
    return r.returncode

if __name__ == "__main__":
    sys.exit(main())
