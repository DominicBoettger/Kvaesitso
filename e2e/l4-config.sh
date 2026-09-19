#!/usr/bin/env bash
# L4 config test: validates the Phase-2 dotfiles config system (ADR 0003)
# end to end on the GrapheneOS emulator, against the same harness as
# e2e/l4-smoke.sh (ADR 0005).
#
#   e2e/l4-config.sh [path/to/kvaesitso.apk]
#
# What it does:
#   1. acquires the device lock (as "l4-config"), boots the dedicated test
#      instance (emulator-5556, own qcow2 overlays under
#      <gos-repo>/emulator/instances/test) from the `clean` snapshot,
#      installs the debug APK
#   2. pushes a known JSONC config (icons, transparency, search bar, dock,
#      widgets, clock; empty favorites so no installed-package assumptions)
#   3. proves the explicit, non-exported ReloadConfigReceiver is reachable
#      from the shell: the watcher startup-check is allowed to settle first
#      (trigger "startup-check"), then the broadcast must produce a report
#      with trigger "broadcast" for the same config hash
#   4. reads back content://<pkg>.state/config and asserts the effective
#      fields with jq
#   5. re-pushes the unchanged config and asserts the follow-up broadcast
#      report is successful with no applied mutations (watcher settled first)
#   6. pushes malformed JSON and asserts a failed report with a
#      "malformed-json" error diagnostic, and that the previous effective
#      config remains intact
#   7. pushes unknown keys in an otherwise valid config and asserts warning
#      diagnostics with a successful apply
#   8. restores the valid config
#
# Report correlation: ReloadReport has no id/timestamp, so a new reload is
# detected via trigger and/or configSha256 transitions. Before every explicit
# broadcast the script waits for the file-watcher report of the push (trigger
# "file-watcher"), which both settles the watcher and validates it.
#
# TODO(per-user isolation, scenario requirement 9): NOT covered here yet.
# Sketch for a future extension, pending live validation on the emulator:
#   - `pm create-user` + `am switch-user` on a running instance is known to
#     be fragile headless (lock screen, user switch races with boot)
#   - install the APK for the new user (`adb install --user <id> -r`),
#     push a *different* config to
#     /storage/emulated/<id>/Android/data/<pkg>/files/config/launcher.json,
#     broadcast with `--user <id>`, and assert via
#     `content query --user <id>` that /config differs per user while user 0
#     stays untouched
#   - the provider is per-user (android:exported without permission, resolved
#     in the calling user's package instance), so read-back isolation should
#     hold by construction — but that is exactly what a test must prove,
#     not assume
#
# The emulator harness (run.sh, device-lock.sh, snapshots, overlays) lives in
# the provisioning repo — see docs/architecture/adr/0005-testing-strategy.md.
# The default APK path assumes a prior
#   ./gradlew :app:app:assembleDefaultDebug
set -euo pipefail

GOS_REPO="${GOS_REPO:-$HOME/Development/GrapheneOS}"
SERIAL="emulator-5556"
export SERIAL
export OVERLAY_DIR="$GOS_REPO/emulator/instances/test"
# Second instance alongside the working one requires -read-only (see run.sh).
# All writes are discarded on exit; the run starts from the `clean` snapshot.
export READ_ONLY=1
SNAPSHOT="${SNAPSHOT:-clean}"
APK="${1:-$(dirname "$0")/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="de.mm20.launcher2.debug"
RECEIVER="$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver"
ACTION="$PKG.action.RELOAD_CONFIG"
STATE_URI="content://$PKG.state"
REMOTE_DIR="/storage/emulated/0/Android/data/$PKG/files/config"
REMOTE_CONFIG="$REMOTE_DIR/launcher.json"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK (build it or pass a path)"
command -v jq >/dev/null || die "jq not found (required for config assertions)"

WORK="$(mktemp -d)"

cleanup() {
  (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
  (cd "$GOS_REPO" && emulator/device-lock.sh release l4-config) >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# --- adb helpers -------------------------------------------------------

# Prints the `json` column of the single row returned by the state provider.
# The payload is pretty-printed (multi-line) JSON, so everything after the
# "Row: 0 json=" prefix is the value. No grep -q anywhere in this script:
# under pipefail, -q exits after the first match and the resulting SIGPIPE
# makes the producer side of the pipeline fail despite the match.
query_json() { # $1 = provider path (config|diagnostics)
  local out
  out="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/$1" 2>&1 | tr -d '\r')" \
    || { printf 'content query failed: %s\n' "$out" >&2; return 1; }
  case "$out" in
    "Row: 0 json="*) printf '%s' "${out#Row: 0 json=}" ;;
    *) printf 'unexpected provider output: %s\n' "$out" >&2; return 1 ;;
  esac
}

