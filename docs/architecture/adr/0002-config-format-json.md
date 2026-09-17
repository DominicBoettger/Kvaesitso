# 0002: JSON (JSONC-tolerant) as the dotfiles config format

Status: accepted (2026-09-17)

## Context

The whole launcher configuration — grid layout, dock favorites, theme reference,
widget settings — must live in a dotfiles repo as one human-editable document
(Omarchy-style), be parsed by the app on hot reload (ADR 0003), and be verifiable.

Candidates:

| Format | For | Against |
|---|---|---|
| **JSON** | kotlinx.serialization is already the app's settings format (`LauncherSettingsData`); zero new dependencies; trivial schema generation | no comments/trailing commas in strict mode |
| YAML | human-friendly | new parser dependency (snakeyaml), whitespace/typing pitfalls, second serialization stack |
| TOML | nice for flat config | nested grid layout becomes awkward; new parser dependency |
| Custom DSL (Hyprland-style) | prettiest to hand-edit | a parser and a language to design, document, and test; disproportionate |

## Decision

**JSON**, with a JSONC-tolerant reader:

```kotlin
Json {
    allowComments = true        // JSONC comments, Omarchy/waybar style
    allowTrailingComma = true
    ignoreUnknownKeys = true    // forward compatibility, both directions
    prettyPrint = true
}
```

(kotlinx-serialization ≥ 1.7 supports `allowComments`; the project pins 1.11.0.)

Additional rules:

- The document carries an explicit `schemaVersion: Int`. Migrations run config-side,
  are pure functions `ConfigVn -> ConfigVn+1`, and are unit-tested.
- A **JSON Schema** is generated from the Kotlin model (or maintained alongside) and
  committed to the dotfiles repo; editors get completion and validation via
  `$schema`.
- The config is a *desired state* document, not a backup archive. It does not adopt
  Kvaesitso's version-pinned backup format — that was the trap identified in the
  exported-surface analysis ("fragile UI automation traded for fragile format
  coupling"). The config schema is the fork's own, deliberately small, public
  contract.

## Consequences

- One serialization stack everywhere: app settings, config file, schema, tests.
- Hand-editing is comfortable (comments, trailing commas, schema-aware editors).
- `ignoreUnknownKeys` lets the dotfiles repo move ahead of the app (and vice versa)
  without hard failures; unknown keys are reported through read-back diagnostics
  instead.
