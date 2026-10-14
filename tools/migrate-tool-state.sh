#!/usr/bin/env bash
# Migrate agent / IDE memory and config from the Automately era to Cluster93.
#
#   tools/migrate-tool-state.sh [--apply] [--dry-run] [--move-directory] [--move-repo-dir]
#
# Dry run by default: nothing is written until --apply. Every file it changes is
# backed up beside itself as <file>.bak-<timestamp>.
#
# WHAT IT DOES
#   Name-keyed (always) — stale references to the old module names:
#     - ~/.gemini/  config + cache JSON (settings, mcp_config, keybindings, cache/*)
#       permission allow-list commands ("find automately-runtime-js/src …" ->
#       "find runtime-js/src …"), MCP server keys / "command" strings
#     - ~/.claude.json  the automately project's allowedTools / mcpServers
#     - ~/.cursor/mcp.json  MCP server keys / "command" strings
#
#   Path-keyed (only with --move-directory) — retarget the absolute repo path, for
#   when the checkout moves from ~/src/automately to ~/src/cluster93:
#     - ~/.gemini/antigravity-cli/settings.json  trustedWorkspaces
#     - ~/.gemini/antigravity-cli/cache/last_conversations.json  workspace-path keys
#       (this is what keeps your Antigravity conversations attached to the project)
#     - ~/.gemini/antigravity-cli/history.jsonl  command history lines
#     - ~/.claude.json  projects["<old>"] -> projects["<new>"]
#     - ~/.claude/projects/-home-…-automately  ->  ~/.claude/projects/-home-…-cluster93
#     - ~/.local/share/opencode/opencode.db  project.worktree / worktree.worktree /
#       session_v2.directory / session_v2.path
#
#   --move-repo-dir also moves the checkout itself (implies --move-directory), so a
#   single run relocates the tree and retargets all of the above. Run it from a
#   plain terminal, then restart your editor / agent sessions.
#
# DELIBERATELY NOT TOUCHED
#   ~/.gemini/antigravity-cli/{brain,log,conversations}/* and conversation_summaries.db
#   are historical transcripts (thousands of files, mostly binary DBs). They record
#   what happened under the old name; retargeting them changes history and is not
#   needed — the cache/settings above are what future sessions read. Same for
#   ~/.local/share/opencode/{snapshot,shell}/<hash> (opaque id, not a path hash).
#
# SCOPE: project-scoped. It never rewrites unrelated projects' memory, and it leaves
# ~/.claude/projects/-home-tony-src-jsync-io (which merely mentions the project) alone.
#
# SUGGESTED USE
#   tools/migrate-tool-state.sh                       # see what would change (no move)
#   tools/migrate-tool-state.sh --apply               # fix the stale module names
#   tools/migrate-tool-state.sh --move-repo-dir --apply   # move ~/src/automately -> ~/src/cluster93
set -euo pipefail
exec python3 - "$@" <<'PY'
import argparse
import glob
import json
import os
import re
import shutil
import sys
import time

HOME = os.path.expanduser("~")
TS = time.strftime("%Y%m%d_%H%M%S")

# Ordered, same intent as tools/rename-map.tsv, for config/memory strings.
RULES = [
    ("automately-v2", "cluster93"),
    ("AUTOMATELY_", "C93_"),
    ("automately-", "cluster93-"),
    (".automately-", ".cluster93-"),
    (".automately", ".cluster93"),
    ("automately.json", "cluster93.json"),
    ("automately:", "c93:"),
    ("automately/", "cluster93/"),
    ("automately.", "cluster93."),
    ("Automately", "Cluster93"),
]
BARE = re.compile(r"(?<![-\w.:/])automately(?![-\w.:/])")

# ~/.gemini config + cache JSON (relative to HOME). Historical brain/log are excluded.
GEMINI_JSON = [
    ".gemini/settings.json",
    ".gemini/config/config.json",
    ".gemini/config/mcp_config.json",
    ".gemini/antigravity/mcp_config.json",
    ".gemini/antigravity-cli/settings.json",
    ".gemini/antigravity-cli/keybindings.json",
    ".gemini/antigravity-cli/mcp_oauth_tokens.json",
    ".gemini/antigravity-cli/cache/last_conversations.json",
    ".gemini/antigravity-cli/cache/projects.json",
    ".gemini/antigravity-cli/cache/conversation_metadata.json",
    ".gemini/antigravity-cli/cache/onboarding.json",
]
GEMINI_HISTORY = ".gemini/antigravity-cli/history.jsonl"


