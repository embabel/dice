# DICE architecture overview

DICE is a proposition-first knowledge substrate: it turns raw text into confidence-weighted natural
language statements (propositions), keeps them healthy over time, and projects them into whatever
representation a task needs — a Neo4j graph, a Prolog fact base, vector embeddings, or agent
working memory. Propositions are the single system of record. Everything else derives from them.

## Module map

DICE is a multi-module Maven build. Each module's intent, and what it's allowed to depend on:

| Module | Intent |
|---|---|
| `dice` | The core: proposition model, pipeline, gates, projection interfaces, query facades, agent tools, REST controllers. In-memory implementations only — no database driver. |
| `dice-storage` | The durable Neo4j backend: `Drivine`-based repository, graph/Prolog/lineage projectors, schema and index bootstrap. Depends on `dice`. |
| `dice-storage-autoconfigure` | Spring Boot autoconfiguration that wires `dice-storage`'s beans (repository, projectors, trust scorer) into a host application. Depends on `dice-storage`. |
| `dice-ingestion` | Content-hash dedup ledger and source adapters that sit in front of `PropositionPipeline`, so the same artifact is never extracted twice concurrently. Depends on `dice`. |
| `dice-report` | Rationale and structured report generation over propositions and their lineage. Depends on `dice`. |
| `dice-metamodel` | Schema governance contracts: `MetamodelVersion` stamping, declared-vs-observed diffing, drift reports, quarantine policy. Pure JVM — no database driver, no Spring wiring. Depends on `dice`. |
| `dice-integration-tests` | End-to-end tests exercising the real Neo4j backend and full pipeline across module boundaries. Depends on `dice`, `dice-ingestion`, `dice-report` (and transitively `dice-storage`). Not shipped. |

```mermaid
flowchart TB
    dice["dice<br/>(core)"]
    storage["dice-storage<br/>(Neo4j backend)"]
    autoconf["dice-storage-autoconfigure<br/>(Spring Boot wiring)"]
    ingestion["dice-ingestion<br/>(dedup ledger)"]
    report["dice-report<br/>(rationale/reports)"]
    metamodel["dice-metamodel<br/>(schema governance)"]
    itest["dice-integration-tests"]

    storage --> dice
    metamodel --> dice
    autoconf --> storage
    ingestion --> dice
    report --> dice
    itest --> dice
    itest --> ingestion
    itest --> report
```

`dice` never depends on any other DICE module — it's the leaf of the graph, so every other module
can be added or removed without touching core logic. `dice-storage-autoconfigure` is the only
module that knows about Spring Boot autoconfiguration; plain `dice-storage` stays framework-neutral
so it can be wired by hand outside Spring Boot.

### Subsystem design docs

Each subsystem below the module level has its own design note:

- [proposition-lifecycle](proposition-lifecycle.md) — proposition states and transitions
- [extraction-pipeline](extraction-pipeline.md) — `PropositionPipeline` extraction and revision
- [ingestion](ingestion.md) — content-hash dedup ledger, source adapters
- [graph-projection](graph-projection.md) — `GraphProjector`, `Reconciler`, lineage
- [prolog-projection](prolog-projection.md) — `PrologProjector`, fact base generation
- [retrieval-and-discovery](retrieval-and-discovery.md) — `RetrievalRouter`, `GraphQuery`
- [oracle-and-query](oracle-and-query.md) — query answering over the substrate
- [consolidation-and-dream-loop](consolidation-and-dream-loop.md) — `DreamLoopOrchestrator`
- [knowledge-hygiene](knowledge-hygiene.md) — admission gates, pinning, decay
- [reclamation-and-collector](reclamation-and-collector.md) — mark-and-sweep reclamation
- [multi-signal-collector](multi-signal-collector.md) — collector marking strategies
- [collector-trace-store](collector-trace-store.md) — collector audit trail persistence
- [grounding-and-conflicts](grounding-and-conflicts.md) — contradiction and grounding resolution
- [entity-resolution-and-text2graph](entity-resolution-and-text2graph.md) — entity resolution, text-to-graph
- [durable-storage](durable-storage.md) — `dice-storage` backend, schema, indexes
- [events](events.md) — `DiceEvent` model and emitters
- [report](report.md) — `dice-report` rationale and structured reports
- [web-api](web-api.md) — REST surface (`DiscoveryController` and friends)

## System-level map

The subsystems form a left-to-right pipeline from ingestion through maintenance to query. Each box
is a distinct subsystem with its own design note.

