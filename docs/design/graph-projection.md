# Graph projection: lineage, outcomes, and staleness

DICE projects its propositions into a typed graph so they can be queried as entities and
relationships. Projection can quietly erode trust: edges with no trail back to their evidence,
duplicate nodes on every re-run, and stale structure left behind when the underlying facts change.
This note is about the decisions that keep the projected graph honest — not about the projector
classes themselves.

## The projection pipeline

Propositions flow from the store through a reconciler and into the target backend, with a
`ProjectionRecord` written for every outcome whether the proposition lands as a new artifact, adopts
an existing one, is skipped, or fails. The authority tier travels with the record.

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant Service as GraphProjectionService
    participant Projector as GraphProjector
    participant Reconciler
    participant Backend as GraphRelationshipPersister
    participant Records as ProjectionRecordStore
    Caller->>Service: projectAndPersist(propositions)
    Service->>Projector: projectAll(propositions, schema)
    Projector-->>Service: ProjectionResults<ProjectedRelationship>
    loop each successful result
        Service->>Reconciler: reconcile(proposition, target, projected)
        Reconciler-->>Service: CreateNew / Adopt / Align
    end
    Service->>Backend: persist(projectionResults)
    Backend-->>Service: RelationshipPersistenceResult
    loop each result
        Service->>Records: record(ProjectionRecord)
    end
    Service-->>Caller: Pair<ProjectionResults, RelationshipPersistenceResult>
```

```kotlin
val (projectionResults, persistenceResult) = graphProjectionService.projectAndPersist(propositions)
projectionResults.results.forEach { result ->
    when (result) {
        is ProjectionSuccess -> println("projected: ${result.projected}")
        is ProjectionSkipped -> println("skipped: ${result.reason}")
        is ProjectionFailed -> println("failed: ${result.reason}")
    }
}
```

`GraphProjector` itself only does the classification step (`project`/`projectAll`, turning a
proposition into a `ProjectedRelationship`); it has no notion of a reconciler, a backend, or a
record store. Reconciliation, persistence, and lineage recording are all orchestrated by
`GraphProjectionService.projectAndPersist`.

`EventEmittingProjector` is a generic decorator available for any `Projector`: it wraps a
delegate, forwards `projectAll`, and publishes a `ProjectionBatchCompleted` event afterwards so
listeners can react without polling. It isn't wired into `GraphProjectionService` by default —
use it if you want that event.

## Edge lineage

When a proposition becomes a graph edge, that's not the end of the story — DICE writes a record of
it. Each projection result becomes a `ProjectionRecord` (which proposition, which target, which
graph artifact, the outcome, and when), and the projected edge itself carries the IDs of the source
propositions and the authority tier of their source.

The reason is auditability. An edge with no provenance is an opaque assertion: you can't ask "where
did this come from?" or "how much should I trust it?" Keeping the record turns the graph into
something you can interrogate. The record store is even reversible — given an artifact reference
from the graph you can find every record that created or adopted it:

```kotlin
val records = recordStore.findByTargetRef(targetRef)
```

so a graph artifact can always be traced back to the text that justified it.

Authority travels with the edge for the same reason it matters everywhere else (see
[proposition-lifecycle](proposition-lifecycle.md)): a relationship derived from a first-party record
shouldn't be weighed the same as one inferred from a passing mention. The tier is re-stamped
whenever an edge is re-persisted, so it's never silently lost.

## Projection outcomes

Projection isn't a boolean. A proposition might be successfully projected as a new edge, *adopted*
onto a node that already existed, *aligned* by merging attributes into a match, *skipped* because it
met no projection criteria, or *failed* because something threw. DICE records which of these
happened for every proposition, with a reason for the skips and failures.

The point is that these outcomes mean different things to whatever decides what to re-project later.
"Nothing to do here" and "this broke" look identical if you only track success/failure, and you'd
either retry things that were fine or ignore things that need attention. Distinguishing *adopted*
from *newly projected* also records the reconciliation decision in the lineage, not just in the
graph write.

The reconciler returns one of three decisions — `CreateNew`, `Adopt`, or `Align` — each recorded in the lineage.

```mermaid
flowchart TD
    P[Proposition] --> RECON{"Reconcile against<br/>existing graph"}
    RECON -->|"CreateNew — no match"| NEW[Project new artifact]
    RECON -->|"Adopt — exact match found"| ADOPT[Adopt existing node]
    RECON -->|"Align — merge attrs into match"| ALIGN[Align with existing node]
    P -.->|met no criteria| SKIP[Skipped]
    P -.->|projector threw| FAIL[Failed]
    NEW --> REC[("ProjectionRecord<br/>lineage + authority + outcome")]
    ADOPT --> REC
    ALIGN --> REC
    SKIP --> REC
    FAIL --> REC
