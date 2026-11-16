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
        out[lib] = (repo, sha.group(1))
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
        if hp:
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
