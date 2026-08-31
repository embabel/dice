# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(anything tracking `0.2.0-SNAPSHOT`): **additive** (safe to
pick up), **behavioral** (same API, different runtime behavior — read the note),
or **breaking** (consumer change required; the entry links the migration notes
and the consumer PRs that deliver it).

## Unreleased

### Added

- `dice-metamodel` module, first slice of schema versioning: `MetamodelVersion`
  content-hash stamping with per-type governance selection, the declared-schema
  opt-in seam, and the `MetamodelVersionStore` contract. Pure JVM.
  **Compatibility: additive.** New module; no existing API touched.

- Declared renames in `dice-metamodel`. **EXPERIMENTAL** (shape may change
  before 1.0): `SchemaAliases`, `PropertySignature.aliases`, and
  `MetamodelVersion.entityTypeAliases`. A declaration states the names a type or
  property used to go by, so a later comparison pairs a rename instead of
  reading it as a removal and an addition.
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

- Schema attribution mechanism: per-proposition version is answered through the
  run that produced the proposition (PRODUCED_BY_RUN). The run record carries
  the declared schema's content hash, resolved by the extraction coordinator from
  the host's DeclaredSchemaSource. The `DiceMetadataKeys.METAMODEL_VERSION`
  metadata key is removed; lineage answers per-proposition attribution.
  **Compatibility: breaking.** The key is no longer available; code holding it
  must migrate to extraction-run queries.
- `DiceMetadataKeys.METAMODEL_VERSION` metadata key and stamping contract.
  Propositions can carry the declared schema version hash under this key to
  record which schema governed their extraction. The key is defined here with
  its contract; production wiring that stamps propositions at persistence time
  lands in a follow-up slice after the extraction-run stack merges.
  **Compatibility: additive.** New metadata key only; no existing API or code
  touched.
- Drivine/Neo4j-backed `MetamodelVersionStore` in `dice-storage`
  (`DrivineMetamodelVersionStore`): stamps persist as `(:MetamodelVersion)` nodes,
  MERGEd on the natural key `(schemaName, contentHash)`, so a re-stamp updates in
  place. `latestVersion`, `versionHistory` and `findVersion` all resolve in Cypher.
  History is ordered by a persisted per-schema sequence, taken off a
  `(:MetamodelSchemaCounter)` node in the same statement that creates the version;
  `savedAt` and `savedAtEpochMillis` are informational, and nothing sorts on them.
  An idempotent re-save leaves the counter and the sequence alone. Concurrent saves
  of one version leave one node. Hosts must declare three uniqueness constraints:
  `MetamodelVersion(schemaName, contentHash)`, `MetamodelSchemaCounter(schemaName)`,
  and `MetamodelVersion(schemaName, sequence)`.
  Declared aliases persist at both levels: the version-level `entityTypeAliases` map
  as its own node property, and a property signature's former names as a fifth
  `aliases` field inside the stored signature. Both are written only when they hold
  something, so an alias-free stamp writes exactly the properties this mapper wrote
  before aliases existed, and a node from that older build reads back as a stamp
  declaring none. Aliases feed `contentHash`, and the mapper recomputes the hash from
  the persisted fields, so a stamp that failed to store them would be unreadable for
  good — pinned by an integration test that writes a row in the old four-field shape
  through raw Cypher and reads it back, one that round-trips a stamp carrying both
  alias kinds, and one that removes the stored alias map and asserts the integrity
  check rejects the row.
  Stamp provenance persists too, and is the one part of a stamp a re-save does not
  overwrite — **EXPERIMENTAL** (shape may change before 1.0): `origin` is
  first-write-wins, set only when the stored row has none, and `lastStamped` moves
  only when the incoming value is non-null. A re-stamp carrying no provenance leaves
  both alone, which is what a scheduled drift check does on every pass, so a routine
  check can neither erase the recorded cause nor replace it with its own identity.
  Both rules are `coalesce` expressions inside the MERGE, so they hold under
  concurrency without a read followed by a write. `savedAt` and `savedAtEpochMillis`
  keep their existing behavior: set on create, untouched by a re-save. `origin` and
  `lastStamped` are not hashed, so neither rule can move a stamp off its natural key.
  Each is stored as a JSON object, which keeps a `StampProvenance()` with both fields
  unset distinguishable from no provenance at all. `StampProvenance`'s 256-character
  cap needs no column sizing here, since a Neo4j string property has no declared
  width; a byte-sized backend still needs room for the up-to-1024 UTF-8 bytes.
  `MetamodelVersionStore.saveVersion`'s KDoc now states both rules as contract, and
  `dice-metamodel` gains `InMemoryMetamodelVersionStore`, the reference
  implementation that applies them, promoted from a private class in that module's
  own tests. `AbstractMetamodelVersionStoreContractTest` runs one suite against both
  stores.
  **Compatibility: additive.** New classes, and a new `dice-storage` → `dice-metamodel`
  module dependency; no existing API touched. Stored nodes stay readable: every
  property that existed before keeps its name, meaning, and encoding, and the four
  new ones are absent when nothing declares them.
