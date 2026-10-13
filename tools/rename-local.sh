#!/usr/bin/env bash
# Rename local-only (git-ignored) files from the Automately era to Cluster93.
#
#   tools/rename-local.sh [--dry-run|--apply] [paths...]
#
# The tracked tree is handled by tools/refactor-rename.sh (it only sees git-tracked
# files). This covers the files .git/info/exclude keeps local-only:
#   GEMINI.md  AGENTS.md(symlink)  lastsession.md  ideas/  .agents/
# and any paths passed on the command line.
#
# It applies the same ordered content rules as tools/rename-map.tsv, then the org
# fix adamnlynn/cluster93 -> TonyRice/cluster93. Dry run by default; --apply writes a
# tarball backup first.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REFACTOR_TOOLS_DIR="$SCRIPT_DIR"
export REFACTOR_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

exec python3 - "$@" <<'PY'
import argparse
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time

ROOT = os.environ.get("REFACTOR_REPO_ROOT") or os.getcwd()
TOOLS = os.environ.get("REFACTOR_TOOLS_DIR", os.path.join(ROOT, "tools"))
TS = time.strftime("%Y%m%d_%H%M%S")

# Default local-only targets (relative to the repo root).
DEFAULT_TARGETS = ["GEMINI.md", "lastsession.md", "ideas", ".agents"]

SKIP_DIRS = {".git", "node_modules", "target", ".next", "out"}
# Applied after the map rules.
EXTRA = [("adamnlynn/cluster93", "TonyRice/cluster93")]


def parse_content_rules(path):
    rules = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            while len(parts) > 1 and parts[-1].lstrip().startswith("#"):
                parts.pop()
            if len(parts) < 4:
                continue
            order = float(parts[0].strip())
            kind = parts[1].strip()
            if kind not in ("content", "both"):
                continue
            rules.append((order, parts[2], parts[3]))
    rules.sort(key=lambda r: r[0])
    return [(m, r) for _, m, r in rules] + EXTRA


def rewrite(text, rules):
    hits = 0
    for match, repl in rules:
        if match in text:
            hits += text.count(match)
            text = text.replace(match, repl)
    return text, hits


def is_binary(data):
    return b"\x00" in data[:8192]


def gather(targets):
    files = []
    for t in targets:
        p = t if os.path.isabs(t) else os.path.join(ROOT, t)
        if not os.path.exists(p):
            print(f"  (missing) {t}")
            continue
        if os.path.islink(p):
            print(f"  (symlink, skipped) {t}")
            continue
        if os.path.isfile(p):
            files.append(p)
        elif os.path.isdir(p):
            for dirpath, dirnames, filenames in os.walk(p):
                dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
                for name in filenames:
                    fp = os.path.join(dirpath, name)
                    if os.path.islink(fp):
                        continue
                    files.append(fp)
    return files


def main():
    ap = argparse.ArgumentParser(prog="rename-local.sh")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dry-run", action="store_true", help="report only (default)")
    g.add_argument("--apply", action="store_true", help="write changes (tarball backup first)")
    ap.add_argument("targets", nargs="*", help="paths to process (default: GEMINI.md lastsession.md ideas .agents)")
    args = ap.parse_args()

    rules = parse_content_rules(os.path.join(TOOLS, "rename-map.tsv"))
    targets = args.targets or DEFAULT_TARGETS
    files = gather(targets)

    print(f"== rename-local {'(APPLY)' if args.apply else '(dry run)'} ==")
    print(f"targets: {' '.join(targets)}")
    print(f"scanned: {len(files)} file(s)")
    print()

    changed, skipped_binary, total = [], 0, 0
    for fp in files:
        try:
            data = open(fp, "rb").read()
        except OSError:
            continue
        if is_binary(data):
            skipped_binary += 1
            continue
        text = data.decode("utf-8", "replace")
        new, hits = rewrite(text, rules)
        if hits:
            changed.append((fp, hits))
            total += hits
            if args.apply:
                open(fp, "w", encoding="utf-8").write(new)

    for fp, hits in sorted(changed, key=lambda x: -x[1])[:40]:
        print(f"   {hits:>5}  {os.path.relpath(fp, ROOT)}")
    if len(changed) > 40:
        print(f"   ... and {len(changed) - 40} more files")
    print()
    print(f"-- {len(changed)} file(s), {total} replacement(s); {skipped_binary} binary skipped --")

    if not args.apply:
        print("dry run complete. Re-run with --apply to write.")
        return
    if not changed:
        print("nothing to do.")
        return
    backup = os.path.join("/tmp/opencode", f"rename-local-{TS}.tar.gz")
    os.makedirs("/tmp/opencode", exist_ok=True)
    with tarfile.open(backup, "w:gz") as tar:
        for fp, _ in changed:
            tar.add(fp, arcname=os.path.relpath(fp, ROOT))
    print(f"applied. Backup: {backup}")


if __name__ == "__main__":
    main()
PY
