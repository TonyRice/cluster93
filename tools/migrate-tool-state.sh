#!/usr/bin/env bash
# Migrate agent / IDE memory and config from the Automately era to Cluster93.
#
#   tools/migrate-tool-state.sh [--apply] [--dry-run] [--move-directory]
#
# Dry run by default. This script is deliberately PROJECT-SCOPED: it never
# rewrites unrelated projects' memory. It updates only:
#   - MCP server keys / "command" strings that point at the old `automately` CLI
#   - Antigravity permission allow-list commands that name `automately-*` modules
#   - the automately project's own entry in ~/.claude.json
#
# With --move-directory (off by default; the working tree move is deferred) it
# additionally retargets path-keyed state:
#   - ~/.claude.json projects key + ~/.claude/projects/<encoded> directory
#   - Antigravity trustedWorkspaces entries
#   - the OpenCode state DB (project.worktree, session_v2.directory)
#
# Always run with --dry-run first; --apply writes timestamped .bak copies.
set -euo pipefail
exec python3 - "$@" <<'PY'
import argparse
import json
import os
import re
import shutil
import sys
import time

HOME = os.path.expanduser("~")
TS = time.strftime("%Y%m%d_%H%M%S")

# Ordered, same intent as tools/rename-map.tsv but for config/memory strings.
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


def rename_text(s):
    if not isinstance(s, str) or "automately" not in s.lower():
        return s
    for a, b in RULES:
        s = s.replace(a, b)
    return BARE.sub("c93", s)


class Report:
    def __init__(self):
        self.files = 0
        self.changes = 0
        self.examples = []

    def note(self, path, before, after):
        self.changes += 1
        if len(self.examples) < 30:
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


def transform_list(path, items, report, apply):
    changed = False
    for i, v in enumerate(items):
        nv = rename_text(v)
        if nv != v:
            report.note(path, v, nv)
            items[i] = nv
            changed = True
    return changed


# --------------------------------------------------------------------------- #
def do_antigravity_settings(args, report):
    path = os.path.join(HOME, ".gemini", "antigravity-cli", "settings.json")
    if not os.path.exists(path):
        return
    data = load_json(path)
    changed = False
    perms = data.get("permissions", {})
    for key in ("allow", "deny", "ask"):
        if isinstance(perms.get(key), list):
            changed |= transform_list(path, perms[key], report, args.apply)
    if args.move_directory and isinstance(data.get("trustedWorkspaces"), list):
        for i, v in enumerate(data["trustedWorkspaces"]):
            if isinstance(v, str) and v.startswith(args.old_dir):
                nv = args.new_dir + v[len(args.old_dir):]
                report.note(path, v, nv)
                data["trustedWorkspaces"][i] = nv
                changed = True
    if changed:
        write_json(path, data, args.apply)
        report.files += 1


def do_claude_json(args, report):
    path = os.path.join(HOME, ".claude.json")
    if not os.path.exists(path):
        return
    data = load_json(path)
    changed = False
    # top-level MCP servers
    servers = data.get("mcpServers")
    if isinstance(servers, dict) and "automately" in servers:
        report.note(path, "mcpServers.automately", "mcpServers.c93")
        servers["c93"] = servers.pop("automately")
        if isinstance(servers["c93"], dict) and servers["c93"].get("command"):
            servers["c93"]["command"] = rename_text(servers["c93"]["command"])
        changed = True
    # only the automately project's own entry
    projects = data.get("projects", {})
    for key in list(projects.keys()):
        if args.old_dir not in key:
            continue
        entry = projects[key]
        for field in ("allowedTools", "disallowedTools", "enabledMcpjsonServers",
                      "disabledMcpjsonServers", "mcpContextUris"):
            if isinstance(entry.get(field), list):
                changed |= transform_list(path, entry[field], report, args.apply)
        if "mcpServers" in entry and isinstance(entry["mcpServers"], dict):
            for name in list(entry["mcpServers"].keys()):
                if name == "automately":
                    entry["mcpServers"]["c93"] = entry["mcpServers"].pop("automately")
                    changed = True
        if args.move_directory:
            new_key = args.new_dir + key[len(args.old_dir):]
            report.note(path, f"projects[{key}]", f"projects[{new_key}]")
            projects[new_key] = projects.pop(key)
            changed = True
    if changed:
        write_json(path, data, args.apply)
        report.files += 1


def do_named_mcp_configs(args, report):
    for path in (
        os.path.join(HOME, ".cursor", "mcp.json"),
        os.path.join(HOME, ".gemini", "antigravity", "mcp_config.json"),
        os.path.join(HOME, ".gemini", "settings.json"),
    ):
        if not os.path.exists(path):
            continue
        try:
            data = load_json(path)
        except Exception:
            continue
        servers = data.get("mcpServers") if isinstance(data, dict) else None
        if not isinstance(servers, dict):
            continue
        changed = False
        for name in list(servers.keys()):
            entry = servers[name]
            new_name = rename_text(name)
            if new_name != name:
                report.note(path, f"mcpServers.{name}", f"mcpServers.{new_name}")
                servers[new_name] = servers.pop(name)
                changed = True
                entry = servers[new_name]
            if isinstance(entry, dict) and isinstance(entry.get("command"), str):
                nv = rename_text(entry["command"])
                if nv != entry["command"]:
                    report.note(path, entry["command"], nv)
                    entry["command"] = nv
                    changed = True
        if changed:
            write_json(path, data, args.apply)
            report.files += 1


def do_claude_project_dir(args, report):
    if not args.move_directory:
        return
    src = os.path.join(HOME, ".claude", "projects", "-" + args.old_dir.lstrip("/").replace("/", "-"))
    dst = os.path.join(HOME, ".claude", "projects", "-" + args.new_dir.lstrip("/").replace("/", "-"))
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
        for table, col in (("project", "worktree"), ("session_v2", "directory")):
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


# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser(prog="migrate-tool-state.sh")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dry-run", action="store_true", help="report only (default)")
    g.add_argument("--apply", action="store_true", help="write changes (with .bak copies)")
    ap.add_argument("--move-directory", action="store_true",
                    help="also retarget path-keyed state (deferred by default)")
    ap.add_argument("--old-dir", default=os.path.join(HOME, "src", "automately"))
    ap.add_argument("--new-dir", default=os.path.join(HOME, "src", "cluster93"))
    args = ap.parse_args()

    print(f"== migrate-tool-state {'(APPLY)' if args.apply else '(dry run)'} ==")
    print(f"project: {args.old_dir}  (move-directory: {'yes' if args.move_directory else 'no'})")
    print()

    report = Report()
    do_antigravity_settings(args, report)
    do_claude_json(args, report)
    do_named_mcp_configs(args, report)
    do_claude_project_dir(args, report)
    do_opencode_db(args, report)

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
    elif args.apply:
        print("applied. Backups are written beside each file as *.bak-<timestamp>.")
    else:
        print("dry run complete. Re-run with --apply to write (backups are kept).")


if __name__ == "__main__":
    main()
PY
