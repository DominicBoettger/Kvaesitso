# 0007: Fork strategy and upstream relationship

Status: accepted (2026-09-17)

## Context

The single-page widget grid contradicts upstream's product direction
(MM2-0/Kvaesitso#1943, "app icons on desktop", closed *not planned*). The config
hot-reload interface is equally unlikely upstream ("let any app rewrite the
launcher"). A long-lived fork is therefore the right shape; the question is how to
keep it cheap to maintain.

## Decision

### Relationship to upstream

- Fork of `MM2-0/Kvaesitso` at v1.41.0+; `upstream` remote kept for fetching.
- **Divergence is structural, not drifting:** fork code lives in new, clearly-named
  packages/files (`HomeGrid`, `config/`, provider) instead of edits scattered
  through upstream files. Where upstream files must change, changes are minimal and
  marked.
- New home surface **next to** the existing scaffold (ADR 0001), switchable by
  config, so rebases touch wiring, not internals.
- Sync cadence: per upstream release, not per commit. The test pyramid (ADR 0005)
  is the merge safety net.

### What goes back upstream

The existing bugfixes in `~/Development/kvaesitso-patch/` are upstreamable and
should be offered as PRs regardless of the fork — every merged fix shrinks the
fork diff:

- `0001-Fix-crash-when-opening-a-theme-file-from-outside-the.patch`
- `0001-Clock-widget-scale-the-time-down-instead-of-clipping.patch`
- `0001-Dock-stop-a-single-pinned-item-from-filling-the-widt.patch`

The grid, the config system, and the provider are **not** offered upstream.

### Identity

- New applicationId + own signing key (ADR 0006). Display name and branding TBD;
  "Kvaesitso" trademarks/naming courtesy respected (distinct fork name).
- GPL compliance: source published, upstream copyright intact, changes documented.

## Consequences

- Maintenance cost is bounded by upstream's release frequency and how cleanly fork
  code stays out of upstream files — the ADR 0001 "new surface" rule exists
  precisely to protect this.
- If upstream ever ships a grid/config interface of its own, we evaluate adopting
  it; nothing in this design depends on upstream *not* doing so.
