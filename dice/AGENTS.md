# `dice` module — Agent Navigation Guide

This is the core of DICE. It owns the domain model, every SPI interface, the extraction and revision pipelines, entity resolution, projectors, incremental analysis, in-memory stores, Prolog integration, and optional REST endpoints. The two storage modules depend on this one, not the other way round.

Kotlin 2.1.10, Java 21. `embabel-agent-api` and `embabel-agent-rag-core` are `provided` — the consumer supplies them at runtime.

## Central types

**`Proposition`** (`com.embabel.dice.proposition.Proposition`) — a data class that is the system of record. One proposition should express one fact with at most two entity mentions (SUBJECT and OBJECT). Key fields:
- `contextId` — the primary scope for all queries; use this first
- `text` — the natural language statement
- `confidence` / `decay` / `importance` — three orthogonal scores; call `effectiveConfidence()` for time-decayed ranking
- `contentRevised` — the decay anchor; `metadataRevised` is for administrative touches (status, pin); neither should be confused with the other
- `status` — `ACTIVE`, `SUPERSEDED`, `CONTRADICTED`, `PROMOTED`, `STALE`
- `provenanceEntries` — rich links back to source material; complements the legacy `grounding` list of chunk IDs

**`PropositionStore`** (`com.embabel.dice.proposition.PropositionStore`) — the base persistence SPI: CRUD, `findBy*`, and a composable `query(PropositionQuery)`. The default `query()` implementation filters in memory; persistent backends override to push predicates to the database.

**`PropositionRepository`** (`com.embabel.dice.proposition.PropositionRepository`) — extends `PropositionStore` with opt-in capability fragments:
- `VectorSearchCapable` — `findSimilarWithScores`, `findClusters`
- `GraphTraversalCapable` — traversal helpers over the proposition abstraction hierarchy
- `TemporalQueryCapable` — bitemporal queries
- `GraphQueryCapable` — native neighbourhood, path-between, and why-explain lineage queries over the entity-relationship graph; also declares `honorsAuthorityFilter` so the portable graph facade can route authority-filtered traversals to the native backend rather than falling back to proposition-walk

`CoreSearchOperations` is a separate RAG bridge (vector + text search), not a capability fragment a backend opts into.

**`PropositionQuery`** (`com.embabel.dice.proposition.PropositionQuery`) — composable query spec with filters (contextId, entityId, confidence threshold, importance, status, time windows), ordering (`EFFECTIVE_CONFIDENCE_DESC`, `IMPORTANCE_DESC`, `CREATED_DESC`, etc.), and an optional limit. Always scope queries with a `contextId` to avoid scanning everything.

**`PropositionExtractor`** (`com.embabel.dice.proposition.PropositionExtractor`) — the extraction SPI. `LlmPropositionExtractor` (`com.embabel.dice.proposition.extraction`) is the production implementation; it calls the LLM against a configurable Mustache template.

**`PropositionReviser`** (`com.embabel.dice.proposition.revision.PropositionReviser`) — the revision SPI. `LlmPropositionReviser` uses the LLM to classify new propositions as IDENTICAL / SIMILAR / CONTRADICTORY / GENERALIZES / UNRELATED against existing ones, then merges accordingly.

**`PropositionPipeline`** (`com.embabel.dice.pipeline.PropositionPipeline`) — the orchestrating entry point. Build one with the fluent API (`PropositionPipeline.withExtractor(...).withRevision(...).withMentionFilter(...)`), then call `process(chunks, context)` or `processOnce(text, sourceId, context, historyStore)`.

**`SourceAnalysisContext`** (`com.embabel.dice.common.SourceAnalysisContext`) — carries everything a pipeline run needs: schema (`DataDictionary`), entity resolver, `contextId`, optional known entities and template model. In Kotlin use infix builders; in Java use the `withXxx` builder chain.

## Knowledge hygiene: admission, reclamation, consolidation

Three seams keep the store healthy at three different moments; all ship conservative, pluggable defaults.

**Admission — gates.** `ExtractionGate` (`com.embabel.dice.proposition.gate`) runs on pipeline output *before* the caller persists. `ExtractionGatePipeline` chains gates; the first non-`Persist` decision wins. `StandardGates` ships confidence/trust/conflict/evidence-floor gates; `EvidenceFloorGate` demotes a weak relation rather than dropping the fact.

