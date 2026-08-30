# `dice-storage-autoconfigure` module — Agent Navigation Guide

This module is the Spring Boot wiring that picks a proposition-store backend and turns on the decay
schedule. It contains no domain logic and no persistence code — just `@AutoConfiguration` classes that
assemble beans from `dice` (the in-memory implementations and SPIs) and `dice-storage` (the
Drivine/Neo4j implementations). The *why* behind these decisions is in
[`docs/design/durable-storage.md`](../docs/design/durable-storage.md); this guide is the *where*.

## What's here

Under `com.embabel.dice.storage.autoconfigure`:

- **`DiceStorageAutoConfiguration`** — declares the store beans for both backends: `PropositionRepository`,
  `ChunkHistoryStore`, `DecayManager`, `ProjectionRecordStore`, `CollectorRecordStore`, and the
  `SchemaCatalog` beans (constraints, range indexes, vector index). Also `DiceDecaySchedulingConfiguration`,
  the separate auto-config that schedules the decay tick.
- **`DiceStoreProperties`** — `@ConfigurationProperties(prefix = "embabel.dice.store")`: the `type`
  switch plus nested `decay` and `vector-index` blocks.
- **`MetamodelAutoConfiguration`** — the opt-in wiring for schema governance. Activates only when the
  host declares a `DeclaredSchemaSource` bean (no declared schema, no metamodel beans at all); every
  default is `@ConditionalOnMissingBean`, so consumer-supplied implementations always win. See
  [`docs/design/metamodel-wiring.md`](../docs/design/metamodel-wiring.md).
- **`MetamodelProperties`** — `@ConfigurationProperties(prefix = "embabel.dice.metamodel")`: the
  `enabled` kill switch and `drift.mode` escalation tier (`off` | `observe` | `quarantine`;
  quarantine is never a default).

## How backend selection works

Three rules, and that's the whole mechanism:

1. **`embabel.dice.store.type=graph`** activates the Drivine/Neo4j beans (each gated with
   `@ConditionalOnProperty(... havingValue = "graph")`). Anything else, including unset, falls through
   to the in-memory beans (the default `type` is `in-memory`).
2. **Every store bean is `@ConditionalOnMissingBean`** — an application that defines its own store
   bean always wins; the autoconfig only fills gaps. (The `SchemaCatalog` beans below are the
   exception — they carry no `@ConditionalOnMissingBean`, so they're applied whenever the graph
   backend is active and aren't overridable by a competing bean.)
3. **Graph beans are declared before their in-memory counterparts**, so the flip resolves by
   registration order (with no need for mutually-exclusive conditions).

The graph repository and the vector-index schema additionally require `@ConditionalOnBean(Ai::class)`
— they need an embedding service, which comes from the embabel-agent `Ai` handle.

## Schema beans (graph backend only)

`SchemaCatalog` beans declare the Neo4j constraints and indexes; Drivine's `SchemaManager` (registered
by the starter) applies them idempotently on startup — there is no migration runner here.

- `propositionConstraintSchema` — uniqueness on `Proposition.id`, `Mention.id`, `ProcessedChunk.id`,
  `Source.key`, the composite `(Proposition.contextId, Proposition.text)` dedup backstop, and the range
  indexes queries filter by (`contextId`, `status`, `level`, `effectiveConfidence`, `Mention.resolvedId`, …).
- `lineageRecordSchema` — natural-key uniqueness for `ProjectionRecord`, `CollectorRecord`, and
  `CollectorRun`, which lets the lineage stores `MERGE` (upsert) without duplicating.
- `propositionVectorIndexSchema` — the cosine vector index on `Proposition.embedding`, sized to the
  embedding model's dimension and stamped with the model name as the schema version. Gated behind
  `embabel.dice.store.vector-index.enabled` (default true).

## The decay tick

`DiceDecaySchedulingConfiguration` is a *separate* `@AutoConfiguration(after = …)` so `@EnableScheduling`
is only switched on when decay is enabled (`embabel.dice.store.decay.enabled`, default true). It resolves
the `DecayManager` lazily via `ObjectProvider` so it works regardless of which backend registered one,
and ticks on `embabel.dice.store.decay.interval-ms` (default 1 hour), materialising cached confidence and
applying lifecycle transitions.

## Property reference

| Property | Default | Meaning |
|---|---|---|
| `embabel.dice.store.type` | `in-memory` | Backend: `graph` (Drivine/Neo4j) or `in-memory` |
| `embabel.dice.store.decay.enabled` | `true` | Whether the scheduled decay tick runs |
| `embabel.dice.store.decay.interval-ms` | `3600000` | Tick interval (1 hour) |
| `embabel.dice.store.decay.k` | `2.0` | Decay-rate multiplier for the staleness policy |
| `embabel.dice.store.decay.prune-stale` | `false` | Hard-delete STALE propositions during the sweep |
| `embabel.dice.store.vector-index.enabled` | `true` | Register the vector index schema |

The index's label (`Proposition`), property (`embedding`), name (`Proposition_embedding_vector`), and
similarity (cosine) aren't configurable — they're fixed by the `@VectorIndex` annotation on
`PropositionNode.embedding`, which the `loadNearest` path reads. Exposing them as config would let them
drift from that annotation and silently break vector search, so they live as constants on
`DrivinePropositionRepository`.

## Dependencies

- `dice` (core) — the store SPIs and the in-memory implementations.
- `dice-storage` — the Drivine/Neo4j implementations wired for the graph backend.
- `embabel-agent-api` (provided) — `Ai`, supplied at runtime by the consuming application.

## Gotchas

- The graph backend needs an `Ai` bean on the context; without one, the graph repository and vector
  index beans back off and you silently get the in-memory store even with `type=graph`.
- Changing the embedding model to a different vector dimension requires dropping and recreating the
  vector index — the schema is applied idempotently but won't resize an existing index.
- The decay tick is a no-op when no `DecayManager` is available (resolved lazily), so enabling decay
  without a store backend does nothing (it never fails).