def rename_text(s):
    if not isinstance(s, str) or "automately" not in s.lower():
        return s
    for a, b in RULES:
        s = s.replace(a, b)
    return BARE.sub("c93", s)


def transform_string(s, args):
    """A string that holds the repo path is retargeted (only when moving); anything
    else gets the name-keyed rename. A path is never name-rewritten, so an un-moved
    ~/src/automately is left intact."""
    if not isinstance(s, str):
        return s
    if args.old_dir and args.old_dir in s:
        return s.replace(args.old_dir, args.new_dir) if args.move_directory else s
    return rename_text(s)


def die(msg, code=1):
    print(f"migrate-tool-state: {msg}", file=sys.stderr)
    sys.exit(code)


class Report:
    def __init__(self):
        self.files = 0
        self.changes = 0
        self.examples = []

    def note(self, path, before, after):
        self.changes += 1
        if len(self.examples) < 40:
            self.examples.append((path, before, after))


def load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def write_json(path, data, apply):
    if not apply:
        return
    shutil.copy2(path, f"{path}.bak-{TS}")
    tmp = f"{path}.tmp-{TS}"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    os.replace(tmp, path)


def transform_obj(obj, args, path, report):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            nk = transform_string(k, args)
            if nk != k:
                report.note(path, f"key: {k}", nk)
            out[nk] = transform_obj(v, args, path, report)
        return out
    if isinstance(obj, list):
        return [transform_obj(v, args, path, report) for v in obj]
    if isinstance(obj, str):
        nv = transform_string(obj, args)
        if nv != obj:
            report.note(path, obj, nv)
        return nv
    return obj


# --------------------------------------------------------------------------- #
def do_gemini_json(args, report):
    for rel in GEMINI_JSON:
        path = os.path.join(HOME, rel)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            continue
        try:
            data = load_json(path)
        except Exception as e:
            print(f"  skip (unreadable): {rel}: {e}")
            continue
        before = report.changes
        new = transform_obj(data, args, path, report)
        if report.changes > before:
            write_json(path, new, args.apply)
            report.files += 1


def do_gemini_history(args, report):
    if not args.move_directory:
        return
    path = os.path.join(HOME, GEMINI_HISTORY)
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.readlines()
    changed = 0
    for i, line in enumerate(lines):
        nv = line.replace(args.old_dir, args.new_dir)
        if nv != line:
            report.note(path, f"line {i + 1}: {line.strip()[:80]}", nv.strip()[:80])
            lines[i] = nv
            changed += 1
    if changed:
        if args.apply:
            shutil.copy2(path, f"{path}.bak-{TS}")
            with open(path, "w", encoding="utf-8") as fh:
                fh.writelines(lines)
        report.files += 1


def do_claude_json(args, report):
    path = os.path.join(HOME, ".claude.json")
    if not os.path.exists(path):
        return
    data = load_json(path)
    before = report.changes
    servers = data.get("mcpServers")
    if isinstance(servers, dict) and "automately" in servers:
        report.note(path, "mcpServers.automately", "mcpServers.c93")
        servers["c93"] = servers.pop("automately")
        if isinstance(servers["c93"], dict) and servers["c93"].get("command"):
            servers["c93"]["command"] = rename_text(servers["c93"]["command"])
    projects = data.get("projects", {})
    for key in list(projects.keys()):
        if args.old_dir not in key:
            continue
        entry = projects[key]
        for field in ("allowedTools", "disallowedTools", "enabledMcpjsonServers",
                      "disabledMcpjsonServers", "mcpContextUris"):
            if isinstance(entry.get(field), list):
                for i, v in enumerate(entry[field]):
                    nv = transform_string(v, args)
                    if nv != v:
                        report.note(path, v, nv)
                        entry[field][i] = nv
        if isinstance(entry.get("mcpServers"), dict):
            for name in list(entry["mcpServers"].keys()):
                if name == "automately":
                    entry["mcpServers"]["c93"] = entry["mcpServers"].pop("automately")
        if args.move_directory:
            new_key = args.new_dir + key[len(args.old_dir):]
            report.note(path, f"projects[{key}]", f"projects[{new_key}]")
            projects[new_key] = projects.pop(key)
    if report.changes > before:
        write_json(path, data, args.apply)
        report.files += 1


