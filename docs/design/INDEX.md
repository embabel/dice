# DICE design docs

Entry point into DICE's design notes. DICE is a proposition-first knowledge substrate: raw text
becomes confidence-weighted natural-language statements (propositions), which are kept healthy
over time and projected into whatever representation a task needs (graph, Prolog, vectors, agent
memory). Start with [architecture](architecture.md) for the big picture, then drop into the theme
you need.

## Architecture & modules

- [architecture.md](architecture.md) — system overview, the six-module map, and how propositions
  flow from ingestion through projection. Read this first.

## Ingestion & extraction

- [ingestion.md](ingestion.md) — `dice-ingestion`'s two jobs: content-hash dedup before
  extraction, and turning artifact text into `Chunk`s the pipeline understands.
- [extraction-pipeline.md](extraction-pipeline.md) — the two-stage pipeline (extract, then
  resolve/reconcile) that turns chunks into grounded propositions; why the stages are separable
  and what "unsaved results" means for the caller.
- [entity-resolution-and-text2graph.md](entity-resolution-and-text2graph.md) — how mentions get
  matched to existing entities (or minted as new ones) without blowing the LLM budget or
  fragmenting the graph with near-duplicates.

## Propositions & lifecycle

- [proposition-lifecycle.md](proposition-lifecycle.md) — how a proposition earns or loses trust,
  what happens on conflict, supersession, and decay over its life.
- [grounding-and-conflicts.md](grounding-and-conflicts.md) — how propositions stay anchored to
  source material (grounding) and how the conflict/policy SPI resolves disagreements between
  propositions.

## Projections

- [graph-projection.md](graph-projection.md) — projecting propositions into a typed Neo4j graph:
  lineage back to evidence, dedup across re-runs, and cleaning up stale structure.
- [prolog-projection.md](prolog-projection.md) — projecting propositions into a Prolog fact base
  for logical/transitive queries that are awkward to express as graph traversals.

## Memory hygiene & consolidation

- [knowledge-hygiene.md](knowledge-hygiene.md) — the umbrella note: why hygiene is three separate
  interventions (admission, reclamation, consolidation) at three different moments, not one.
- [multi-signal-collector.md](multi-signal-collector.md) — pluggable duplicate detection that
  combines multiple signals (similarity, contradiction, shared entities/grounding), not just
  cosine similarity.
- [reclamation-and-collector.md](reclamation-and-collector.md) — reclamation as a tracing garbage
  collector: one stage marks what looks like garbage, another sweeps it, every action is recorded.
- [collector-trace-store.md](collector-trace-store.md) — the `CollectorTraceStore` audit trail
  that makes a collapse/merge decision explainable after the fact.
- [consolidation-and-dream-loop.md](consolidation-and-dream-loop.md) — folding a session's raw
  facts into long-term memory: passes, composition, and the between-session cycle.

## Retrieval & query

- [retrieval-and-discovery.md](retrieval-and-discovery.md) — how propositions come back out:
  vector search, graph walks, temporal windows, trust filtering at read time, and surfacing
  connections nobody explicitly queried for. Ends with why the embedding model is a latency
  decision on the retrieval side, and what a hosted one costs per interactive turn.
- [oracle-and-query.md](oracle-and-query.md) — the layer above retrieval: turning a
  natural-language question into an answer, not just a bag of relevant propositions.

## Storage & platform

- [durable-storage.md](durable-storage.md) — the `PropositionStore` SPI family, the durable Neo4j
  backend, dedup, and the decay tick.
- [events.md](events.md) — the domain events DICE emits (fact persisted, status changed, batch
  finished) and how they loosely couple the substrate to observers.
- [web-api.md](web-api.md) — the opt-in REST surface over the pipeline, memory, and discovery
  layers, gated by an API-key filter.
- [report.md](report.md) — `dice-report`'s pure projectors that turn queried propositions into
  human-facing artifacts: structured breakdowns, discovered links, LLM-generated rationale.
- [metamodel-versioning.md](metamodel-versioning.md) — stamping a schema with a content hash so it
  can be compared later: per-type governance, the declared-schema opt-in seam, and the version
  store's accumulating history.

## Modules

DICE ships as seven Maven modules; [architecture.md](architecture.md#module-map) has the full
dependency map. Quick pointer to where each is documented:

| Module | Documented in |
| --- | --- |
| `dice` (core) | most notes above — propositions, pipeline, projections, hygiene, retrieval |
| `dice-storage` | [durable-storage.md](durable-storage.md), [graph-projection.md](graph-projection.md), [prolog-projection.md](prolog-projection.md) |
| `dice-storage-autoconfigure` | [durable-storage.md](durable-storage.md) |
| `dice-ingestion` | [ingestion.md](ingestion.md) |
| `dice-report` | [report.md](report.md) |
| `dice-metamodel` | [metamodel-versioning.md](metamodel-versioning.md) |
| `dice-integration-tests` | not separately documented — exercises the above end-to-end |
</content>
