# dice-metamodel

Tracks how a DICE knowledge graph schema changes over time and protects stored propositions when
it does. There are two related but distinct comparisons here: **declared vs. declared**
(`MetamodelDiffer`) — comparing two schema versions you decided on — and **declared vs. observed**
(`DeclaredObservedDiffer`, `DriftCheckRunner`) — comparing what you declared against what a live
graph actually contains right now. Both feed the same quarantine machinery. See
[metamodel-governance.md](../docs/design/metamodel-governance.md) for the full design write-up,
including diagrams and Kotlin samples.

## Key types

| Type | Where | What it does |
|---|---|---|
| `MetamodelVersion` | `MetamodelVersion.kt` | Immutable stamp of a `DataDictionary` at a point in time. Holds entity-type names, label sets, property sets, and relationship names. The `contentHash` is a SHA-256 digest of all structural content (schema name excluded), so two structurally identical schemas produce the same hash regardless of name. Use `MetamodelVersion.from(dataDictionary)` to create one. |
| `MetamodelChange` | `MetamodelDiff.kt` | Sealed interface with five variants: `EntityTypeAdded`, `EntityTypeRemoved`, `EntityTypeModified` (labels and/or properties changed on a same-named type), `RelationshipAdded`, `RelationshipRemoved`. Exhaustive `when` expressions work on it. |
| `MetamodelDiff` | `MetamodelDiff.kt` | The result of comparing two versions. Carries the ordered `changes` list plus convenience accessors `removedEntityTypes`, `addedEntityTypes`, and `modifiedEntityTypes`. `isEmpty` is `true` when nothing changed. |
| `MetamodelDiffer` | `MetamodelDiffer.kt` | Interface with two `diff()` overloads — one takes `MetamodelVersion` stamps, the other takes raw `DataDictionary` instances for convenience. Default impl is `StructuralMetamodelDiffer` (in `support/`). |
| `ObservedSchema` / `ObservedSchemaSource` | `ObservedSchema.kt`, `ObservedSchemaSource.kt` | A snapshot of what a live graph actually contains (`entityTypeNames`, `relationshipTypeNames`, `capturedAt`), and the `fun interface` that produces one. No graph driver dependency here — `DrivineObservedSchemaSource` (`dice-storage-autoconfigure`) is the real implementation. |
| `DeclaredSchema` / `DeclaredSchemaSource` | `DeclaredSchemaSource.kt` | The declared schema (a stamped `MetamodelVersion` plus its bare relationship type names) and the `fun interface` that supplies one. No default implementation — a consuming app maps whatever it already uses to define its schema. |
| `DeclaredObservedDiffer` | `MetamodelDiffer.kt` | Compares a `DeclaredSchema` against an `ObservedSchema` and returns a `DeclaredObservedDiff`, distinguishing drift (observed, never declared) from merely-unobserved (declared, zero instances). Default impl is `StructuralMetamodelDiffer`, which implements both differ interfaces. |
| `DriftReport` / `MetamodelStore` | `MetamodelStore.kt` | `DriftReport` is one drift observation (`schemaName`, `versionHash`, drifting entity/relationship types, `capturedAt`). `MetamodelStore` is the durable, append-only log for versions and reports — no deletion, no updates. Default impl is `DrivineMetamodelStore` (`dice-storage`), which MERGEs on natural keys. |
| `DriftCheckRunner` | `DriftCheckRunner.kt` | Snapshots the live graph, diffs it against the declared schema, always persists a `DriftReport` (even a clean one), and — on a live, non-dry run with entity-type drift — quarantines affected propositions. Default impl `DefaultDriftCheckRunner`. No scheduler of its own; a consumer calls `run(dryRun)`. |
| `DriftQuarantinePolicy` | `DriftQuarantinePolicy.kt` | Interface that evaluates a collection of propositions against a `MetamodelDiff` and returns a `QuarantineResult`. Call `evaluate(diff, propositions)`. Also what `DriftCheckRunner` delegates to, via a synthesized `MetamodelDiff` of `EntityTypeRemoved` entries for drifted types. |
| `QuarantineDecision` | `DriftQuarantinePolicy.kt` | Sealed interface: `Conforming` (no action needed) or `Quarantined` (proposition set to `STALE`, reason written to `DiceMetadataKeys.QUARANTINE_REASON`). |
| `QuarantineResult` | `DriftQuarantinePolicy.kt` | Aggregate of one `QuarantineDecision` per input proposition, split into `conforming` and `quarantined` lists. Caller is responsible for persisting the returned copies. |
| `StructuralMetamodelDiffer` | `support/StructuralMetamodelDiffer.kt` | Default `MetamodelDiffer` *and* `DeclaredObservedDiffer`. Deterministic structural comparison: diffs entity-type, label, property, and relationship sets directly (no delimiter-joined projection). Stateless and thread-safe. |
| `MentionTypeDriftQuarantinePolicy` | `support/MentionTypeDriftQuarantinePolicy.kt` | Default `DriftQuarantinePolicy`. Quarantines a proposition when any of its entity mentions references a type that was removed or that lost labels/properties (lossy changes). Additive changes never trigger quarantine. Already-quarantined propositions are passed through unchanged (idempotent). |
| `MetamodelConfiguration` | `MetamodelConfiguration.kt` | Spring `@Configuration` that registers `StructuralMetamodelDiffer` and `MentionTypeDriftQuarantinePolicy` as `@ConditionalOnMissingBean` beans. Import it to get both without wiring by hand; define your own beans to override either one. This module has no bean for `DriftCheckRunner` itself — that wiring, plus `ObservedSchemaSource`/`MetamodelStore` defaults, lives in `dice-storage-autoconfigure`'s `MetamodelAutoConfiguration`, gated by `embabel.dice.metamodel.enabled` (off by default). |