```mermaid
flowchart LR
    subgraph ingest ["Ingestion"]
        ING["Content hash<br/>dedup ledger"] --> PIPE["PropositionPipeline<br/>(extract → resolve → revise)"]
    end
    subgraph store ["Store + trust"]
        PS[("PropositionStore<br/>PropositionRepository")]
        TR["TrustScorer<br/>AuthorityResolver"]
        PS --- TR
    end
    subgraph maintain ["Maintenance"]
        GATE["Admission gates<br/>(ExtractionGatePipeline)"]
        DREAM["Dream-loop<br/>(DreamLoopOrchestrator)"]
        COLLECT["Collector<br/>(CollectorRunner)"]
        PIN["Pinning<br/>(pin / unpin)"]
    end
    subgraph project ["Projection"]
        GRAPH["GraphProjector<br/>+ Reconciler"]
        PROLOG["PrologProjector"]
        MEM["MemoryProjector"]
        LIN["ProjectionRecordStore<br/>CollectorRecordStore"]
    end
    subgraph query ["Query / retrieval"]
        GQ["GraphQuery<br/>(neighborhood, path, lineage)"]
        RR["RetrievalRouter<br/>(vector / entity / graph / temporal / hybrid)"]
        DISC["Link discovery<br/>(serendipitous)"]
        REPORT["Reports<br/>(rationale, structured)"]
    end
    subgraph expose ["Expose"]
        REST["DiscoveryController<br/>(REST)"]
        AGENT["DiscoveryTools<br/>GraphQueryTools<br/>Memory<br/>(agent tools)"]
    end

    ingest --> GATE --> store
    store --> maintain
    maintain --> project
    project --> query
    query --> expose
```

## Subsystem by subsystem

### Ingestion

The `dice-ingestion` module's dedup ledger claims a content hash before any extraction runs,
so two concurrent ingests of the same artifact never both proceed. Once through the ledger,
`PropositionPipeline` runs in two stages: a concurrent extraction stage (fan-out to the LLM,
order preserved) followed by a serial entity-resolution stage (one shared cross-chunk resolver).
The pipeline writes nothing — it hands an unsaved result back to the caller, which decides when
and where to persist. See [extraction-pipeline](extraction-pipeline.md).

### Store and trust/authority

`PropositionStore` is the base port: CRUD plus a composable `PropositionQuery`. `PropositionRepository`
extends it with three opt-in capability fragments a backend declares only when it genuinely supports
them:

| Fragment | What it adds |
|---|---|
| `VectorSearchCapable` | similarity search and clustering |
| `GraphTraversalCapable` | proposition abstraction-hierarchy traversal |
| `TemporalQueryCapable` | bitemporal valid/observed window queries |

A separate interface, `GraphQueryCapable`, adds native neighbourhood, path, and lineage queries
(with `honorsAuthorityFilter` and `honorsContextFilter` as its own opt-ins) for a graph-native
backend. It is not part of `PropositionRepository` — a store implements it in addition, and
`GraphQuery` casts the store `as? GraphQueryCapable` at runtime, routing to the native implementation
when the backend provides one. The durable Neo4j backend (`dice-storage`) implements it and confines
each walk to the query's context in Cypher, so graph queries there run as native Cypher for both
unscoped and context-scoped queries (which is what the production callers issue); a store without the
capability, or one that doesn't confine a walk to a context itself, falls back to the portable,
store-agnostic walk.

`TrustScorer` and `AuthorityResolver` are advisory — they score and rank, never delete or hide.
`AuthorityWeightedTrustScorer` is the production scorer; the default is neutral (everything trusted
equally). See [proposition-lifecycle](proposition-lifecycle.md) and [durable-storage](durable-storage.md).

### Maintenance

Three mechanisms keep the store healthy at three different moments:

```mermaid
flowchart TB
    subgraph gates ["1. Admission · at ingest"]
        G["ExtractionGatePipeline chains gates.<br/>GateDecision: Persist / Reject / RouteToReview / SkipProjection / Demote"]
    end
    subgraph collector ["2. Reclamation · continuously"]
        C["CollectorRunner: mark (Stale / Duplicate / Custom) then sweep.<br/>SweepPolicy decides: TransitionStatus / HardDelete / Skip.<br/>Pinned propositions are always skipped."]
    end
    subgraph dream ["3. Consolidation · periodically"]
        D["DreamLoopOrchestrator: SessionConsolidation → Abstraction → ContradictionResolution → DecaySweep.<br/>Threshold-gated; one write per cycle."]
    end
    gates --> collector --> dream
```

Pinning (`pin(id)` / `unpin(id)`) is a cross-cutting immunity: a pinned proposition is skipped by
the collector's sweep policy, not auto-demoted on contradiction, and excluded from the dream-loop's
contradiction-resolution pass. See [knowledge-hygiene](knowledge-hygiene.md),
[reclamation-and-collector](reclamation-and-collector.md), and
[consolidation-and-dream-loop](consolidation-and-dream-loop.md).

### Projection

`GraphProjector` turns propositions into graph edges, running each through a `Reconciler` that
returns `CreateNew`, `Adopt`, or `Align`. Every outcome — including skips and failures — is recorded
as a `ProjectionRecord`. When a proposition reaches a terminal status (superseded, contradicted,
stale), a listener cascades that to `STALE` on every associated `ProjectionRecord`. `PrologProjector`
and `MemoryProjector` project into the other backends.

Durable lineage is backed by `DrivineProjectionRecordStore` and `DrivineCollectorRecordStore` (in
`dice-storage`), which persist `(:ProjectionRecord)` and `(:CollectorRecord)` nodes in Neo4j so
audit trails survive a restart. See [graph-projection](graph-projection.md).

