# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(assistant/me and anything else tracking `0.2.0-SNAPSHOT`): **additive** (safe to
pick up), **behavioral** (same API, different runtime behavior — read the note),
or **breaking** (consumer change required; the entry links the migration notes
and the consumer PRs that deliver it).

## Unreleased

### Added

- `dice-metamodel` module, first slice of schema versioning: `MetamodelVersion`
  content-hash stamping with per-type governance selection, the declared-schema
  opt-in seam, and the `MetamodelVersionStore` contract. Pure JVM.
  **Compatibility: additive.** New module; no existing API touched.

- Declared renames and stamp provenance in `dice-metamodel`. **EXPERIMENTAL**
  (shape may change before 1.0): `SchemaAliases`, `StampProvenance`,
  `PropertySignature.aliases`, `MetamodelVersion.entityTypeAliases`, and
  `MetamodelVersion.origin`/`lastStamped`. A declaration states the names a type
  or property used to go by, so a later comparison pairs a rename instead of
  reading it as a removal and an addition; provenance records who caused the
  first and the most recent stamp, and is never hashed.
  **Compatibility: additive.** `contentHash` is unchanged for any schema that
  declares no aliases — the new hash blocks serialize only when non-empty, and
  the pinned golden digest is asserted unchanged, including for a stamp rebuilt
  through the public constructor. The new constructor parameters carry
  `@JvmOverloads`, so the existing `PropertySignature(String, Kind, String,
  Cardinality)` and `MetamodelVersion(String, List, Map, Map, List)` descriptors
  survive. `SchemaAliases` reaches `MetamodelVersion.from` and
  `DeclaredSchema.from` through separate overloads that take it as a required
  parameter, which leaves the shipped one- and two-argument forms and their
  Kotlin `from$default` synthetics byte-identical; widening those functions with
  a third defaulted parameter would have replaced
  `DeclaredSchema.Companion.from$default(Companion, DataDictionary,
  GovernedTypeSelector, int, Object)` with a wider descriptor and broken any
  caller already compiled against it. `MetamodelJavaCompatTest` calls every
  static form. The changed Kotlin synthetic constructor, `copy`, `copy$default`
  and `componentN` signatures on `PropertySignature` are the accepted boundary:
  Kotlin callers recompile, and no consumer holds a compiled reference to them.
  `StampProvenance` caps `actor` and `trigger` at 256 **characters**
  (`String.length`), so a storage backend sizing a column in bytes needs room
  for the up-to-1024 UTF-8 bytes those characters can take.
