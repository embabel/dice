# dice-integration-tests

This is a test-only module. It contains no production code — only cross-feature end-to-end tests that wire the real shipped components together and confirm the full knowledge flow works correctly from ingestion through to reporting. Run it when you want confidence that modules compose correctly, not just that each module works in isolation.

## How to run

```
mvn test -pl dice-integration-tests
```

No Docker or live Neo4j is required. All tests run offline against deterministic fixtures and in-memory doubles. The module declares a Testcontainers/Neo4j test dependency for infrastructure that is wired up in the test harness, but the current concrete subclasses use offline in-memory doubles, not a live container.

## What's in here

**`CanonicalFlowFixtures`** — the shared fixture dataset. Three ACTIVE propositions forming an alice–bob–carol–dana chain: `prop-alice-bob` and `prop-bob-carol` (confidence 0.95, decay 0.0) plus `prop-decay-candidate` (Carol–Dana, confidence 0.2, decay 0.9). The low-utility candidate is intentionally designed to be swept by a decay collector. Entity ids are `entity-alice`, `entity-bob`, `entity-carol`, `entity-dana`.

**`AbstractCanonicalFlowTest`** — the TCK base class. Subclasses supply a `PropositionRepository` implementation via `newStore()` and inherit one comprehensive test that drives seven stages in sequence:

1. **Ingest** — `TextIngestionHandler` + `FixedPropositionExtractor` (no LLM) ingests the fixture batch; propositions land in the store.
2. **Project** — `RelationBasedGraphProjector` (AI-free, predicate-matching) projects edges into an `InMemoryGraphRelationshipPersister`; exactly the two high-confidence edges persist, the decay candidate is skipped; `InMemoryProjectionRecordStore` captures `PROJECTED` / `SKIPPED` lineage records.
3. **Query** — `GraphQuery` facade verifies neighborhood, path, lineage (`whyExplain`), and vector similarity (via `FixedVectorEmbeddingService`).
4. **Semantic links** — `TwoHopSemanticLinkDiscoverer` finds alice↔carol via bob and bob↔dana via carol.
5. **Collector sweep** — `DefaultCollectorRunner` + `DecayCollectorStrategy` transitions the decay candidate to `STALE`.
6. **Events** — a `RecordingListener` confirms the runner emitted a `PropositionStatusChanged` for the swept proposition.
7. **Report** — `StructuredReportProjector` confirms total count, status grouping, and confidence-ordered ranking.

**Concrete subclasses**

- `InMemoryCanonicalFlowTest` — runs the full TCK against `InMemoryPropositionRepository`. No dependencies beyond the JVM. Always runs in CI.
- `Neo4jAdapterCanonicalFlowTest` — runs the same TCK against `RagAdapterPropositionRepository`, wired with an `InMemoryPropositionRepository` as its backing CRUD store and an `InMemoryNamedEntityDataRepository` for the entity axis. A thin `TckPropositionRepositoryBridge` re-declares `GraphTraversalCapable` and `TemporalQueryCapable` so the adapter satisfies the TCK's type without modification. Also runs fully offline.

**`CollectorSweepStalesProjectionRecordTest`** — a focused cross-module test that proves the lifecycle→projection lineage cascade. A decay sweep transitions the decay candidate to STALE; `ProjectionLineageStaleCascade` (installed as the runner's listener) flips the seeded `ProjectionRecord` to `STALE`. Confirms both the emitted event and the record mutation in one test.

**`IngestionLedgerDedupE2ETest`** — proves the deduplication contract end-to-end: identical content submitted twice does not re-extract and does not duplicate propositions in the store. Also covers intra-batch dedup and mixed-batch behavior (new + repeat artifact in one batch).

## Adding a new store adapter to the TCK

Create a subclass of `AbstractCanonicalFlowTest`, override `newStore()` to return a fresh empty instance of your store, and all seven canonical-flow stages run automatically. Override `newEmbeddingService()` only if your store needs a different offline embedder.
