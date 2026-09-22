# The proposition re-embed should reconcile its own index

> A change of embedding model may make the vector WIDER or NARROWER. Nothing here depends on the
> direction: an index either has the shape the model asks for or it does not.

## Goal

Make `reembedAll()` leave the graph consistent on its own, so a host changing embedding model
calls one method instead of sequencing three — and cannot get the order wrong.

## Background

`PropositionRepository.reembedAll()` rewrites every proposition's vector with the currently
configured embedding service. Its own contract states the limit:

> Caveat: a model swap that changes the embedding *dimension* also needs the backend's vector
> index recreated at the new dimension — a backend/schema concern this does not perform. For
> same-dimension re-embeds (the common case) it is sufficient on its own.

So after a change of model the vectors are at the model's width and the index is at the previous
one — wider or narrower, it does not matter which.
Nothing errors; searches simply stop returning what they should.

`PropositionVectorIndexConvergence.drop()` (#114) gave hosts the missing half, and `me` uses it.
This proposes removing the need for hosts to know about it at all.

## Why the two sides differ today

Not because one uses Drivine and the other does not — both do. The difference is **when the index
spec exists and who holds it**.

`DrivineStore` in `embabel-agent-rag-graph` computes its specs on demand and calls Drivine
directly, so it can do DDL at the live width at any moment:

```kotlin
private val vectorIndexSpecs: List<VectorIndexSpec>
    get() = listOf(
        VectorIndexSpec(properties.chunkNodeName, "embedding", embeddingService.dimensions, …),
        VectorIndexSpec(properties.entityNodeName, "embedding", embeddingService.dimensions, …),
    )

override fun reembedAll(): ReembedReport {
    vectorIndexSpecs.forEach { persistenceManager.indexes.drop(it) }
    val chunks = reembedNodesWithLabel("Chunk")
    val entities = reembedNodesWithLabel(properties.entityNodeName)
    provision()                       // rebuilt at the width the model now reports
    return ReembedReport(chunks, entities)
}
```

Dice declares its index through a startup `SchemaCatalog`
(`DiceStorageAutoConfiguration.propositionVectorIndexSchema`), which `SchemaManager` snapshots
when it is built and applies once. `DrivinePropositionRepository` — the class that rewrites the
vectors — never receives a spec, so it has nothing to do DDL with.

## Current state

**dice** — the re-embed writes vectors and no DDL:

```kotlin
@Transactional
override fun reembedAll(): Int {
    logger.info("reembedAll start: model={} dim={}", embeddingService.name, embeddingService.dimensions)
    val specs = graphObjectManager.loadAll<PropositionView> { … }.mapNotNull { view ->
        view.proposition.text.takeIf { it.isNotBlank() }?.let { text ->
            QuerySpecification
                .withStatement("MATCH (p:Proposition {id: \$id}) SET p.embedding = \$embedding")
                .bind(mapOf("id" to view.proposition.id, "embedding" to embeddingService.embed(text).toList()))
        }
    }
    if (specs.isNotEmpty()) persistenceManager.executeBatch(specs)
    return specs.size
}
```

**me** — the host brackets the call, and has to know the order:

```kotlin
private fun reembedPropositions(): Int {
    val index = propositionIndex.ifAvailable
    index?.drop()
    val count = propositionRepository.reembedAll()
    index?.ensure()
    return count
}
```

with the collaborator that exists only for those two lines:

```kotlin
class EmbeddingModelReindexService(
    embeddingService: EmbeddingService,
    private val modelProvider: ModelProvider,
    private val graphRagStore: GraphRagStore,
    private val propositionRepository: PropositionRepository,
    private val propositionIndex: ObjectProvider<PropositionVectorIndexConvergence>,
)
```

## Gaps

1. **The order is the host's to get right, and only one order works.** Drop, re-embed, remake. An
   index recreated before the run declares a width none of the stored vectors have; one left in
   place declares the old width throughout. Nothing enforces this, and every host re-derives it.
2. **The knowledge is split across two artifacts that cannot see each other.** The repository owns
   the vectors; the convergence class owns the index. Neither can make the graph consistent alone.
3. **A host that calls only `reembedAll()` is silently wrong across a width change** — the exact
   case anyone calls it for.

## Direction

Let the Drivine backend override `reembedAll()` and reconcile inline, as `DrivineStore` does.

```kotlin
@Transactional
override fun reembedAll(): PropositionReembedReport {
    val spec = vectorIndexSpec(embeddingService.dimensions)
    // `ensure` is non-destructive and reports Drift against an index of a different shape, so it
    // doubles as the question "does the stored index still match the model?" — WIDER or NARROWER
    // alike, since a shape is either the one asked for or it is not.
    val shapeChanged = persistenceManager.indexes.ensure(spec) is EnsureResult.Drift
    if (shapeChanged) persistenceManager.indexes.drop(spec)

    val count = /* the batch above, unchanged */

    if (shapeChanged) persistenceManager.indexes.ensure(spec)
    return PropositionReembedReport(propositions = count, indexRecreated = shapeChanged)
}
```

and `me` loses the bracketing and the collaborator:

```kotlin
private fun reembedPropositions(): Int = propositionRepository.reembedAll().propositions
```

Three things to settle:

- **Where the spec comes from.** `DiceStorageAutoConfiguration.propositionVectorIndexSpec` is
  `internal` and builds it from constants that are already canonical on
  `DrivinePropositionRepository`. Either pass a `(Int) -> VectorIndexSpec` into the repository, or
  move the builder beside the constants it already reads.
- **The return type.** `Int` cannot say whether the index moved, which is the one thing a caller
  wants to log. `DrivineStore` returns a `ReembedReport`; this would mirror it.
- **What happens to `PropositionVectorIndexConvergence`.** `ensure()` still has a job — creating
  the index the first time a key arrives, with nothing to re-embed. `drop()` would have no callers
  and should go with this change rather than linger as a second way to do the same thing.

Backends that own no index — in-memory — keep the default, which is why this belongs in the
override and not in the interface.

## Outcome

A host changes the embedding model, calls `reembedAll()`, and the graph is consistent: vectors and
index at the same width, or a failure that names which. No host has to know the order, and the
only remaining reason to reach for the index directly is the first-key case `ensure()` already
serves.
