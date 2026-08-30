# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(assistant/me and anything else tracking `0.2.0-SNAPSHOT`): **additive** (safe to
pick up), **behavioral** (same API, different runtime behavior — read the note),
or **breaking** (consumer change required; the entry links the migration notes
and the consumer PRs that deliver it).

## Unreleased

### Added

- `dice-metamodel` module, first slice: schema versioning — `MetamodelVersion`
  content-hash stamping with per-type governance selection, declared-schema
  opt-in seam, and the `MetamodelVersionStore` contract. Pure JVM.
  **Compatibility: additive.** New module; no existing API touched.