### Query / retrieval / discovery

`GraphQuery` is the portable graph facade — it answers neighbourhood, path, and lineage questions by
walking propositions over any store, routing to a native `GraphQueryCapable` backend when available.
`RetrievalRouter` is the single multi-modal entry point: it checks whether the backing store
supports the requested mode (VECTOR / ENTITY / GRAPH_WALK / TEMPORAL / HYBRID) and returns an
empty `supported=false` result rather than falling back to a scan when the mode isn't available.

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant Router as RetrievalRouter
    participant GQ as GraphQuery
    participant Store as PropositionStore
    Caller->>Router: retrieve(DiscoveryQuery)
    Router->>Router: clamp depth and topK
    Router->>Router: does the store support this mode?
    alt mode supported
        Router->>Store: run the retrieval (vector / entity / temporal)
        Store-->>Router: propositions
    else graph_walk or hybrid
        Router->>GQ: neighborhood(entityId, depth)
        GQ-->>Router: GraphNeighborhood
    else not supported
        Router-->>Caller: DiscoveryResult(supported=false)
    end
    Router-->>Caller: DiscoveryResult (DTOs only)
```

See [retrieval-and-discovery](retrieval-and-discovery.md).

### Expose: agent tools and REST

```mermaid
flowchart LR
    subgraph agent ["Agent (contextId baked in)"]
        MT["Memory / MemoryRetriever"]
        GQT["GraphQueryTools<br/>(neighborhood, path, why)"]
        DT["DiscoveryTools<br/>(query, path, why, health, dry-run)"]
    end
    subgraph rest ["REST (contextId from URL)"]
        DC["DiscoveryController<br/>/api/v1/contexts/{contextId}/discovery"]
        PC["PropositionPipelineController"]
        MC["MemoryController"]
    end
    GQT --> GQ[GraphQuery]
    DT --> RR[RetrievalRouter]
    DC --> RR
    MT --> PS[PropositionStore]
    PC --> PIPE[PropositionPipeline]
    MC --> PS
```

Agent tools and REST share the same underlying routers and stores. The contextId is structurally
isolated — agent tools bake it in at construction, REST takes it from the URL path only. Neither
surface accepts a context override in the request body.

## Events

`EventEmittingPropositionRepository` and `EventEmittingProjector` are decorators that publish
`DiceEvent`s synchronously on saves and projections. The collector emits `PropositionStatusChanged`
per transition — identical to any other status change — so downstream consumers can't tell whether
a transition came from the collector, the reviser, or the dream loop. See [events](events.md).

## Neo4j graph schema

The durable Neo4j backend (`dice-storage`) holds these node labels and key relationships:

```mermaid
flowchart LR
    P["(:Proposition)"]
    M["(:Mention)"]
    S["(:Source)"]
    C["(:ProcessedChunk)"]
    PR["(:ProjectionRecord)"]
    CR["(:CollectorRecord)"]
    P --HAS_MENTION--> M
    P --DERIVED_FROM--> S
    P --ABSTRACTED_FROM--> P
    C --PROCESSED_INTO--> P
```

Uniqueness constraints on `(Proposition.contextId, Proposition.text)` guard dedup. A cosine vector
index on `Proposition.embedding` powers similarity search. Range indexes on `contextId`, `status`,
`effectiveConfidence`, and `Mention.resolvedId` push filters to the database. `ProjectionRecord`
and `CollectorRecord` MERGE on their natural keys so replayed writes are idempotent.

## Where to look first for each concern

| Concern | Where to start |
|---|---|
| Extraction + concurrency | `dice/pipeline`, `PropositionPipeline` |
| Proposition model and fields | `dice/proposition/Proposition.kt` |
| Store SPI and capability fragments | `dice/proposition/PropositionRepository.kt`, `GraphQueryCapable.kt` |
| Trust and authority scoring | `dice/spi/TrustScorer.kt`, `AuthorityResolver.kt` |
| Admission gates | `dice/proposition/gate/` |
| Pinning | `PropositionStore.pin/unpin`, `StatusTransitionSweepPolicy` |
| Dream-loop consolidation | `dice/projection/memory/DreamLoopOrchestrator.kt` |
| Mark-and-sweep reclamation | `dice/projection/memory/CollectorRunner.kt`, `dice/spi/SweepPolicy.kt` |
| Graph projection + lineage | `dice/projection/graph/`, `dice/projection/lineage/` |
| Durable Neo4j backend | `dice-storage/` |
| Retrieval router | `dice/query/discovery/RetrievalRouter.kt` |
| Graph query facade | `dice/query/graph/GraphQuery.kt` |
| Agent tools | `dice/agent/DiscoveryTools.kt`, `GraphQueryTools.kt` |
| REST surface | `dice/web/rest/DiscoveryController.kt` |
| Events | `dice/common/` (event types), `EventEmittingPropositionRepository` |
| Spring Boot wiring | `dice-storage-autoconfigure/DiceStorageAutoConfiguration.kt` |