# Polls /diagnostics until the latest report matches the jq filter.
# Sets LAST_REPORT on success; fails loudly and prints the last seen report
# on timeout.
LAST_REPORT=""
wait_report() { # $1 = jq filter, $2 = timeout (s), $3 = description
  local filter="$1" timeout="$2" what="$3" elapsed=0 report=""
  while [ "$elapsed" -lt "$timeout" ]; do
    if report="$(query_json diagnostics 2>/dev/null)" && [ -n "$report" ]; then
      if jq -e "$filter" >/dev/null 2>&1 <<<"$report"; then
        LAST_REPORT="$report"
        return 0
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf 'last /diagnostics report:\n%s\n' "$report" >&2
  die "timed out (${timeout}s) waiting for report: $what"
}

assert_jq() { # $1 = json, $2 = jq filter, $3 = description
  if ! jq -e "$2" >/dev/null 2>&1 <<<"$1"; then
    printf 'offending json:\n%s\n' "$1" >&2
    die "assertion failed: $3"
  fi
}

push_config() { # $1 = local file
  adb -s "$SERIAL" shell "mkdir -p '$REMOTE_DIR'" >/dev/null
  adb -s "$SERIAL" push "$1" "$REMOTE_CONFIG" >/dev/null
}

reload_broadcast() {
  local out
  out="$(adb -s "$SERIAL" shell am broadcast -n "$RECEIVER" -a "$ACTION" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "am broadcast failed"; }
  case "$out" in
    *"Broadcast completed"*) ;;
    *) printf '%s\n' "$out" >&2; die "am broadcast did not complete" ;;
  esac
}

# Pushes a config, waits for the file-watcher to settle (validates the
# watcher and avoids watcher/broadcast report races), then sends the explicit
# broadcast and waits for the resulting report.
settle_then_broadcast() { # $1 = local config file, $2 = sha256, $3 = stage name
  push_config "$1"
  log "$3: waiting for file-watcher reload (hash ${2:0:12}...)"
  wait_report ".configSha256 == \"$2\" and .trigger == \"file-watcher\"" 30 \
    "$3: file-watcher report"
  log "$3: broadcasting explicit reload"
  reload_broadcast
  wait_report ".configSha256 == \"$2\" and .trigger == \"broadcast\"" 30 \
    "$3: broadcast report"
}

# --- fixtures ----------------------------------------------------------

# JSONC on purpose (comments + trailing commas): ConfigParser must accept
# both. Empty dock favorites: the fixture must not assume any specific
# packages are installed.
VALID_CONFIG="$WORK/valid.jsonc"
cat > "$VALID_CONFIG" <<'EOF'
{
  // L4 test fixture: covers every config section.
  "schemaVersion": 1,
  "icons": {
    "themed": true,
    "enforceThemed": true,
  },
  "appearance": {
    "transparency": {
      "background": 0.5,
      "surface": 0.7,
      "elevatedSurface": 0.9,
    },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": {
      "enabled": true,
      // Empty on purpose: favorites reference installed packages.
      "favorites": [],
    },
    "widgets": { "enabled": true, "widgets": ["weather", "music"] },
    "clock": { "style": "analog", "fillHeight": true },
  },
}
EOF

UNKNOWN_KEYS_CONFIG="$WORK/unknown-keys.jsonc"
cat > "$UNKNOWN_KEYS_CONFIG" <<'EOF'
{
  "schemaVersion": 1,
  "futureTopLevelKey": { "anything": 1 },
  "icons": {
    "themed": true,
    "enforceThemed": true,
    "futureIconsKey": "ignored",
  },
  "appearance": {
    "transparency": {
      "background": 0.5,
      "surface": 0.7,
      "elevatedSurface": 0.9,
    },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": {
      "enabled": true,
      "favorites": [],
      "futureDockKey": true,
    },
    "widgets": { "enabled": true, "widgets": ["weather", "music"] },
    "clock": { "style": "analog", "fillHeight": true },
  },
}
EOF

MALFORMED_CONFIG="$WORK/malformed.jsonc"
printf '{ "schemaVersion": 1, "icons": { not json at all\n' > "$MALFORMED_CONFIG"

