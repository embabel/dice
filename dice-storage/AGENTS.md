# `dice-storage` module — Agent Navigation Guide

This module is the Drivine/Neo4j implementation of the core `dice` store SPIs. It provides durable, graph-backed implementations of `PropositionRepository`, `ChunkHistoryStore`, and `DecayManager`. Everything in here is a concrete backend; the interfaces and domain model live in the `dice` module.

Kotlin 2.2.0 (one minor version above `dice`'s 2.1.10 — see below). Java 21.

## What it implements

| `dice` SPI | Implementation here |
|---|---|
| `PropositionRepository` | `DrivinePropositionRepository` |
| `ChunkHistoryStore` | `DrivineChunkHistoryStore` |
| `DecayManager` | `GraphDecayManager` |
| `ProjectionRecordStore` | `DrivineProjectionRecordStore` — persists projection lineage as `(:ProjectionRecord)` nodes so it survives a restart and stays queryable by proposition or lifecycle; MERGEs on the natural key (propositionId + runId + target) so a replayed outcome updates in place |
| `CollectorRecordStore` | `DrivineCollectorRecordStore` — persists the collector audit trail as `(:CollectorRecord)` and `(:CollectorRun)` nodes; MERGEs on natural keys so retried records update rather than duplicate |

## How it relates to the core SPI

`DrivinePropositionRepository` implements every capability in `PropositionRepository` — not just basic CRUD. Filtering, ordering, limiting, vector similarity, and entity-mention predicates all push into Neo4j via the Drivine `StatelessGraphObjectManager` DSL. Hand-written Cypher is used only for a few things the DSL can't express: the `findClusters` correlated query, cascade-aware bulk clears (`queries/clear_propositions.cypher`), the dedup existence check, and the batch re-embed.

**Exact-text dedup.** Parallel chunk extraction can mint identical propositions. `DrivinePropositionRepository.save()` runs a stripe-locked find-then-insert to collapse exact `(contextId, text)` duplicates into a single node. There is also a Neo4j uniqueness constraint on `(contextId, text)` as a cross-instance backstop.

**Two-phase save for provenance.** `doPersist()` writes node + mentions with `Replace(PropositionView::mentions, DELETE_UNREFERENCED)` (authoritative — stale mentions are reconciled), then writes provenance edges with `PRESERVE` (append-only — existing evidence is never dropped by a lean save). Use `setProvenance()` / `clearProvenance()` when you need to replace evidence authoritatively.

**Stamped proposition nodes.** `PropositionNode` carries a Drivine `@NodeStamp`. `Proposition` does not, so `save(proposition)` is unchecked. The repository's own load-then-save paths (`setProvenance`, the dedup evidence merge) save with the stamp they loaded and load again when the save is refused as stale. Raw Cypher that changes a `:Proposition` or one of its relationships must mark the stamp with `Stamps.setClause("p")` or `Stamps.linksClause("p")`, or a checked save will not notice the write.

**Materialised effective confidence.** The graph stores a pre-computed `effectiveConfidence` column (updated by the decay sweep). Queries with default `decayK=2.0` and no custom `asOf` push this column to the DB. Non-default decay parameters fall back to in-memory filtering over a candidate set from the DB.

## Source layout

```
src/main/kotlin/com/embabel/dice/storage/
  DrivinePropositionRepository.kt     — main repository implementation
  DrivineChunkHistoryStore.kt         — processed-chunk dedup store
  DrivineProjectionRecordStore.kt     — durable ProjectionRecord store (Neo4j-backed lineage)
  DrivineCollectorRecordStore.kt      — durable CollectorRecord/CollectorRun store (audit trail)
  GraphDecayManager.kt                — decay tick that updates the graph in place
  LineageRowMappers.kt                — row mappers shared by the lineage stores: translates
                                        ProjectionRecord and CollectorRecord to/from property maps;
                                        kept separate so the fiddly enum/timestamp marshalling
                                        can be unit-tested without a database
  PropositionGraphMapper.kt           — maps between Proposition domain objects and Drivine graph views
  model/
    PropositionNode.kt              — @GraphNode for the :Proposition label
    PropositionView.kt              — lean Drivine view (node + mentions, no provenance)
    PropositionWithProvenanceView.kt — full view with DERIVED_FROM edges
    Mention.kt                      — @GraphRelationship for HAS_MENTION
    ProcessedChunkNode.kt           — @GraphNode for dedup tracking
    SourceNode.kt                   — @GraphNode for :Source (provenance)
    DerivedFrom.kt                  — @GraphRelationship for DERIVED_FROM (provenance)
```

The `codegen-gradle/` directory holds a nested Gradle project that runs Drivine's KSP annotation processor to generate the `where { }` / `orderBy { }` query DSL. The generated sources land in `codegen-gradle/build/generated/ksp/main/kotlin` and are added to the Maven compile source set automatically during `generate-sources`. The `mvn verify` cycle runs the Gradle task for you.

## Why Kotlin 2.2 here

Drivine's generated `where { }` DSL uses Kotlin context parameters (`-Xcontext-parameters`), which is a Kotlin 2.2 feature. The `dice` core is pinned to 2.1.10 by the tuProlog dependency. Kotlin 2.2 can read 2.1.10 class metadata (`-Xskip-metadata-version-check` is set in the pom), so depending on `dice` from here is fine.

## Integration tests need Docker

`DrivinePropositionStoreIntegrationTest` runs against a real Neo4j instance spun up by `testcontainers:neo4j`. Docker must be running when you do `mvn verify -pl dice-storage`. Tests use `@SpringBootTest(classes = [TestApplication::class])` and isolate via explicit `clearAll()` calls rather than transaction rollback (the dedup logic commits via its own `TransactionTemplate`, which would escape a test-managed transaction boundary).

The root pom pins `api.version=1.41` as a Surefire JVM system property. Without it, docker-java defaults to API version 1.32, which is below the engine minimum and causes silent HTTP 400 failures.

## Schema management

`dice-storage-autoconfigure` declares `SchemaCatalog` beans that Drivine applies idempotently on startup:
- Uniqueness constraints on `Proposition.id`, `Mention.id`, `ProcessedChunk.id`, `Source.key`
- Composite uniqueness on `(Proposition.contextId, Proposition.text)` — the cross-instance dedup backstop
- Range indexes on `contextId`, `status`, `level`, `effectiveConfidence`, `Mention.resolvedId`, `ProcessedChunk.sourceId`, `Source.kind`
- A cosine vector index on `Proposition.embedding` sized to the embedding model's dimension

If you change the embedding model to one with a different vector dimension, you need to drop and recreate the vector index — `reembedAll()` alone is not sufficient for a dimension change.

## Configuration (via `dice-storage-autoconfigure`)

All wiring is in `DiceStorageAutoConfiguration`. Activate the graph backend with:

```properties
embabel.dice.store.type=graph
```

Other knobs:

| Property | Default | Meaning |
|---|---|---|
| `embabel.dice.store.decay.enabled` | `true` | Whether the scheduled decay tick runs |
| `embabel.dice.store.decay.interval-ms` | `3600000` | Tick interval (1 hour) |
| `embabel.dice.store.decay.k` | `2.0` | Decay rate multiplier |
| `embabel.dice.store.decay.prune-stale` | `false` | Hard-delete STALE propositions during sweep |
| `embabel.dice.store.vector-index.enabled` | `true` | Whether to register the vector index schema |
| `embabel.dice.store.vector-index.similarity-function` | `cosine` | Vector similarity function |
