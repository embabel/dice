# dice-metamodel

Tracks how a knowledge-graph schema changes over time and protects stored propositions when it
does. Two comparisons live here: **declared vs. declared** (`MetamodelDiffer`) — two schema versions
you chose — and **declared vs. observed** (`DeclaredObservedDiffer`, `DriftCheckRunner`) — what you
declared against what a live graph actually holds. Both end at the same quarantine policy, which
marks affected propositions `STALE` rather than deleting them.

Read [metamodel-governance.md](../docs/design/metamodel-governance.md) first if you want the
reasoning: why a version is a content hash, why drift is observed after the fact instead of blocked
at write time, why quarantine never deletes.

## Contracts and support

Everything in `com.embabel.dice.metamodel` is contract — interfaces and immutable value types:

- `MetamodelVersion` — an immutable stamp of a `DataDictionary`, fingerprinted by a length-prefixed
  SHA-256 `contentHash` that excludes the schema name. Build one with `MetamodelVersion.from(dd)`.
- `MetamodelChange` / `MetamodelDiff` — the five structural change kinds and the diff that carries
  them. `DeclaredObservedDiff` is the asymmetric cousin: drift vs. unobserved.
- `DeclaredSchemaSource` / `ObservedSchemaSource` — where the two sides of a drift check come from.
  Neither has a default here; a consumer supplies the first, a storage module the second.
- `MetamodelStore` — log of versions and drift reports. Nothing is ever deleted, and both writes
  upsert on a natural key: re-saving under a key that already exists overwrites that record's
  non-key content rather than adding a duplicate. Three reads for drift reports, because the scope
  has to be visible at the call site: `driftReports` (everything), `globalDriftReports` (unscoped
  checks only), `driftReportsInContext` (one context only).
- `DriftCheckRunner` — sequences observe → declare → diff → report → optional quarantine. Dry-run by
  default. No scheduler of its own.
- `DriftQuarantinePolicy` — turns a diff plus some propositions into `QuarantineDecision`s. Returns
  immutable copies; the caller persists them.

`com.embabel.dice.metamodel.support` holds the shipped implementations: `StructuralMetamodelDiffer`
(both differ interfaces, stateless), `MentionTypeDriftQuarantinePolicy` (lossy changes only,
idempotent on already-quarantined propositions), and `DefaultDriftCheckRunner`. The top-level
package holds contracts only.

There is no Spring wiring in this module. The defaults are ordinary constructor calls; a ready-made
set of beans arrives with the autoconfigure slice, where `@ConditionalOnMissingBean` actually behaves
as advertised.

## Dependencies, and the ones to keep out

- **`dice` (core)** — required. `Proposition`, `PropositionStatus`, `PropositionRepository`,
  `DiceMetadataKeys`.
- **`embabel-agent-api`, `embabel-agent-rag-core`** — `provided`. `DataDictionary` and `ContextId`
  come from here; the consuming app supplies them.
- **slf4j** — logging only.

No Spring dependency at all, not even `provided`. Nothing here is annotated, so keep it that way —
bean wiring belongs in the autoconfigure module.

Never add Drivine, the Neo4j driver, or Jackson. This module has to stay runnable with no database
and no serialization framework — that is what lets tests drive the whole drift loop from a canned
`ObservedSchema`. Graph-side work belongs one module out: `DrivineMetamodelStore` lives in
`dice-storage`, and the Neo4j `ObservedSchemaSource` lands with the autoconfigure wiring that
assembles the `DriftCheckRunner` bean.

## Who depends on this

`dice-storage` (for `MetamodelStore` and the value types it persists). Autoconfiguration will follow.
Nothing here depends on either, so a change to the contracts ripples outward only.

## Tests

`src/test/kotlin/com/embabel/dice/metamodel/` — plain JUnit, no Spring context, no containers:
`MetamodelVersionTest` (hashing and its escape safety), `MetamodelDifferTest`,
`DriftQuarantinePolicyTest`, `DriftCheckRunnerTest` (fakes for the store, observer, and repository),
`DriftReportTest`. Run them with `mvn test -pl dice-metamodel`.

`MetamodelVersionTest.GoldenHash` pins the digest of a fixed schema to a literal string. That is on
purpose: the hash is a persisted format — a MERGE key in the store and a stored `versionHash` on
every drift report — so changing the encoding orphans reports already on disk. If the literal goes
red, decide whether you meant to change the format, and migrate; don't paste in the new value.

## Gotchas

- **The content hash excludes the schema name.** Deliberate, for dev/prod parity. It also means
  `contentHash` alone can't distinguish two identically-shaped schemas.
- **`EntityTypeModified` only fires for types in both versions.** Removed-and-re-added under the same
  name shows up as `EntityTypeRemoved` + `EntityTypeAdded`.
- **Quarantine is idempotent.** A proposition already `STALE` with a `QUARANTINE_REASON` comes back
  as `QuarantineDecision.AlreadyQuarantined`, unchanged — its own bucket, not the conforming one, so
  `conforming.size` stays an honest count of clean propositions. Clear the metadata key to force
  re-evaluation.
- **`contentHash` is derived, never supplied.** It's computed in `MetamodelVersion`'s body from the
  structural fields, so two versions that compare equal really do have the same shape.
- **Relationship names travel un-rendered.** `MetamodelVersion.relationshipNames` holds
  `From-[name]->To` descriptors; never parse a bare name back out of one. `DeclaredSchema` carries
  the bare names for that reason.