## Dependencies

- **`dice` (core)** — required; provides `Proposition`, `PropositionStatus`, `DiceMetadataKeys`, and
  `DataDictionary` (via `embabel-agent-api`).
- **`embabel-agent-api`** and **`embabel-agent-rag-core`** — `provided`; supplied by the consuming
  Spring Boot application, not pulled transitively.
- **Spring Context / Boot Autoconfigure** — `provided`; only needed if you use `MetamodelConfiguration`.
- **`dice-storage-autoconfigure`** wires the drift-check runner itself (`DriftCheckRunner`,
  `ObservedSchemaSource` defaulting to `DrivineObservedSchemaSource`, `MetamodelStore` defaulting to
  `DrivineMetamodelStore`) — this module only defines the SPI and the declared-vs-declared /
  declared-vs-observed comparison logic, not the drift-check wiring.

## Quarantine workflow

```kotlin
val diff = differ.diff(oldSchema, newSchema)
if (!diff.isEmpty) {
    val result = policy.evaluate(diff, repository.findAll())
    result.quarantined.forEach { repository.save(it.proposition) }
}
```

Quarantine is non-destructive: propositions come back as immutable copies with updated status and
metadata. Nothing is deleted.

## Gotchas

- **Schema name excluded from hash.** Two schemas with identical structure but different names
  produce the same `contentHash`. This is intentional (dev vs prod environment parity), but it
  means you cannot use `contentHash` to distinguish schemas by name.
- **`EntityTypeModified` only fires for common types.** If a type is removed and re-added with the
  same name in a single diff, it appears as `EntityTypeRemoved` + `EntityTypeAdded`, not `EntityTypeModified`.
- **Idempotency contract.** A proposition already in `STALE` status with a `QUARANTINE_REASON`
  metadata entry is placed in the `conforming` group unchanged — its original reason is preserved.
  To force re-evaluation, clear `QUARANTINE_REASON` from its metadata before passing it in.
- **Label and property names can contain any character.** The `contentHash` uses length-prefixed
  encoding (`<len>:<token>`) when building the fingerprint, so names with spaces, semicolons, or any
  delimiter are safe and unambiguous.
- **No Spring auto-configuration.** `MetamodelConfiguration` is not auto-registered. You must
  `@Import(MetamodelConfiguration::class)` or declare it in your application context explicitly.