**Reclamation — the mark-and-sweep collector.** `CollectorRunner`/`DefaultCollectorRunner` (`com.embabel.dice.projection.memory`) fetch ACTIVE candidates, gather marks from each `CollectorStrategy` (stale via `DecayCollectorStrategy`, duplicates via `DuplicateCollectorStrategy`), then let a `SweepPolicy` (in `spi` with `StatusTransitionPolicy`, since deciding a lifecycle fate is a policy seam) decide each one's fate (`TransitionStatus`/`HardDelete`/`Skip`). `run(dryRun = true)` previews without writing; `collect()` marks only. Every run is recorded in a `CollectorRecordStore` for audit.

**Consolidation — the dream loop.** `DreamLoopOrchestrator`/`DefaultDreamLoopOrchestrator` run `ConsolidationPass`es (`operations.consolidation`) as a threshold-gated cycle: `SessionConsolidationPass`, `AbstractionPass` (drives supersession), `ContradictionResolutionPass` (drives contradiction), `DecaySweepPass` (drives stale). The orchestrator owns all writes and locks per `contextId` so cycles for one context never overlap.

These map onto the lifecycle in [proposition-lifecycle](../docs/design/proposition-lifecycle.md); the design notes in [`docs/design/`](../docs/design/) explain the *why* behind each.

## Package map

| Package | Contents |
|---|---|
| `proposition` | `Proposition`, `PropositionStore`, `PropositionRepository`, `PropositionQuery`, `EntityMention`, `DecayManager`, `DecaySweeper`, capability interfaces |
| `proposition.extraction` | `PropositionExtractor`, `LlmPropositionExtractor`, extraction config |
| `proposition.revision` | `PropositionReviser`, `LlmPropositionReviser` |
| `proposition.store` | `InMemoryPropositionRepository`, `JsonFilePropositionRepository`, `InMemoryDecayManager` |
| `proposition.gate` | Admission gates applied to pipeline output before persistence: `ExtractionGate`, `ExtractionGatePipeline`, `StandardGates`, `ObservableGate`, `GateDecision`, `GatedPropositionResult`, `EvidenceFloorGate` |
| `pipeline` | `PropositionPipeline` (two-stage extract→resolve), `PropositionResults`, `PersistablePropositions`; execution strategies: `ExtractionExecutionStrategy` + `SerialExtractionStrategy`/`ParallelExtractionStrategy`/`BatchedExtractionStrategy` |
| `spi` | Policy extension points: `TrustScorer`, `AuthorityResolver`/`AuthorityTier`, `AuthorityWeightedTrustScorer`, `ConflictDetector`, `ConflictType`, `StatusTransitionPolicy`, the mark-and-sweep `SweepPolicy`/`StatusTransitionSweepPolicy` with its `SweepAction`/`PropositionMark`/`MarkReason` vocabulary, and their shipped defaults |
| `common` | `SourceAnalysisContext`, `EntityResolver`, `Relations`, `ContentHasher`, `KnowledgeType`, events, `SchemaAdherence`, validation rules |
| `common.filter` | `MentionFilter`, `SchemaValidatedMentionFilter`, `ObservableMentionFilter`, context-aware filters |
| `common.resolver` | `EscalatingEntityResolver`, `InMemoryEntityResolver`, `KnownEntityResolver`, `ChainedEntityResolver`, `LlmCandidateBakeoff`, `ContextCompressor` |
| `common.resolver.searcher` | `CandidateSearcher` and all implementations: `ByIdCandidateSearcher`, `ByExactNameCandidateSearcher`, `NormalizedNameCandidateSearcher`, `PartialNameCandidateSearcher`, `FuzzyNameCandidateSearcher`, `VectorCandidateSearcher`, `AgenticCandidateSearcher`; `DefaultCandidateSearchers` factory |
| `entity` | `EntityPipeline`, `EntityIncrementalAnalyzer`, `EntityResolutionService`, `EntityResolutionTools`, `LlmEntityExtractor`, result types |
| `incremental` | `AbstractIncrementalAnalyzer`, `ChunkHistoryStore`, `InMemoryChunkHistoryStore`, `ConversationSource`, `IncrementalSource` |
| `incremental.proposition` | `PropositionIncrementalAnalyzer` |
| `projection.graph` | `GraphProjector`, `RelationBasedGraphProjector`, `LlmGraphProjector`, `GraphProjectionService`, `ProjectionPolicy` |
| `projection.memory` | `MemoryProjector`/`DefaultMemoryProjector`, `MemoryRetriever`, `MemoryConsolidator`; **maintenance & dream loop**: `MemoryMaintenanceOrchestrator`/`DefaultMemoryMaintenanceOrchestrator`, `DreamLoopOrchestrator`/`DefaultDreamLoopOrchestrator`, `DreamLoopReport`; **mark-and-sweep collector**: `CollectorRunner`/`DefaultCollectorRunner`, `CollectorStrategy` (`DecayCollectorStrategy`, `DuplicateCollectorStrategy`), `CollectorRunResult` — the `SweepPolicy` and the `SweepAction`/`PropositionMark`/`MarkReason` vocabulary live in `spi` |
| `projection.prolog` | `PrologProjector`, `PrologEngine`, Prolog type wrappers |
| `projection.lineage` | `ProjectionRecordStore`, `InMemoryProjectionRecordStore`, `Reconciler`, `ProjectionRecord`, `ProjectionRun`; **collector audit trail**: `CollectorRecordStore`/`InMemoryCollectorRecordStore`, `CollectorRecord`, `CollectorRun`, `CollectorOutcome` |
| `projection.grounding` | `GroundingResolver`, `GroundingWiringService` |
| `text2graph` | `KnowledgeGraphBuilder`, `SourceAnalyzer`, `LlmSourceAnalyzer`, `MultiPassKnowledgeGraphBuilder`, merge policies, relationship resolution |
| `provenance` | `ProvenanceEntry`, `SourceLocator`, `UriLocator` |
| `query.oracle` | `Oracle`, `LlmOracle`, `PrologTools`, `ToolOracle` |
| `query.graph` | `GraphQuery`, `GraphNeighborhood`, `GraphPath`, `PropositionLineage` — neighbourhood/path/lineage retrieval over the graph |
| `query.discovery` | `RetrievalRouter`, `DiscoveryQuery`, `RetrievalMode`, discovery DTOs — mode-routed retrieval entry point |
| `temporal` | `TemporalMetadata` — bitemporal valid/observed windows, explicit retraction |
| `agent` | `Memory`, `MemoryRetriever` (agent-facing view), `ProvenanceResolver`; **`DiscoveryTools`** — `@LlmTool`-annotated tools wrapping `RetrievalRouter` (query propositions, graph path, why-explain, projection health, collector dry-run) with context baked in at construction so an agent can't cross context boundaries; **`GraphQueryTools`** — `@LlmTool`-annotated tools wrapping the `GraphQuery` facade (entity neighbourhood, path between entities, why-explain) |
| `mcp` | `DiceMcpTools`, `DiceMcpProfile` — simplified MCP/agent tool surface (`dice_recall`, `dice_list`, `dice_store`, `dice_get`, optional `dice_extract` / `dice_assert_entities`) |
| `web.rest` | `PropositionPipelineController`, `MemoryController`, **`DiscoveryController`** — REST surface for discovery operations (`/api/v1/contexts/{contextId}/discovery`; routes query, path, why, projection health, and collector dry-run; context comes from the URL path only), API key security — optional, activated by `spring-webmvc` |
| `operations` | `PropositionAbstractor`, `PropositionContraster` — higher-level proposition management |
| `operations.consolidation` | The dream-loop steps as composable passes: `ConsolidationPass`/`ConsolidationPassResult`, `SessionConsolidationPass`, `AbstractionPass`, `ContradictionResolutionPass`, `DecaySweepPass` |

