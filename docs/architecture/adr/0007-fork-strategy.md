# 0007: Fork strategy and upstream relationship

Status: accepted (2026-09-17), **revised 2026-09-19: hard fork**

## Context

The single-page widget grid contradicts upstream's product direction
(MM2-0/Kvaesitso#1943, "app icons on desktop", closed *not planned*). The config
hot-reload interface is equally unlikely upstream ("let any app rewrite the
launcher"). A long-lived fork is therefore the right shape.

The first version of this ADR tried to keep the fork cheap by staying mergeable:
fork code in new packages, minimal marked edits to upstream files, a merge per
upstream release. After Phase 2 (issue #2) that premise no longer holds. The next
steps contradict it on every axis: the identity rename touches every module
(ADR 0006), minSdk 36 deletes `core/compat` and the pre-36 branches it guarded,
and modules that cost memory, CPU or attack surface without serving the andashi
profile model are to be removed (plugin system, Nextcloud plugin, backup/restore,
unused search providers). Keeping upstream merges possible would make each of
these harder for a benefit we would rarely collect: the parts of upstream we care
about (search providers, weather, database, Android version adaptations) change
slowly and can be taken by hand.

## Decision

**Andashi Home is a hard fork of Kvaesitso.** Upstream is a source, not a
constraint.

### Relationship to upstream

- Forked from `MM2-0/Kvaesitso` at v1.41.0. The `upstream` remote stays for
  reading; the local mirror branch is `upstream-main`.
- **No merges from upstream.** Upstream changes are taken by deliberate
  cherry-pick when they are worth it, reviewed like any other change, and only in
  areas the fork still shares (providers, weather, database, platform
  adaptations). `app/ui` and the cross-cutting concerns are fork-owned; upstream
  changes there are read as inspiration only.
- **Everything may change.** Modules may be deleted, Kotlin packages renamed,
  compat code removed, upstream files edited without marking. The earlier rule
  "fork code in new packages, minimal edits to upstream files" is withdrawn.
  Structure is still chosen for the fork's own maintainability, not for merge
  friendliness.
- The test pyramid (ADR 0005) remains the safety net for every change,
  including cherry-picks.

### What goes back upstream

The three verified bugfixes in `~/Development/kvaesitso-patch/` are still
offered to upstream as a courtesy and applied to the fork directly (issue #3):

- `0001-Fix-crash-when-opening-a-theme-file-from-outside-the.patch`
- `0001-Clock-widget-scale-the-time-down-instead-of-clipping.patch`
- `0001-Dock-stop-a-single-pinned-item-from-filling-the-widt.patch`

The grid, the config system, the providers and everything that follows from the
hard fork are **not** offered upstream.

### Identity

- Organization **andashi** (andashi.org), repository `github.com/andashi/home`,
  default branch `main`.
- Product name **Andashi Home**, applicationId `org.andashi.home` (debug:
  `org.andashi.home.debug`), own signing key held by the organization (ADR 0006,
  issues #19 and #21). "Kvaesitso" naming courtesy respected: distinct name, no
  claim to upstream's identity.
- GPL compliance: source published, upstream copyright intact, changes documented
  in git history and ADRs.

## Consequences

- The fork owns its whole codebase, including Compose, dependency and Android
  updates in `app/ui`. That is the price; it is paid knowingly, for a
  single-maintainer project with a narrow support matrix (current GrapheneOS on
  Pixel devices, ADR 0006).
- Maintenance cost is now bounded by what the fork keeps, not by upstream's
  release cadence. Removing unused modules (issue #20) lowers it directly.
- Cherry-picks get harder as the code diverges; that is accepted. If upstream
  ships something the fork wants and the pick no longer applies, it is
  reimplemented.
- ADR 0001's "new surface next to the existing scaffold" no longer serves merge
  protection; it stays only where it is the better design.
