# 0003: File-based hot reload with convergence + read-back provider

Status: accepted (2026-09-17)

## Context

Provisioning today drives the launcher UI with uiautomator (`45-launcher-prefs.sh`,
~650 lines of coordinate arithmetic) because the stock app exports no usable
interface: no provider, a five-route deep-link whitelist, a crashing theme importer
(see `~/Development/GrapheneOS/docs/kvaesitso-interfaces.md`). Its failure mode was
never writing — it was **not being able to check**. This fork owns the app, so the
interface problem is solved at the root.

Goal: Omarchy-style workflow — edit a dotfile, push it, launcher reloads live, and
the result is machine-verifiable.

## Decision

Three components:

### 1. Config file location

`<external-files-dir>/config/launcher.json`, i.e.
`/sdcard/Android/data/<applicationId>/files/config/launcher.json`.

- Writable by `adb push` (shell user) — no runtime storage permission, fully
  compatible with GrapheneOS Storage Scopes.
- Per Android user, hence per GrapheneOS profile: each zone/profile in the
  provisioning repo gets its own `launcher.json` pushed with `--user`.
- Watched with `FileObserver` + debounce (~300 ms) so editors that write
  non-atomically do not trigger partial reloads.

### 2. Reload trigger

Primary: explicit broadcast, deterministic for scripts —

```
adb shell am broadcast -a <applicationId>.action.RELOAD_CONFIG [--user N]
```

The file watcher is the convenience path for interactive editing (edit → save →
launcher updates). Both funnel into the same loader.

### 3. Convergence, not application

Reload never "applies" a file. It:

1. parses + migrates the config (ADR 0002),
2. computes a **diff against current state**,
3. writes only the differences into the existing DataStore/repository layer,
4. records diagnostics (unknown keys, invalid entries, skipped items).

UI state therefore converges; re-pushing an unchanged file is a guaranteed no-op.
Compose recomposition over the DataStore makes the reload "hot" — no restart.

### 4. Read-back provider

An exported, **read-only** `ContentProvider` serving the current effective state
as JSON (`content://<applicationId>.state/config`), plus the diagnostics of the
last reload. No permission for v1 (settings are not secrets; revisit if that
changes). This is the verification half of the loop:

```
push launcher.json  ->  broadcast RELOAD  ->  content query  ->  assert equality
```

This is exactly option 2 of the exported-surface proposal, now trivial because we
own the code.

## Consequences

- `45-launcher-prefs.sh` and its entire uiautomator machinery are deleted from the
  provisioning repo once this lands; the launcher step becomes convergent like every
  other step.
- The convergence function (`Config -> Diff -> [Mutation]`) is pure and unit-tested
  against repository fakes; the provider is covered by instrumented tests.
- UI edits and config edits share one model, so "user moved a widget by hand" and
  "dotfiles say otherwise" is a visible, resolvable conflict (config wins on next
  explicit reload; diagnostics surface the drift).
- No network, no service, no telemetry: reload is fully local.