## Gotchas

- **`contextId` first.** `PropositionQuery` has no `create()` factory on purpose. Always scope with `forContextId`/`againstContext` or `mentioningEntity`. Calling `findAll()` without a context filter can be expensive.
- **Decay clock vs. admin clock.** `contentRevised` is the decay anchor — it resets when text, mentions, or confidence changes. `metadataRevised` records administrative touches (status, pin, grounding, metadata). Helpers like `withStatus()`, `withPinned()`, `withGrounding()`, and `withMetadataValue()` touch only `metadataRevised`. Use `withText()` or `withConfidence()` to reset the decay clock.
- **`PropositionStore` vs. `PropositionRepository`.** Inject `PropositionStore` if you only need basic persistence. Inject `PropositionRepository` when you need vector search, clustering, or the RAG bridge.
- **tuProlog 1.0.4 pin.** The 1.1.x series requires Kotlin 2.2+; `dice` is on 2.1.10. Do not upgrade tuProlog without upgrading Kotlin first.
- **Kotlin `-Xjvm-default=all`.** The Kotlin compiler flag is set in the pom so that default interface methods are accessible from Java. Don't remove it or Java callers will lose default implementations.
- **Java interop on `ContextId`.** `ContextId` is a Kotlin value class. Java code can't construct it directly — use `SourceAnalysisContext.withContextId("my-id")` and read it back with `getContextIdValue()`.
- **Provenance is append-only on `save()`.** `PropositionRepository.save()` never drops provenance entries it didn't load — the safe default for bulk re-saves and decay sweeps. Use `setProvenance()` / `clearProvenance()` to authoritatively replace evidence.