```

`CreateNew` creates a fresh artifact in the target backend. `Adopt` reuses an existing artifact verbatim — the proposition's projected identity becomes that node's reference. `Align` is the middle option: the proposition merges attributes into an existing artifact while keeping its own distinct identity (for example, a projector that enriches an existing entity node rather than pointing at it wholesale). The shipped `RepositoryBackedReconciler` uses exact entity-ID match to return `Adopt` or `CreateNew`; `Align` is available for backends that need finer-grained merging.

## SPI extension points for projection

The projectors, the reconciler, and the record stores are all SPIs. Here is how they fit together:

```mermaid
classDiagram
    class GraphProjectionService {
        +projectAndPersist(propositions) Pair
    }
    class GraphProjector {
        +project(proposition, schema) ProjectionResult
        +projectAll(propositions, schema) ProjectionResults
    }
    class Reconciler {
        +reconcile(proposition, target, projected) ReconciliationDecision
    }
    class ReconciliationDecision {
        <<sealed>>
        CreateNew
        Adopt(targetRef)
        Align(targetRef)
    }
    class ProjectionRecordStore {
        +record(record)
        +markStaleByProposition(propositionId)
        +all() List
    }
    class ProjectionRecord {
        +propositionId
        +target
        +targetRef
        +lifecycle ProjectionLifecycle
        +runId
        +at
    }
    class ProjectionLifecycle {
        <<enum>>
        PROJECTED
        ADOPTED
        ALIGNED
        SKIPPED
        FAILED
        STALE
    }
    GraphProjectionService --> GraphProjector : projects
    GraphProjectionService --> Reconciler : delegates reconciliation
    GraphProjectionService --> ProjectionRecordStore : writes outcome
    ProjectionRecordStore --> ProjectionRecord : stores
    ProjectionRecord --> ProjectionLifecycle : lifecycle field
    Reconciler --> ReconciliationDecision : returns
```

The in-memory `InMemoryProjectionRecordStore` and the durable `DrivineProjectionRecordStore` (in
`dice-storage`) both satisfy this SPI — the durable one persists `(:ProjectionRecord)` nodes in
Neo4j so lineage survives a restart.

## Stale-cascade on source change

The graph is downstream of the propositions, so it can fall out of date. When a proposition reaches
a terminal lifecycle state — superseded, contradicted, or stale — a listener marks every projection
record derived from it as stale.

Two deliberate choices live here. First, the trigger is the proposition's *status change*, not a
manual sweep, so the graph self-heals as a side effect of the lifecycle rather than needing a
separate reconciliation job to remember. Second, the cascade only marks the *records* stale; it
doesn't rip out the actual edge. Edge removal or refresh is a re-projection concern — the stale flag
is a signal to downstream consumers that the edge needs a refresh, not the refresh itself, and
keeping the cascade to a fast, idempotent "flag it" step means a status change never triggers
expensive graph surgery inline.

The trigger is the `PropositionStatusChanged` event (see [events](events.md)) — this cascade is the
one place DICE consumes its own events, so nothing has to remember to run it:

```mermaid
sequenceDiagram
    autonumber
    participant Life as A proposition's status changes
    participant Bus as Event bus
    participant Cascade as Stale-cascade listener
    participant Records as Projection records
    Life->>Bus: status becomes superseded / contradicted / stale
    Bus->>Cascade: deliver the change
    Cascade->>Records: markStaleByProposition(propositionId)
```

## Idempotent ingestion and reconciliation

Re-running ingestion or re-projecting a source should be safe and cheap. DICE guards both ends.

At the **front door**, content is dedup'd by hash before any extraction runs. Extraction is an LLM
call; doing it twice on identical content wastes money and mints duplicate propositions. The ledger
claims a content hash atomically so two concurrent ingests of the same artifact can't both proceed —
and if extraction fails, the claim is released so a transient error doesn't permanently block
re-ingestion. (A second, durable layer tracks processed chunks across sessions for the incremental
path.)

At the **graph end**, deduplication is opt-in, not the default. `GraphProjectionService` defaults
to `AlwaysCreateReconciler`, which always returns `CreateNew` and never looks at the live graph —
running it twice over the same content mints a new edge each time. To dedupe, configure a
`RepositoryBackedReconciler`, which checks whether the exact edge already exists before deciding to
adopt it:

```kotlin
val reconciler = RepositoryBackedReconciler(repository = namedEntityDataRepository)
val service = GraphProjectionService(
    graphProjector = graphProjector,
    persister = persister,
    schema = schema,
    recordStore = recordStore,
    reconciler = reconciler,
)
```

The match is exact-ID and deterministic — it would rather create a clean new node than guess a
fuzzy match and merge two things that aren't the same.

The unifying idea is that ingestion and projection are operations you'll run repeatedly over
overlapping material, so they're built to converge rather than accumulate — but for the graph end,
you have to ask for it.

## Backend access through a port

The durable backend is Neo4j, but the core never depends on it directly — it depends on SPIs.
Proposition storage sits behind the `PropositionStore` interface, lineage and other records behind
their own store interfaces, and the entity/relationship axis behind embabel-agent's entity-repository
port. All the Neo4j-specific wiring lives in the storage module; domain code never imports a graph
driver. Because the core talks only to those interfaces, you can swap the backend or test against an
in-memory substitute.

The entity-repository-backed proposition store follows the same "declare only what you really
support" stance as the durable-storage design (see [durable storage](durable-storage.md)): it
exposes plain storage and vector search, not proposition-scoped graph traversal or temporal
queries, because the entity-scoped repository can't genuinely back them.

## Configurable behavior

The reconciler, the projection record store, and the authority resolver are all pluggable. What
ships favours safety — create-new when unsure, record everything, resolve authority from provenance —
so the conservative behaviour is the default and a deployment tightens it where it needs to.