def do_cursor_mcp(args, report):
    for path in (os.path.join(HOME, ".cursor", "mcp.json"),):
        if not os.path.exists(path):
            continue
        try:
            data = load_json(path)
        except Exception:
            continue
        before = report.changes
        new = transform_obj(data, args, path, report)
        if report.changes > before:
            write_json(path, new, args.apply)
            report.files += 1


def do_claude_project_dir(args, report):
    if not args.move_directory:
        return
    enc_old = "-" + args.old_dir.lstrip("/").replace("/", "-")
    enc_new = "-" + args.new_dir.lstrip("/").replace("/", "-")
    src = os.path.join(HOME, ".claude", "projects", enc_old)
    dst = os.path.join(HOME, ".claude", "projects", enc_new)
    if os.path.isdir(src) and not os.path.exists(dst):
        report.note(src, src, dst)
        if args.apply:
            os.rename(src, dst)
        report.files += 1


def do_opencode_db(args, report):
    if not args.move_directory:
        return
    db = os.path.join(HOME, ".local", "share", "opencode", "opencode.db")
    if not os.path.exists(db):
        return
    import sqlite3
    con = sqlite3.connect(db)
    try:
        for table, col in (("project", "worktree"), ("worktree", "worktree"),
                           ("session_v2", "directory"), ("session_v2", "path")):
            try:
                rows = con.execute(
                    f"select rowid, {col} from {table} where {col} like ?",
                    (f"%{args.old_dir}%",)).fetchall()
            except sqlite3.OperationalError:
                continue
            for rowid, val in rows:
                nv = val.replace(args.old_dir, args.new_dir)
                report.note(db, f"{table}.{col}[{rowid}]", nv)
                if args.apply:
                    con.execute(f"update {table} set {col}=? where rowid=?", (nv, rowid))
        if args.apply:
            con.commit()
        report.files += 1
    finally:
        con.close()


def do_move_repo_dir(args, report):
    if not args.move_repo_dir:
        return
    if not os.path.isdir(args.old_dir):
        die(f"cannot move: {args.old_dir} is not a directory")
    if os.path.exists(args.new_dir):
        die(f"cannot move: {args.new_dir} already exists")
    report.note(args.old_dir, args.old_dir, args.new_dir)
    if args.apply:
        os.rename(args.old_dir, args.new_dir)


# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser(prog="migrate-tool-state.sh")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dry-run", action="store_true", help="report only (default)")
    g.add_argument("--apply", action="store_true", help="write changes (with .bak copies)")
    ap.add_argument("--move-directory", action="store_true",
                    help="also retarget path-keyed state (Gemini/Claude/OpenCode)")
    ap.add_argument("--move-repo-dir", action="store_true",
                    help="move the checkout to --new-dir and retarget (implies --move-directory)")
    ap.add_argument("--old-dir", default=os.path.join(HOME, "src", "automately"))
    ap.add_argument("--new-dir", default=os.path.join(HOME, "src", "cluster93"))
    args = ap.parse_args()
    if args.move_repo_dir:
        args.move_directory = True

    print(f"== migrate-tool-state {'(APPLY)' if args.apply else '(dry run)'} ==")
    print(f"project: {args.old_dir}"
          + (f"  ->  {args.new_dir}" if args.move_directory else "  (path unchanged)"))
    print()

    report = Report()
    do_gemini_json(args, report)
    do_gemini_history(args, report)
    do_claude_json(args, report)
    do_cursor_mcp(args, report)
    do_claude_project_dir(args, report)
    do_opencode_db(args, report)
    do_move_repo_dir(args, report)

    print(f"-- {report.changes} change(s) across {report.files} file(s) --")
    for path, before, after in report.examples:
        print(f"   {path}")
        print(f"      - {before}")
        print(f"      + {after}")
    if report.changes > len(report.examples):
        print(f"   ... and {report.changes - len(report.examples)} more")
    print()
    if report.changes == 0:
        print("nothing to do.")
        return
    if args.apply:
        print("applied. Backups are beside each changed file as *.bak-<timestamp>.")
        if args.move_repo_dir:
            print(f"moved the checkout to {args.new_dir}; restart your editor / agent sessions.")
        else:
            print("restart your editor / agent sessions to pick up the changes.")
    else:
        print("dry run complete. Re-run with --apply to write (backups are kept).")


if __name__ == "__main__":
    main()
PY
