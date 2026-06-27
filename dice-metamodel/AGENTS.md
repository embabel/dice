# dice-metamodel

Tracks how a DICE knowledge graph schema changes over time and protects stored propositions when
it does. The module does three things: stamps a `DataDictionary` as an immutable `MetamodelVersion`,
diffs two versions to produce a `MetamodelDiff`, and decides which propositions to quarantine when
the diff is lossy.

## Key types

| Type | Where | What it does |
|---|---|---|
| `MetamodelVersion` | `MetamodelVersion.kt` | Immutable stamp of a `DataDictionary` at a point in time. Holds entity-type names, label sets, property sets, and relationship names. The `contentHash` is a SHA-256 digest of all structural content (schema name excluded), so two structurally identical schemas produce the same hash regardless of name. Use `MetamodelVersion.from(dataDictionary)` to create one. |
| `MetamodelChange` | `MetamodelDiff.kt` | Sealed interface with five variants: `EntityTypeAdded`, `EntityTypeRemoved`, `EntityTypeModified` (labels and/or properties changed on a same-named type), `RelationshipAdded`, `RelationshipRemoved`. Exhaustive `when` expressions work on it. |
| `MetamodelDiff` | `MetamodelDiff.kt` | The result of comparing two versions. Carries the ordered `changes` list plus convenience accessors `removedEntityTypes`, `addedEntityTypes`, and `modifiedEntityTypes`. `isEmpty` is `true` when nothing changed. |
| `MetamodelDiffer` | `MetamodelDiffer.kt` | Interface with two `diff()` overloads — one takes `MetamodelVersion` stamps, the other takes raw `DataDictionary` instances for convenience. Default impl is `JaversMetamodelDiffer` (in `support/`). |
| `DriftQuarantinePolicy` | `DriftQuarantinePolicy.kt` | Interface that evaluates a collection of propositions against a `MetamodelDiff` and returns a `QuarantineResult`. Call `evaluate(diff, propositions)`. |
| `QuarantineDecision` | `DriftQuarantinePolicy.kt` | Sealed interface: `Conforming` (no action needed) or `Quarantined` (proposition set to `STALE`, reason written to `DiceMetadataKeys.QUARANTINE_REASON`). |
| `QuarantineResult` | `DriftQuarantinePolicy.kt` | Aggregate of one `QuarantineDecision` per input proposition, split into `conforming` and `quarantined` lists. Caller is responsible for persisting the returned copies. |
| `JaversMetamodelDiffer` | `support/JaversMetamodelDiffer.kt` | Default `MetamodelDiffer`. Uses direct sorted-string comparison for label/property drift rather than full JaVers object-graph diff; JaVers is on the classpath for future richer diffing. Stateless and thread-safe. |
| `MentionTypeDriftQuarantinePolicy` | `support/MentionTypeDriftQuarantinePolicy.kt` | Default `DriftQuarantinePolicy`. Quarantines a proposition when any of its entity mentions references a type that was removed or that lost labels/properties (lossy changes). Additive changes never trigger quarantine. Already-quarantined propositions are passed through unchanged (idempotent). |
| `MetamodelConfiguration` | `MetamodelConfiguration.kt` | Spring `@Configuration` that registers `JaversMetamodelDiffer` and `MentionTypeDriftQuarantinePolicy` as `@ConditionalOnMissingBean` beans. Import it to get both without wiring by hand; define your own beans to override either one. |

## Dependencies

- **`dice` (core)** — required; provides `Proposition`, `PropositionStatus`, `DiceMetadataKeys`, and
  `DataDictionary` (via `embabel-agent-api`).
- **`embabel-agent-api`** and **`embabel-agent-rag-core`** — `provided`; supplied by the consuming
  Spring Boot application, not pulled transitively.
- **JaVers 7.9.0** — bundled; used by `JaversMetamodelDiffer`.
- **Spring Context / Boot Autoconfigure** — `provided`; only needed if you use `MetamodelConfiguration`.

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
- **Label names must not contain spaces.** `TypeShapeSnapshot` uses a space as the join delimiter
  when comparing sorted labels and properties. Commas in labels are safe; spaces are not.
- **No Spring auto-configuration.** `MetamodelConfiguration` is not auto-registered. You must
  `@Import(MetamodelConfiguration::class)` or declare it in your application context explicitly.