H_VALID="$(sha256sum "$VALID_CONFIG" | cut -d' ' -f1)"
H_UNKNOWN="$(sha256sum "$UNKNOWN_KEYS_CONFIG" | cut -d' ' -f1)"
H_MALFORMED="$(sha256sum "$MALFORMED_CONFIG" | cut -d' ' -f1)"

# The /config read-back is fully populated (ConfigStateMapper), so these are
# the exact effective values after applying VALID_CONFIG.
EFFECTIVE_FILTER='
  .schemaVersion == 1
  and .icons.themed == true
  and .icons.enforceThemed == true
  and .appearance.transparency.background == 0.5
  and .appearance.transparency.surface == 0.7
  and .appearance.transparency.elevatedSurface == 0.9
  and .home.searchBar.position == "bottom"
  and .home.dock.enabled == true
  and .home.dock.favorites == []
  and .home.widgets.enabled == true
  and (.home.widgets.widgets | sort) == ["music", "weather"]
  and .home.clock.style == "analog"
  and .home.clock.fillHeight == true
'

# --- 1. boot + install -------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire l4-config)

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)

log "installing $(basename "$APK")"
install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
case "$install_out" in
  *Success*) ;;
  *) printf '%s\n' "$install_out" >&2; die "adb install failed" ;;
esac

adb -s "$SERIAL" shell pm list packages | tr -d '\r' | grep -x "package:$PKG" >/dev/null \
  || die "$PKG not installed"
ok "package installed: $PKG"

# --- 2./3. push known config, prove the explicit broadcast works -------

push_config "$VALID_CONFIG"

# The first provider query starts the app process (the provider is exported);
# the watcher's startup drift check then reloads on its own. Letting it settle
# first makes the subsequent broadcast unambiguous: trigger must flip from
# "startup-check" to "broadcast", which only the explicit receiver can cause.
# Generous timeout: cold process start plus Koin on the emulator.
log "waiting for watcher startup-check to settle (starts the app process)"
wait_report ".success == true and .configSha256 == \"$H_VALID\" and .trigger == \"startup-check\"" 90 \
  "startup-check report for valid config"
ok "startup-check applied the pushed config"

log "broadcasting explicit reload to non-exported receiver"
reload_broadcast
wait_report ".success == true and .configSha256 == \"$H_VALID\" and .trigger == \"broadcast\"" 30 \
  "broadcast report for valid config"
ok "explicit broadcast reached the non-exported receiver (trigger=broadcast)"

# --- 4. assert the effective config ------------------------------------

log "asserting effective config via $STATE_URI/config"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config matches pushed fixture"
ok "effective config matches (icons, transparency, search bar, dock, widgets, clock)"

# --- 5. re-push unchanged config: no mutations --------------------------

settle_then_broadcast "$VALID_CONFIG" "$H_VALID" "re-push"
assert_jq "$LAST_REPORT" \
  '.success == true and ((.appliedMutations // []) == [])' \
  "re-push of unchanged config yields success with no applied mutations"
ok "unchanged re-push: successful, no applied mutations"

# --- 6. malformed JSON: failed report, state intact --------------------

settle_then_broadcast "$MALFORMED_CONFIG" "$H_MALFORMED" "malformed"
assert_jq "$LAST_REPORT" \
  '.success == false and ([.diagnostics[] | select(.severity == "error" and .code == "malformed-json")] | length > 0)' \
  "malformed config yields a failed report with a malformed-json error"
ok "malformed config rejected with malformed-json diagnostic"

effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config intact after malformed push"
ok "previous effective config intact"

# --- 7. unknown keys: warnings, successful apply -----------------------

settle_then_broadcast "$UNKNOWN_KEYS_CONFIG" "$H_UNKNOWN" "unknown-keys"
assert_jq "$LAST_REPORT" \
  '.success == true and ([.diagnostics[] | select(.severity == "warning" and .code == "unknown-key")] | length >= 3)' \
  "unknown keys yield warning diagnostics and a successful apply"
ok "unknown keys: warnings recorded, apply successful"

effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config unchanged by unknown keys"
ok "effective config unchanged (unknown keys ignored)"

# --- 8. restore a valid config -----------------------------------------

settle_then_broadcast "$VALID_CONFIG" "$H_VALID" "restore"
assert_jq "$LAST_REPORT" '.success == true' "restore of valid config succeeds"
ok "valid config restored"

ok "L4 config passed"
