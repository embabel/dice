# Events

DICE emits domain events as it works — a fact was persisted, a proposition changed status, a batch
finished — so other parts of a system can react without DICE having to know about them. This note
covers the event model and what the store and pipeline emit; it's the extension point that loosely couples the
substrate to whatever observes it.

## The model

An event is any value implementing `DiceEvent`. A listener is a `DiceEventListener` — a single
`onEvent(event)` method that receives *every* event and decides for itself which ones it cares
about. There's no per-type subscription; it's a plain fan-out.

A few deliberate choices:

- **Synchronous and inline.** Listeners run on the thread that emitted the event, during the
  operation that produced it. Ordering is obvious and events are easy to reason about, with one rule
  for consumers: if a handler is slow, hand the work to your own queue — don't block the write.
- **Opt-in, zero-cost by default.** Emission happens through decorators you wrap around the real
  components and a listener you hand to the pipeline. Wire nothing and the default listener is a
  no-op; you pay nothing until you opt in.
- **Failure-isolated.** `SafeDiceEventListener` wraps a listener so a thrown exception is caught and
  logged rather than aborting the write that emitted the event; `CompositeDiceEventListener` fans
  out to several listeners, each isolated; `LoggingDiceEventListener` just logs. The pipeline wraps
  whatever listener you give it in the safe variant automatically.
- **Advisory.** Events report what happened; they don't drive DICE's own behavior. They exist for
  consumers — audit logs, dashboards, downstream indexes — to observe the substrate.

```mermaid
flowchart LR
    STORE[Proposition store] -->|"PropositionPersisted<br/>PropositionStatusChanged"| L[DiceEventListener]
    PROJ[Projector] -->|ProjectionBatchCompleted| L
    PIPE[Revision pipeline] -->|"PropositionDiscovered<br/>PropositionMerged<br/>PropositionReinforced<br/>PropositionContradicted<br/>PropositionGeneralized<br/>ExtractionBatchCompleted"| L
    L --> C["your consumers:<br/>audit, dashboards, indexes"]
```

## Event taxonomy

```mermaid
flowchart TB
    DE["DiceEvent (marker)"]
    PE["PropositionPersisted<br/>(save — no status change)"]
    SC["PropositionStatusChanged<br/>(previous + new status + reason)"]
    PIN["PropositionPinned<br/>(exempted from decay)"]
    UNP["PropositionUnpinned<br/>(back in scope for decay)"]
    PBC["ProjectionBatchCompleted<br/>(success / skip / fail counts)"]
    EBC["ExtractionBatchCompleted<br/>(run statistics)"]
    PD["PropositionDiscovered<br/>(revision: new fact)"]
    PM["PropositionMerged<br/>(revision: identical)"]
    PR["PropositionReinforced<br/>(revision: similar)"]
    PC["PropositionContradicted<br/>(revision: conflict)"]
    PG["PropositionGeneralized<br/>(revision: generalizes)"]
    REJ["PropositionRejected<br/>(gate: discarded)"]
    RTR["PropositionRoutedToReview<br/>(gate: needs a second look)"]
    SKIP["PropositionProjectionSkipped<br/>(gate: saved but not projected)"]
    DEM["PropositionDemoted<br/>(gate: relation weakened)"]
    DE --> PE
    DE --> SC
    DE --> PIN
    DE --> UNP
    DE --> PBC
    DE --> EBC
    DE --> PD
    DE --> PM
    DE --> PR
    DE --> PC
    DE --> PG
    DE --> REJ
    DE --> RTR
    DE --> SKIP
    DE --> DEM
```

## What the store and pipeline emit

Wrapping a `PropositionStore` in the event-emitting decorator turns persistence into a stream of
events:

- **`PropositionPersisted`** — a fact was saved (a fresh insert, or an update that didn't change
  status).
- **`PropositionStatusChanged`** — a save moved a proposition to a new status; it carries the
  previous and new status and an optional reason. As a hot-path optimization, saving an
  already-`ACTIVE` proposition is reported as `PropositionPersisted` rather than a status change, so
  a revival back to active reads as a persist.

Wrapping a projector in the event-emitting decorator emits **`ProjectionBatchCompleted`** with
success / skip / failure counts after each batch.

The pipeline, when a reviser is configured, emits one event per revision outcome as it reconciles a
new proposition against what's stored — **`PropositionDiscovered`**, **`PropositionMerged`**,
**`PropositionReinforced`**, **`PropositionContradicted`**, **`PropositionGeneralized`** — and an
**`ExtractionBatchCompleted`** with run statistics at the end of a batch. These are pre-persistence
signals about what the reviser decided; the durable record of a save is still `PropositionPersisted`.

