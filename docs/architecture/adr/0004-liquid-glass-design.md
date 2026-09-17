# 0004: Liquid Glass as the single visual direction

Status: accepted (2026-09-17)

## Context

The reference is the iOS home screen: frosted, translucent widget cards with
rounded corners over a blurred wallpaper, and monochrome/tinted icons. The fork
deliberately does **not** maintain alternative visual styles — one polished path
instead of three mediocre ones. (Upstream theming code stays where it is; the fork
just doesn't ship its own second style.)

Constraints on Android:

- A launcher can only blur its **own** wallpaper as backdrop — there is no
  cross-app backdrop blur. That is sufficient here: the grid floats over the
  wallpaper and nothing else.
- Real-time blur needs API 31+ (`RenderEffect`); the fork targets GrapheneOS only,
  so this is always available (see ADR 0006 on minSdk).

## Decision

### Rendering

- Widget cards and the dock are **glass surfaces**: wallpaper backdrop blur
  (RenderEffect / RenderNode, or the `haze` library if it proves stable on
  GrapheneOS) + translucent tint from the Monet zone color + 1 dp inner highlight +
  subtle top-edge specular gradient. Corner radius iOS-like (~28 dp, configurable).
- Blur radius and tint opacity are config values, not settings-screen sliders.
- Specular/refraction effects beyond blur+tint ("real" Liquid Glass) are phase 2,
  implemented as custom AGSL shaders only if the simple stack falls short visually.

### Icons

- iOS "tinted/clear" look: **monochrome glyph on a frosted tile**. Lawnicons (or
  any themed-icon source) provides the glyph; the fork renders it tinted against a
  glass chip — more extreme minimalism than stock icon packs, matching the reference
  screenshot's muted dock.
- Labels: hidden on the dock, shown under grid widgets (as in the reference) —
  config flag.

### Themes

- The existing `.kvtheme` import (fix patch already prepared in
  `kvaesitso-patch/0001-Fix-crash-when-opening-a-theme-file-from-outside-the.patch`)
  stays the transport for colors/typography; the glass parameters live in
  `launcher.json` so one file fully describes a zone's look (ties into the
  provisioning repo's `themes/` + `theming.json` model).

## Consequences

- Visual regression risk is high (blur is GPU/compose-version sensitive) →
  screenshot tests with a fixed test wallpaper are part of the test strategy
  (ADR 0005).
- Performance on the Pixel Fold must be measured: one blurred backdrop cached per
  wallpaper change, not per frame.
- Accessibility: glass tint must keep contrast within WCAG-ish bounds; a config
  `contrast: low|medium|high` scales blur/tint, not a separate theme.
