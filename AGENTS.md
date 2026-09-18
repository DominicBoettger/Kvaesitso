# Working agreements for AI agents

This repo is a fork of `MM2-0/Kvaesitso` (see `docs/architecture/adr/0007-fork-strategy.md`).
Read `docs/architecture/README.md` and the ADRs before changing anything.

## Language

**All code and all documentation in English.** Identifiers, comments, commit
messages, docs, ADRs, test names — no exceptions. Chat with the user may be
German; artifacts never are.

## Feedback loop (no LSP)

LSP is deliberately disabled for this project: Kotlin language servers on a
52-module Android/Gradle build give slow, sometimes wrong diagnostics (generated
code, Compose compiler plugin). The feedback loop is Gradle — scoped to the
module being touched, never a full build:

```bash
./gradlew :<module>:compileDebugKotlin        # type errors, fast
./gradlew :<module>:testDebugUnitTest         # L1 unit tests
./gradlew :<module>:connectedDebugAndroidTest # L2 instrumented tests (needs device)
```

Example module paths: `:core:preferences`, `:data:widgets`, `:app:ui`.

## Test policy

This fork is developed AI-assisted, so tests are the safety net, not an
afterthought (see `docs/architecture/adr/0005-testing-strategy.md`):

- New fork code is written **test-first**. Pure logic (grid layout engine, config
  parsing/migration/convergence) lives in headless, unit-testable modules.
- Before touching existing upstream code, write characterization tests that pin
  its current behavior.
- Coverage is measured on fork-touched modules only; untouched upstream code
  staying untested is accepted.
- Definition of done: unit + Compose tests green; for config/provisioning-facing
  features, the L4 scenario in `e2e/` (driven against the provisioning repo's
  emulator harness) updated and green.

## Test harness (Phase 1)

- **L1 unit tests**: JUnit4 + Robolectric 4.17 (SDK 36/37 supported; needs the
  `--add-opens` JVM args already wired in the module build files — copy that
  `tasks.withType<Test>` block when adding tests to another module). Modules
  with test wiring so far: `:core:preferences`, `:services:backup`,
  `:data:database`, `:app:ui`.
- **L3 screenshot tests**: Roborazzi in `:app:ui`; goldens are committed under
  `app/ui/src/test/roborazzi/`.
  - record: `./gradlew :app:ui:recordRoborazziDebug`
  - verify: `./gradlew :app:ui:verifyRoborazziDebug`
- **L2 Compose UI tests**: `androidTest` in `:app:ui`, run on a stock API 36
  emulator (`:app:ui:connectedDebugAndroidTest`).
- **Room migrations**: `:data:database` exports schemas via KSP
  (`schemas/…/<version>.json`); `MigrationTest` validates the full chain and is
  the template for new migrations. When bumping the DB version: write the
  migration, extend `MigrationTest.allMigrations`, run
  `:data:database:kspDebugKotlin` once to export the new schema JSON, commit it.
- **L4**: `e2e/l4-smoke.sh` (and future scenarios) — see the emulator section
  below.

## CI

`.github/workflows/test.yml`: L1 + L3 on every push/PR (JDK 21 — Robolectric
with SDK 36+ requires >= 21), L2 on PRs via `android-emulator-runner` (stock
API 36 image). L4 stays manual/local.

## Fork conventions

- Fork code lives in new, clearly named packages/files — minimal edits to
  upstream files, marked where unavoidable.
- Support matrix: current GrapheneOS on Pixel devices (candybar + Fold, cover and
  inner display). minSdk 36. Old-Android compat code is deleted, not maintained.
- No Play Services dependencies, no telemetry.

## Security

This fork is a **launcher whose primary target is GrapheneOS**, so it is held to
high security standards. A launcher runs with elevated trust on the device
(home-screen role, app launching, widget hosting, potentially provisioning
data), and GrapheneOS users expect software that does not erode the platform's
guarantees. Treat security as a design constraint, not a checklist item:

- **Least privilege**: request no permissions beyond what a feature strictly
  needs; never weaken existing sandboxing, signature checks, or SELinux-related
  behavior to make something work.
- **Data protection**: user data and provisioning/config data must never leak
  — no plain-text secrets, no sensitive data in logs, no world-readable files,
  no unprotected exported components. Treat config/provisioning payloads as
  untrusted input: validate and sanitize everything that is parsed.
- **Attack surface**: keep exported activities/services/providers/receivers
  minimal and explicitly permission-guarded; be conservative with IPC, deep
  links, `WebView` usage, and dynamic code loading.
- **Dependencies**: no new third-party dependencies without clear justification;
  no closed-source blobs, no trackers, no Play Services, no telemetry (see fork
  conventions).
- **When in doubt, ask**: if a change could weaken the security posture —
  even indirectly — flag it explicitly instead of merging it silently.

## Emulator (GrapheneOS, self-built)

The test target is a self-built GrapheneOS emulator (`~/android/grapheneos`,
target `sdk_phone64_x86_64-cur-userdebug`, test-keys), operated via
`~/Development/GrapheneOS/emulator/run.sh`.

- Respect `device-lock.sh` — sessions share devices; never touch a device another
  session holds.
- L4 test runs use a **dedicated instance on port 5556** with its own qcow2
  overlays — never the working instance's `userdata-qemu.img.qcow2`.
- The only honest base state is the `clean` snapshot (near-first-boot);
  `vor-workprofile` is already provisioned and not a base.
- Known emulator limits: nothing Google-server-side can be validated there
  (sandboxed Play, Play Integrity, push); wallpapers apply only after reboot;
  test-keys mean results do not equal "tested on release GrapheneOS".
