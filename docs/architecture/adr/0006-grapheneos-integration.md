# 0006: GrapheneOS-specific integration and constraints

Status: accepted (2026-09-17)

## Context

The launcher runs exclusively on GrapheneOS (Pixel 10 Pro Fold) inside a
multi-profile "network zones" model, provisioned by `~/Development/GrapheneOS`.
That removes generic-Android constraints and adds a few specific ones.

## Decision

### Constraints we adopt deliberately

- **No Play Services, no telemetry, no network permission requirement for config.**
  Stock Kvaesitso already qualifies; the fork must not regress (watch upstream
  merges for new dependencies).
- **Storage-Scopes friendly:** config lives in the app-specific external files dir
  (ADR 0003); the launcher never requests broad storage access.
- **minSdk raised to the current Android release (36)**, matching `targetSdk`;
  `compileSdk` already tracks the newest SDK (37). GrapheneOS ships only current
  Android, so all pre-36 compat code (`core/compat` shims, version checks, legacy
  fallbacks) becomes deletable over time instead of being maintained. Older-Android
  support is explicitly dropped, not just deprioritized.
- **Own signing key, pinned in the provisioning repo** (`apks/SHA256SUMS` flow).
  Reproducible-ish builds: pinned toolchain, version catalog, build log archived
  (as `kvaesitso-patch/build-*.log` already does).
- **New applicationId.** Coexisting with/upgrading over upstream installs is not a
  goal; provisioning creates fresh profiles anyway. Side effect: AppWidget host
  bindings and favorites do not migrate from a stock install — accepted, documented
  in the setup docs. (Package name stays a single rename, done once, early.)

### Features we build because GrapheneOS enables them

- **Per-profile config as first-class design:** external files dirs are per-user;
  the provisioning repo maps zones → profiles → one `launcher.json` each. The
  read-back provider (ADR 0003) makes per-profile verification trivial.
  `LauncherActivity` already handles `android.os.UserHandle` via `core/profiles`;
  the grid respects work-profile apps through the existing profile infrastructure.
- **Private Space:** treated as just another profile for config purposes in v1;
  explicit lock/unlock integration (launcher APIs) is a later enhancement, tracked
  separately.
- **Verified boot / hardened_malloc:** no native code added by the fork, so the
  hardened memory allocator story stays upstream's.

### What we explicitly do not do

- No privileged/system-app integration, no sharedUserId, no requests for
  permissions beyond what stock Kvaesitso uses (notification listener, accessibility
  for global actions, contacts/calendar for search — all opt-in as upstream).
- No attempt to script profile PINs, Play installs, or Storage/Contact Scopes —
  they stay manual per the provisioning repo's documented split.

## Consequences

- The fork's support matrix is: **current GrapheneOS on Pixel devices — candybar
  phones and the Pixel Fold** (cover + inner display). Form-factor differences are
  in scope (see ADR 0001 for per-form-factor grids); OS diversity is not. Bugs
  that only reproduce on other OSes are out of scope.
- Release engineering (sign, hash, pin, changelog) is part of "done" for every
  fork release, because provisioning consumes pinned APKs.
