#!/usr/bin/env bash
# Refactor rename: automately -> cluster93 / c93
#
# Drives the repo-wide rename from tools/rename-map.tsv, honoring the allowlist in
# tools/rename-keep.txt. Default mode is a read-only dry run.
#
#   tools/refactor-rename.sh [--dry-run|--apply|--verify-only] [--paths-only|--content-only] [--verbose] [--repo DIR]
#
# Exit codes: 0 ok, 1 usage/precondition error, 2 residue found (verify).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REFACTOR_TOOLS_DIR="$SCRIPT_DIR"

exec python3 - "$@" <<'PY'
from __future__ import annotations

import argparse
import collections
import os
import re
import subprocess
import sys

# "an Automately" -> after the rename the token always starts with a consonant,
# so the indefinite article must become "a" (optionally across `**`/`` ` ``/`_` markup).
ARTICLE_FIX = re.compile(r"\ban(\s+[*`_]*)(Cluster93|cluster93|c93)\b")

# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

def die(msg: str, code: int = 1):
    print(f"refactor-rename: {msg}", file=sys.stderr)
    sys.exit(code)


def git(root: str, *args: str) -> str:
    return subprocess.run(["git", "-C", root, *args],
                          capture_output=True, text=True, check=True).stdout


def glob_to_re(glob: str) -> re.Pattern:
    """Glob where `*` does not cross `/` and `**` does."""
    out, i = [], 0
    while i < len(glob):
        c = glob[i]
        if c == "*":
            if i + 1 < len(glob) and glob[i + 1] == "*":
                out.append(".*")
                i += 2
                if i < len(glob) and glob[i] == "/":
                    out.append("/?")
                    i += 1
            else:
                out.append("[^/]*")
                i += 1
        elif c == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(c))
            i += 1
    return re.compile("^" + "".join(out) + "$")


# --------------------------------------------------------------------------- #
# configuration
# --------------------------------------------------------------------------- #

def parse_map(path: str):
    rules = []
    with open(path, encoding="utf-8") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            while len(parts) > 1 and parts[-1].lstrip().startswith("#"):
                parts.pop()
            if len(parts) < 4:
                die(f"{path}:{lineno}: expected >=4 tab-separated columns")
            order = int(parts[0].strip())
            kind = parts[1].strip()
            match = parts[2]
            repl = parts[3]
            exclude = [g for g in (parts[4].split(",") if len(parts) > 4 and parts[4].strip() else []) if g.strip()]
            rules.append({"order": order, "kind": kind, "match": match,
                          "repl": repl, "exclude": [glob_to_re(g) for g in exclude]})
    rules.sort(key=lambda r: r["order"])
    return rules


def parse_keep(path: str):
    keep_paths, sections, notes = [], {}, []
    if not os.path.exists(path):
        return keep_paths, sections, notes
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            directive = parts[0].strip()
            if directive == "path" and len(parts) > 1:
                keep_paths.append(glob_to_re(parts[1].strip()))
            elif directive == "section" and len(parts) > 3:
                sections.setdefault(parts[1].strip(), []).append(
                    (re.compile(parts[2]), re.compile(parts[3])))
            elif directive == "note":
                notes.append("\t".join(parts[1:]))
    return keep_paths, sections, notes


def is_kept(path: str, keep_paths) -> bool:
    return any(p.match(path) for p in keep_paths)


def skip_set(lines, section_defs):
    """Line indices that fall inside an allowlisted section."""
    skip = set()
    for begin_re, end_re in section_defs:
        i = 0
        while i < len(lines):
            if begin_re.search(lines[i]):
                j = i
                while j < len(lines) and not end_re.search(lines[j]):
                    j += 1
                if j >= len(lines):
                    j = len(lines) - 1
                skip.update(range(i, j + 1))
                i = j + 1
            else:
                i += 1
    return skip


# --------------------------------------------------------------------------- #
# mapping
# --------------------------------------------------------------------------- #

def map_path(path: str, path_rules) -> str:
    def seg(s: str) -> str:
        for r in path_rules:
            if r["match"] in s:
                s = s.replace(r["match"], r["repl"])
        return s
    return "/".join(seg(p) for p in path.split("/"))