## The emitter map

Events aren't limited to the store and pipeline above — several other components emit them too. This is
every emitter in the system today, and what each one puts out:

```mermaid
flowchart LR
    REPO["EventEmittingPropositionRepository<br/>(store decorator)"] -->|"PropositionPersisted<br/>PropositionStatusChanged"| L((DiceEventListener))
    PROJ["EventEmittingProjector<br/>(projection decorator)"] -->|ProjectionBatchCompleted| L
    PIPE["PropositionPipeline<br/>(revision reconciliation)"] -->|"PropositionDiscovered<br/>PropositionMerged<br/>PropositionReinforced<br/>PropositionContradicted<br/>PropositionGeneralized<br/>PropositionRoutedToReview<br/>ExtractionBatchCompleted"| L
    GATE["ObservableGate<br/>(admission gate decorator)"] -->|"PropositionRejected<br/>PropositionRoutedToReview<br/>PropositionProjectionSkipped<br/>PropositionDemoted"| L
    COLLECTOR["DefaultCollectorRunner<br/>(reclamation sweep)"] -->|PropositionStatusChanged| L
    DREAM["ContradictionResolutionPass<br/>(dream-loop consolidation)"] -->|PropositionRoutedToReview| L
    T2G["MultiPassKnowledgeGraphBuilder<br/>(text2graph entity resolution)"] -->|NewEntity| L
    L --> CASCADE["ProjectionLineageStaleCascade<br/>(built-in consumer)"]
    L --> C["your consumers:<br/>audit, dashboards, indexes"]
    CASCADE -.->|"marks ProjectionRecord STALE<br/>on terminal PropositionStatusChanged"| LIN[(ProjectionRecordStore)]
```

Source locations for each emitter: `EventEmittingPropositionRepository`
(`dice/src/main/kotlin/com/embabel/dice/proposition/EventEmittingPropositionRepository.kt`),
`EventEmittingProjector`
(`dice/src/main/kotlin/com/embabel/dice/proposition/EventEmittingProjector.kt`),
`PropositionPipeline`
(`dice/src/main/kotlin/com/embabel/dice/pipeline/PropositionPipeline.kt:269-280,499`),
`ObservableGate`
(`dice/src/main/kotlin/com/embabel/dice/proposition/gate/ObservableGate.kt:72-88`),
`DefaultCollectorRunner`
(`dice/src/main/kotlin/com/embabel/dice/projection/memory/DefaultCollectorRunner.kt:291`),
`ContradictionResolutionPass`
(`dice/src/main/kotlin/com/embabel/dice/operations/consolidation/ContradictionResolutionPass.kt:96`),
`MultiPassKnowledgeGraphBuilder`
(`dice/src/main/kotlin/com/embabel/dice/text2graph/support/MultiPassKnowledgeGraphBuilder.kt:71`).

Two things worth noticing in that map:

- If you need the cause of a `PropositionStatusChanged`, use the `reason` field rather than trying
  to infer it from which emitter fired — it's a comma-joined list of `MarkReason.key` values (e.g.
  `"duplicate"`, or `"stale,duplicate"` when more than one strategy marked the same proposition).
- **`ProjectionLineageStaleCascade` is a listener that is also an internal consumer** — it doesn't
  emit new `DiceEvent`s, but it reacts to `PropositionStatusChanged` by writing to the
  `ProjectionRecordStore` directly. If you're deciding whether your own consumer belongs beside it
  (in-process, synchronous) or as an out-of-process subscriber, the deciding factor is exactly
  what it optimizes for: cascade must run before the sweep that triggered it returns, because lineage
  staleness is part of the same consistency boundary as the status change.

## Wiring

Events are off until you wire them. Wrap the store and projector in their event-emitting decorators,
hand a listener to the pipeline, and combine several listeners with `CompositeDiceEventListener`.
Every event is set up for polymorphic JSON, so a listener can forward them out of process.

```kotlin
val repo = EventEmittingPropositionRepository(
    delegate = inMemoryRepository,
    listener = SafeDiceEventListener(myListener),
)
```

A listener is a one-method fun interface, so a lambda or a class both work:

```kotlin
val myListener = DiceEventListener { event ->
    when (event) {
        is PropositionPersisted -> println("saved: ${event.proposition.id}")
        is PropositionStatusChanged -> println("${event.previousStatus} -> ${event.newStatus}: ${event.reason}")
        else -> Unit
    }
}
```
