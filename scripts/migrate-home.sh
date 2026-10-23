#!/usr/bin/env bash
# Migrate local Cluster93 state from the pre-rename Automately layout.
#
#   scripts/migrate-home.sh [--apply] [--dry-run]
#
# Dry run by default. Moves/rewrites:
#   ~/.automately            -> ~/.cluster93
#   ~/.config/automately     -> ~/.config/cluster93
#   AUTOMATELY_* env names   -> C93_*           (inside local.env / node.meta)
#   automately.pid           -> c93.pid
#   mcp.json "automately"    -> "c93"           (server key + command)
#
# Idempotent and merge-safe: if the target already exists, entries are merged
# and the source is only removed once empty. Honours AUTOMATELY_HOME / C93_HOME
# and AUTOMATELY_CONFIG_DIR / C93_CONFIG_DIR. The rename of durable Ignite data
# is NOT performed here (the new build uses a new SQL schema and data region);
# existing clusters should start fresh or migrate deliberately.
set -euo pipefail

APPLY=0
for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        --dry-run) APPLY=0 ;;
        -h | --help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "migrate-home: unknown argument: $arg" >&2; exit 1 ;;
    esac
done

say() { printf '%s\n' "$*"; }
act() { if [ "$APPLY" = 1 ]; then "$@"; else say "   would: $*"; fi; }

OLD_HOME="${AUTOMATELY_HOME:-$HOME/.automately}"
NEW_HOME="${C93_HOME:-$HOME/.cluster93}"
OLD_CONFIG="${AUTOMATELY_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/automately}"
NEW_CONFIG="${C93_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/cluster93}"

[ "$APPLY" = 1 ] && say "== migrate-home (APPLY) ==" || say "== migrate-home (dry run; pass --apply to execute) =="
say "home:   $OLD_HOME -> $NEW_HOME"
say "config: $OLD_CONFIG -> $NEW_CONFIG"
say

# ---- merge a directory tree --------------------------------------------------
merge_dir() {
    local src="$1" dst="$2"
    [ -e "$src" ] || { say "skip (absent): ${src#$HOME/}"; return; }
    if [ ! -e "$dst" ]; then
        act mv "$src" "$dst"
    else
        say "merge: ${src#$HOME/} -> ${dst#$HOME/} (keep existing; copy missing)"
        act cp -a -n "$src/." "$dst/"
        [ "$APPLY" = 1 ] && say "   note: source kept at ${src#$HOME/}; remove it after verifying"
    fi
}

# ---- text substitutions in place --------------------------------------------
rewrite() {
    local f="$1"
    [ -f "$f" ] || return 0
    say "rewrite: ${f#$HOME/}"
    if [ "$APPLY" = 1 ]; then
        sed -i.rename-bak \
            -e 's/\.automately/.cluster93/g' \
            -e 's/AUTOMATELY_/C93_/g' \
            -e 's/Automately/Cluster93/g' \
            -e 's/"automately"/"c93"/g' \
            -e 's/automately/c93/g' \
            "$f"
        rm -f "$f.rename-bak"
    fi
}

# ---- rename a single file ----------------------------------------------------
rename_file() {
    local src="$1" dst="$2"
    [ -e "$src" ] || { say "skip (absent): ${src#$HOME/}"; return; }
    [ -e "$dst" ] && { say "keep existing: ${dst#$HOME/}"; return; }
    act mv "$src" "$dst"
}

say "--- home files (rewritten in place, then moved) ---"
rewrite "$OLD_HOME/local.env"
rewrite "$OLD_HOME/mcp.json"
# node.meta is regenerated from the current checkout by `c93 dev build` (write_meta),
# so it is left alone rather than half-rewritten here.
rename_file "$OLD_HOME/automately.pid" "$OLD_HOME/c93.pid"

say
say "--- home directory ---"
merge_dir "$OLD_HOME" "$NEW_HOME"

say
say "--- config directory ---"
merge_dir "$OLD_CONFIG" "$NEW_CONFIG"

say
say "--- CLI binary ---"
if [ -x "$HOME/.local/bin/automately" ] && [ ! -e "$HOME/.local/bin/c93" ]; then
    act mv "$HOME/.local/bin/automately" "$HOME/.local/bin/c93"
fi
[ -e "$HOME/.local/bin/c93" ] && say "note: rebuild the native CLI with scripts/build-cli.sh to refresh ~/.local/bin/c93" || true

say
if [ "$APPLY" = 1 ]; then
    say "done. Restart any running node: c93 dev down && c93 dev up"
else
    say "dry run complete. Re-run with --apply to make the changes."
fi
