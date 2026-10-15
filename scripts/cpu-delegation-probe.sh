#!/usr/bin/env bash
# CPU-delegation probe for tenant CPU limits (D-158).
#
# Answers one question that cannot be answered by a unit test: does cgroup v2
# CPU delegation actually work under a systemd unit shaped like the one we ship?
# `cpu.max` is a kernel interface, and whether it is *available* (the controller
# is delegated into the service's cgroup) and *writable* (by the service's user,
# under ProtectSystem=strict) is a property of the host, not of our code.
#
# It also surfaces the base-path question: CgroupMemoryLimiter defaults to the
# absolute /sys/fs/cgroup/cluster93, but under a Delegate=yes unit the only
# writable subtree is the unit's own cgroup — so a real install may be silently
# applying no cgroup limit at all. Step A3 is where that shows.
#
# Usage:
#   sudo scripts/cpu-delegation-probe.sh            # run the probe
#   C93_PROBE_USER=cluster93 sudo scripts/cpu-delegation-probe.sh
#   scripts/cpu-delegation-probe.sh --dry-run       # print the plan, change nothing
#
# Exit: 0 all steps PASS · 1 a step FAILED (informative, not fatal) ·
#       2 preflight refused (not a Linux + systemd + cgroup-v2 host, or no usable user).
#
# Safety — the point of the exercise:
#   1. It creates exactly one temporary unit, c93-cgprobe-<pid>.service, unique to
#      this run, and removes it on exit (trap on EXIT/INT/TERM), however it exits.
#   2. It touches only that unit and the cgroup subtree beneath it. No other
#      systemctl verb is ever called; no rm outside its own unit file; no
#      `system prune`, no daemon restarts, no service changes.
#   3. It creates no users. The service user is a non-root account that must
#      already exist (default: the one that invoked sudo).
#   4. Its busy loop lives in the probe's own cgroup and is killed by reading that
#      cgroup's cgroup.procs — never a bare `pkill`/`killall`.
#   5. Every wait is bounded, so it cannot hang.
#   6. `--dry-run` prints every command it would run and executes none.
set -euo pipefail

UNIT_NAME="c93-cgprobe-$$"
UNIT_FILE="/etc/systemd/system/${UNIT_NAME}.service"
CG="/sys/fs/cgroup/system.slice/${UNIT_NAME}.service"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

say() { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*" >&2; }

# Run a command as root (or execute it directly when already root).
as_root() {
    if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi
}

# Run a command as the service user, dropping privileges if we are root.
run_as_user() {
    if [ "$(id -u)" -eq 0 ]; then runuser -u "$SERVICE_USER" -- "$@"; else sudo -u "$SERVICE_USER" -- "$@"; fi
}

# --- preflight: a real Linux + systemd + cgroup-v2 host, or refuse -------------

[ "$(uname -s)" = Linux ] || { warn "Not Linux; cgroup v2 CPU delegation is Linux-only."; exit 2; }
[ -d /run/systemd/system ] || { warn "No systemd; this probe needs a systemd host."; exit 2; }
[ "$(stat -fc %T /sys/fs/cgroup 2>/dev/null || echo)" = cgroup2fs ] \
    || { warn "Not a cgroup v2 host (stat -fc %T /sys/fs/cgroup != cgroup2fs)."; exit 2; }
if [ "$(id -u)" -ne 0 ]; then
    command -v sudo >/dev/null 2>&1 || { warn "Not root and no sudo; run as root or install sudo."; exit 2; }
fi
if [ "$(id -u)" -eq 0 ]; then
    command -v runuser >/dev/null 2>&1 || { warn "No runuser (util-linux); install it or run via sudo as a user."; exit 2; }
fi

# A non-root service user that already exists. Never create one.
resolve_service_user() {
    if [ -n "${C93_PROBE_USER:-}" ]; then printf '%s' "$C93_PROBE_USER"; return; fi
    if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ]; then printf '%s' "$SUDO_USER"; return; fi
    if id cluster93 >/dev/null 2>&1; then printf 'cluster93'; return; fi
    printf ''
}

# --- cleanup: our unit, our subtree, nothing else -----------------------------

cleanup() {
    [ "$DRY_RUN" = 1 ] && return
    # Kill anything we launched into the probe's own cgroup (a bare pkill would be unsafe).
    if [ -r "$CG/probe/cgroup.procs" ]; then
        while read -r pid; do
            [ -n "$pid" ] && { as_root kill -9 "$pid" 2>/dev/null || true; }
        done < "$CG/probe/cgroup.procs"
    fi
    as_root systemctl stop "$UNIT_NAME" >/dev/null 2>&1 || true
    as_root rm -f "$UNIT_FILE"
    as_root systemctl daemon-reload >/dev/null 2>&1 || true
    say "Cleaned up ${UNIT_NAME}."
}
trap cleanup EXIT INT TERM

# --- plan (shown in every run; executed unless --dry-run) ---------------------

SERVICE_USER="$(resolve_service_user)"
plan_user="${SERVICE_USER:-<non-root-user>}"

cat <<EOF
CPU-delegation probe (D-158)
  host:         $(uname -srm) / systemd $(systemctl --version 2>/dev/null | awk 'NR==1{print $2}')
  service user: ${plan_user}
  unit:         ${UNIT_NAME}  (temporary; removed on exit)
  cgroup base:  ${CG}
  root cpu controller present: $(grep -qw cpu /sys/fs/cgroup/cgroup.controllers && echo yes || echo NO)
EOF

unit_body=$(cat <<EOF
[Unit]
Description=Cluster93 cgroup CPU delegation probe (${UNIT_NAME}; temporary)
[Service]
Type=simple
User=${plan_user}
# Exactly the delegation knob install/systemd/cluster93.service carries.
Delegate=yes
ExecStart=/bin/sleep infinity
EOF
)

