# FalkorDB storage

`dice-storage-falkordb-autoconfigure` stores propositions and chunk history in FalkorDB through
Drivine 0.1.8. Add it to an application that already supplies the Embabel Agent 1.5.3 APIs, and
declare Jedis 8.0.1 directly in the application's dependency management: a consuming BOM can
otherwise pull in a Jedis that JFalkorDB 0.13 cannot use.

```xml
<dependency>
  <groupId>com.embabel.dice</groupId>
  <artifactId>dice-storage-falkordb-autoconfigure</artifactId>
  <version>0.5.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>redis.clients</groupId>
  <artifactId>jedis</artifactId>
  <version>8.0.1</version>
</dependency>
```

## Configuration

```properties
embabel.dice.store.type=falkordb
embabel.dice.store.falkordb.datasource=knowledge
database.datasources.knowledge.type=FALKORDB
database.datasources.knowledge.host=${FALKORDB_HOST}
database.datasources.knowledge.port=${FALKORDB_PORT}
database.datasources.knowledge.database-name=${FALKORDB_GRAPH}
database.datasources.knowledge.cypher-dialect=FALKORDB
```

`default` names Drivine's first datasource. The auto-configuration checks that the datasource is a
FalkorDB one, takes the object manager and persistence manager for it from Drivine, and registers
the stores' schema as a catalog for it, which Drivine ensures at startup. It also sets the
datasource's `falkor-db-transaction-mode` to `STRICT` unless you set it yourself (see below).

The store embeds with the application's own `EmbeddingService` bean when there is exactly one (or
one marked `@Primary`), and otherwise with the platform's default embedding model, as the other
stores do; the startup log says which. With no `Ai` bean and no embedding bean, vector searches
return nothing and a startup warning says so. Your own `PropositionRepository` or
`ChunkHistoryStore` bean replaces the auto-configuration's.

Without the auto-configuration, build the stores yourself:
`FalkorDbPropositionRepository(objects, persistence, embeddingService)` and
`FalkorDbChunkHistoryStore(objects)`, and register `FalkorDbSchema.catalog(datasource)`. The
repository takes an `ExactVectorBudget` as an optional fourth argument for the vector search cap
described below.

## How it is stored

A proposition is one `DiceProposition` node with typed fields, so filters and ordering run in the
database through Drivine's query DSL. Mentions, evidence and metadata are kept on the node as JSON
text; metadata values must be plain JSON (strings, numbers, booleans, lists and maps), and a number
reads back as Jackson reads it, so `3L` comes back as `3`. FalkorDB returns a stored double to 15
significant digits, so `0.1 + 0.2` reads back as `0.3`. Comparisons in the database use the exact
value.

Chunk history is a `DiceChunkHash` node per (context, hash) and a `DiceBookmark` node per
(context, source), each with an id derived from its pair. The schema makes the proposition id, both
pairs and the derived ids unique, and indexes the fields queries filter on.

Vector searches score every proposition that passes the query's filters, exactly, with FalkorDB's
cosine distance, and keep the best: a filter never hides a match. FalkorDB scores in single
precision, so a score can differ from the in-memory store's in the seventh decimal place. A vector
whose length differs from the query's, or that no longer matches its proposition's text, is left out
until `reembedAll` replaces it.

A filtered vector search first counts the propositions its query lets through, and fails with
`ScopedReadException(EXACT_VECTOR_BUDGET_EXCEEDED)` when there are more than
`embabel.dice.store.exact-vector.max-candidates` (10,000 by default). Narrow the query or raise the
property. `embabel.dice.store.exact-vector.timeout` has no effect on FalkorDB: the server's own
`TIMEOUT` setting is the only deadline on a search. The unfiltered search has no cap and scans every
proposition, and `findClusters` has none either: it pairs every proposition its query returns, so
give that query a limit to bound it.

No approximate vector index is created, so `findApproximateSimilarWithScores` runs the exact
filtered search. `LlmPropositionReviser` looks for similar propositions through it, so revision on
FalkorDB is subject to the same cap, and fails in a context with more active propositions than
`max-candidates` allows.

## Writes and transactions

FalkorDB has no transactions across statements, so every write the stores make is one statement:

- `save` creates the proposition, or merges it into the stored one: its fields win and its evidence
  is added to what is there. Concurrent first saves keep every writer's evidence.
- `pin`, `unpin` and the provenance methods reload and reapply if another writer got there first.
  Under sustained contention they give up with Drivine's `StaleObjectException`.
- `touchAccessed` patches the access time of up to a thousand propositions in one statement.
- `recordProcessed` writes the hash and the bookmark in one statement.

In `STRICT` mode Drivine refuses a caller's `@Transactional` and any batch it cannot write as one
statement, rather than half applying it. Several calls never form one unit: persist your own
completion markers only after every write they cover has succeeded.

## Moving existing knowledge

Changing the selector does not move a Neo4j graph. With both stores quiet, copy through the
repository APIs into an empty FalkorDB graph, hydrating evidence explicitly, and check each
proposition afterwards:

```kotlin
fun copyKnowledge(source: PropositionRepository, target: PropositionRepository) {
    check(target.count() == 0) { "Use an empty target" }
    source.findAll().forEach { proposition ->
        val complete = proposition.copy(provenanceEntries = source.provenanceOf(proposition.id))
        target.save(complete)
        check(target.provenanceOf(complete.id).toSet() == complete.provenanceEntries.toSet()) { "Evidence changed" }
    }
}
```

Chunk history, audit records, traces and metamodel versions are not copied by this.

## What stays in memory

Projection and collector audit records, the collector trace and the metamodel version store have no
FalkorDB implementation yet. With `type=falkordb` they stay in memory and are lost on restart, and a
startup warning lists the ones that are; register your own beans to keep them. The decay manager
works through the proposition store, so its status changes persist. Native graph traversal is not
offered either: the portable facade reads propositions instead.