def rewrite_content(text: str, content_rules, sections_for_file):
    lines = text.split("\n")
    skip = skip_set(lines, sections_for_file)
    hits = collections.Counter()
    for idx, line in enumerate(lines):
        if idx in skip:
            continue
        for r in content_rules:
            if r["match"] in line:
                hits[r["order"]] += line.count(r["match"])
                line = line.replace(r["match"], r["repl"])
        # Article fix: "an Automately" -> "an Cluster93" is ungrammatical; every
        # target token starts with a consonant, so it must always read "a ...".
        line, n = ARTICLE_FIX.subn(r"a\1\2", line)
        if n:
            hits["article"] += n
        lines[idx] = line
    return "\n".join(lines), hits, skip


def scan_residue(lines, skip):
    for idx, line in enumerate(lines):
        if idx in skip:
            continue
        if re.search("automately", line, re.IGNORECASE):
            yield idx + 1, line.strip()


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main():
    ap = argparse.ArgumentParser(prog="refactor-rename.sh")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="report only (default)")
    mode.add_argument("--apply", action="store_true", help="perform the rename")
    mode.add_argument("--verify-only", action="store_true", help="scan current tree for residue")
    scope = ap.add_mutually_exclusive_group()
    scope.add_argument("--paths-only", action="store_true")
    scope.add_argument("--content-only", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--preview", metavar="PATH", default=None,
                    help="print the rewritten text of one file (respecting the allowlist) and exit")
    ap.add_argument("--repo", default=None)
    # accept and ignore an explicit --dry-run before mode resolution
    args = ap.parse_args()

    root = os.path.abspath(args.repo) if args.repo else \
        subprocess.run(["git", "rev-parse", "--show-toplevel"],
                       capture_output=True, text=True, check=True).stdout.strip()
    here = os.environ.get("REFACTOR_TOOLS_DIR") or os.path.dirname(os.path.abspath(__file__))
    map_file = os.path.join(here, "rename-map.tsv")
    keep_file = os.path.join(here, "rename-keep.txt")
    if not os.path.exists(map_file):
        die(f"missing {map_file}")

    rules = parse_map(map_file)
    path_rules = [r for r in rules if r["kind"] in ("path", "both")]
    content_rules = [r for r in rules if r["kind"] in ("content", "both")]
    keep_paths, sections, notes = parse_keep(keep_file)

    files = [f for f in git(root, "ls-files", "-z").split("\0") if f]
    branch = git(root, "branch", "--show-current").strip() or "(detached)"

    if args.preview:
        full = os.path.join(root, args.preview)
        if not os.path.exists(full):
            die(f"no such file: {args.preview}")
        text = open(full, "rb").read().decode("utf-8", "replace")
        if is_kept(args.preview, keep_paths):
            print(f"# {args.preview} is allowlisted; shown unchanged\n")
            print(text)
            return
        new_text, hits, _ = rewrite_content(text, content_rules, sections.get(args.preview, []))
        print(f"# preview: {args.preview}  ({sum(hits.values())} replacements)")
        print(new_text)
        return

    do_paths = not args.content_only
    do_content = not args.paths_only

    print(f"== refactor-rename {'verify' if args.verify_only else ('apply' if args.apply else 'dry run')} ==")
    print(f"repo:    {root}")
    print(f"branch:  {branch}")
    print(f"tracked: {len(files)} files")
    print(f"rules:   {len(path_rules)} path, {len(content_rules)} content")
    if notes:
        for n in notes:
            print(f"note:    {n}")
    print()

    # ---- path mapping ----------------------------------------------------- #
    moves, collisions = [], []
    if do_paths:
        seen = {}
        for f in files:
            nf = map_path(f, path_rules)
            if nf != f:
                moves.append((f, nf))
                if nf in seen and seen[nf] != f:
                    collisions.append((nf, seen[nf], f))
                seen.setdefault(nf, f)

    # ---- content mapping -------------------------------------------------- #
    changed, rule_hits = [], collections.Counter()
    binary, kept, residue = [], [], []
    total_repl = 0
    for f in files:
        full = os.path.join(root, f)
        try:
            data = open(full, "rb").read()
        except OSError:
            continue
        if b"\x00" in data[:8192]:
            binary.append(f)
            continue
        text = data.decode("utf-8", "replace")
        if is_kept(f, keep_paths):
            kept.append(f)
            continue
        if not do_content and not args.verify_only:
            continue
        new_text, hits, skip = rewrite_content(text, content_rules, sections.get(f, []))
        if hits:
            n = sum(hits.values())
            total_repl += n
            rule_hits.update(hits)
            changed.append((f, n))
        for lineno, line in scan_residue(new_text.split("\n"), skip):
            residue.append((f, lineno, line))

    # ---- report ----------------------------------------------------------- #
    if do_paths:
        print(f"-- PATH RENAMES: {len(moves)} file(s) --")
        tops = collections.Counter()
        for old, new in moves:
            tops[(old.split("/")[0], new.split("/")[0])] += 1
        for (o, n), c in sorted(tops.items()):
            if o != n:
                print(f"   {o}/  ->  {n}/   ({c} files)")
        # files whose basename (not dir) changes
        special = [(o, n) for o, n in moves if o.rsplit("/", 1)[:-1] == n.rsplit("/", 1)[:-1]]
        if special:
            print("   basename changes:")
            for o, n in sorted(special):
                print(f"     {o}  ->  {n}")
        if args.verbose:
            print("   all moves:")
            for o, n in moves:
                print(f"     {o}  ->  {n}")
        if collisions:
            print("   !! COLLISIONS:")
            for nf, a, b in collisions:
                print(f"     {a}  and  {b}  both ->  {nf}")
        print()

    print(f"-- CONTENT CHANGES: {len(changed)} file(s), {total_repl} replacement(s) --")
    for r in content_rules:
        if rule_hits.get(r["order"]):
            shown = r["match"] if len(r["match"]) <= 40 else r["match"][:37] + "..."
            print(f"   [{r['order']:>2}] {r['match']!r:42} -> {r['repl']!r:20} {rule_hits[r['order']]} hits")
    if rule_hits.get("article"):
        print(f"   [art] 'an <token>'                             -> 'a <token>'         {rule_hits['article']} hits")
    if args.verbose:
        for f, n in sorted(changed, key=lambda x: -x[1]):
            print(f"     {n:>4}  {f}")
    print()

    if kept:
        print(f"-- ALLOWLISTED (untouched): {len(kept)} file(s) --")
        for f in kept:
            print(f"   {f}")
        print()
    if binary:
        print(f"-- BINARY SKIPPED: {len(binary)} file(s) --")
        for f in binary:
            print(f"   {f}")
        print()

    print(f"-- RESIDUE (case-insensitive 'automately' surviving): {len(residue)} line(s) --")
    for f, lineno, line in residue[:200]:
        print(f"   {f}:{lineno}: {line[:120]}")
    if len(residue) > 200:
        print(f"   ... and {len(residue) - 200} more")
    print()

    if args.apply and not args.verify_only:
        _apply(root, files, path_rules, content_rules, sections, keep_paths, args)

    if args.verify_only and residue:
        sys.exit(2)
    sys.exit(0)


def _apply(root, files, path_rules, content_rules, sections, keep_paths, args):
    dirty = git(root, "status", "--porcelain", "--untracked-files=no").strip()
    if dirty:
        die("refusing --apply: tracked files are modified; commit or stash first")
    # 1. rewrite content
    if not args.paths_only:
        for f in files:
            full = os.path.join(root, f)
            try:
                data = open(full, "rb").read()
            except OSError:
                continue
            if b"\x00" in data[:8192] or is_kept(f, keep_paths):
                continue
            text = data.decode("utf-8", "replace")
            new_text, hits, _ = rewrite_content(text, content_rules, sections.get(f, []))
            if hits:
                open(full, "w", encoding="utf-8").write(new_text)
    # 2. move paths
    if not args.content_only:
        for f in files:
            nf = map_path(f, path_rules)
            if nf == f:
                continue
            os.makedirs(os.path.join(root, os.path.dirname(nf)), exist_ok=True)
            subprocess.run(["git", "-C", root, "mv", f, nf], check=True)
    print("apply: done. Review `git status`, then build/test.")


if __name__ == "__main__":
    main()
PY