if [ "$DRY_RUN" = 1 ]; then
    say ""
    say "--- would write ${UNIT_FILE} ---"
    say "$unit_body"
    say "--- would run ---"
    say "  systemctl daemon-reload && systemctl start ${UNIT_NAME}"
    say "  A2: cat ${CG}/cgroup.controllers            # expect: includes cpu"
    say "  A3: runuser -u ${plan_user} -- sh -c 'echo +cpu > ${CG}/cgroup.subtree_control'"
    say "      runuser -u ${plan_user} -- sh -c 'echo \"50000 100000\" > ${CG}/probe/cpu.max'"
    say "  A4: a busy loop in ${CG}/probe; expect nr_throttled > 0 in ${CG}/probe/cpu.stat"
    say "  cleanup: stop ${UNIT_NAME}; rm ${UNIT_FILE}; daemon-reload"
    exit 0
fi

# --- gating: a usable user, and a free unit name ------------------------------

if [ -z "$SERVICE_USER" ]; then
    warn "No non-root service user to test with."
    warn "Run via sudo as your normal user, or set C93_PROBE_USER=<existing non-root user>."
    warn "(Creating the account is your call: useradd --system --user-group cluster93)"
    exit 2
fi
if [ "$SERVICE_USER" = root ]; then
    warn "The service user is root, which would make the writability check meaningless."
    warn "Set C93_PROBE_USER to a non-root account, or run via sudo as that user."
    exit 2
fi
if [ -f "$UNIT_FILE" ]; then
    warn "Refusing: ${UNIT_FILE} already exists (unexpected for pid $$)."
    exit 2
fi

STEPS=()      # "name|result|detail"
FAILED=0
record() { STEPS+=("$1|$2|$3"); [ "$2" = FAIL ] && FAILED=1 || true; }

# --- A1: the temporary unit ---------------------------------------------------

say ""
say "Writing ${UNIT_FILE}..."
printf '%s\n' "$unit_body" | as_root tee "$UNIT_FILE" >/dev/null
as_root systemctl daemon-reload
as_root systemctl start "$UNIT_NAME"

# --- A2: is cpu delegated into the unit's cgroup? -----------------------------

controllers="$(cat "$CG/cgroup.controllers" 2>/dev/null || echo '<unreadable>')"
if grep -qw cpu <<<"$controllers"; then
    record "A2 delegated" PASS "cgroup.controllers: ${controllers}"
else
    record "A2 delegated" FAIL "cpu absent; controllers: ${controllers}"
fi

# --- A3: can the SERVICE USER enable cpu and write cpu.max under its own cgroup?

as_root mkdir -p "$CG/init" "$CG/probe"
# cgroup v2 forbids enabling a controller for children while a process sits in the same cgroup
# ("no internal processes"); the unit's `sleep` is there, so move it into a leaf first.
while read -r pid; do
    [ -n "$pid" ] && printf '%s\n' "$pid" | as_root tee "$CG/init/cgroup.procs" >/dev/null
done < "$CG/cgroup.procs"

enable_rc=0
run_as_user sh -c "echo +cpu > '$CG/cgroup.subtree_control'" 2>/dev/null || enable_rc=$?
write_rc=0
run_as_user sh -c "echo '50000 100000' > '$CG/probe/cpu.max'" 2>/dev/null || write_rc=$?
quot="$(cat "$CG/probe/cpu.max" 2>/dev/null || echo '<unset>')"
if [ "$enable_rc" -eq 0 ] && [ "$write_rc" -eq 0 ] && [ "$quot" = "50000 100000" ]; then
    record "A3 writable" PASS "service user enabled cpu and wrote cpu.max = ${quot}"
elif [ "$enable_rc" -ne 0 ]; then
    record "A3 writable" FAIL "service user could not enable +cpu (permission denied?) — base path problem, not the controller"
else
    record "A3 writable" FAIL "cpu.max write failed; cpu.max is '${quot}'"
fi

# --- A4: does the quota actually throttle? ------------------------------------

as_root sh -c "echo \$\$ > '$CG/probe/cgroup.procs'; exec bash -c 'while :; do :; done'" &
nr=0
for ((i = 0; i < 20; i++)); do
    nr="$(awk '/^nr_throttled /{print $2}' "$CG/probe/cpu.stat" 2>/dev/null || echo 0)"
    [ "${nr:-0}" -gt 0 ] && break
    sleep 0.5
done
if [ "${nr:-0}" -gt 0 ]; then
    record "A4 throttled" PASS "nr_throttled = ${nr} (quota ${quot} enforced)"
else
    record "A4 throttled" FAIL "nr_throttled stayed 0 — the quota was not enforced"
fi

# --- summary ------------------------------------------------------------------

say ""
say "Result"
printf '  %-13s %-4s %s\n' "STEP" "STATE" "DETAIL"
for step in "${STEPS[@]}"; do
    IFS='|' read -r name state detail <<<"$step"
    printf '  %-13s %-4s %s\n' "$name" "$state" "$detail"
done
say ""
if [ "$FAILED" -eq 0 ]; then
    say "A2-A4 PASS: CPU delegation works under a Delegate=yes unit, and the node should be"
    say "pointed at its OWN delegated cgroup (${CG}) — not /sys/fs/cgroup/cluster93. The same base"
    say "path is what CgroupMemoryLimiter needs, so memory limits may be unapplied today too."
else
    say "A step FAILED. If A2 passed but A3 failed, the fix is the base path (-Dcluster93.cgroup.dir"
    say "pointed at ${CG}), not the controller. Record the unit's cgroup.controllers and the error."
fi
exit "$([ "$FAILED" -eq 0 ] && echo 0 || echo 1)"
