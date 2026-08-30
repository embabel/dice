# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(assistant/me and anything else tracking `0.2.0-SNAPSHOT`): **additive** (safe to
pick up), **behavioral** (same API, different runtime behavior — read the note),
or **breaking** (consumer change required; the entry links the migration notes
and the consumer PRs that deliver it).

## Unreleased

### Added

- `dice-metamodel` module: schema governance contracts — `MetamodelVersion`
  content-hash stamping, declared-vs-observed schema diffing, drift reports, and
  proposition quarantine policy. Pure JVM, no Spring or database dependencies.
  See [docs/design/metamodel-governance.md](docs/design/metamodel-governance.md).
  **Compatibility: additive.** New module; no existing API touched.
