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
- [extraction-profiles.md](extraction-profiles.md) — carrying a host's content-policy identity
  through extraction without DICE resolving it: why profile identity is opaque, why profile,
  perspective, schema and tenant stay independent, and why an extraction run reference shipped in
  review and was pulled back out until its consuming write exists. EXPERIMENTAL.
  and a run reference through extraction without DICE resolving either: why profile identity is
  opaque, why the run reference ships ahead of the run, and why profile, perspective, schema and
  tenant stay independent. EXPERIMENTAL.
- [extraction-runs.md](extraction-runs.md) — the durable record of one extraction execution: why
  requested model configuration and observed provider facts are separate types, how invocation
  identity comes from the call plan rather than completion order, the denormalized root run
  reference, the privacy contract on opaque references and sanitized failures, and why replay
  fidelity is never exact. EXPERIMENTAL.

## Propositions & lifecycle

- [proposition-lifecycle.md](proposition-lifecycle.md) — how a proposition earns or loses trust,
  what happens on conflict, supersession, and decay over its life.
- [grounding-and-conflicts.md](grounding-and-conflicts.md) — how propositions stay anchored to
  source material (grounding) and how the conflict/policy SPI resolves disagreements between
  propositions.
- [source-revisions.md](source-revisions.md) — carrying an opaque source version beside a stable
  locator: where the revision sits on the evidence, what it does to equality and dedup, the
  evidence-key codec that makes a fold precisely undoable, and what stays true for evidence that
  carries no revision.

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
  layers, gated by an API-key filter. MCP export (`DiceMcpTools`, `dice-mcp-autoconfigure`) is the
  sibling opt-in for stateless MCP clients; see [architecture.md](architecture.md#expose-agent-tools-rest-and-mcp).
- [report.md](report.md) — `dice-report`'s pure projectors that turn queried propositions into
  human-facing artifacts: structured breakdowns, discovered links, LLM-generated rationale.
- [metamodel-versioning.md](metamodel-versioning.md) — stamping a schema with a content hash so it
  can be compared later: per-type governance, the declared-schema opt-in seam, and the version
  store's accumulating history.
- [metamodel-diff.md](metamodel-diff.md) — comparing stamps: the change taxonomy, including
  property-signature changes where a property keeps its name but changes type or cardinality,
  and the two comparisons it supports, declared against declared and declared against a live
  graph.
- [metamodel-drift.md](metamodel-drift.md) — checking a live graph against a declared schema: the
  runner's declare, stamp, observe, diff, report sequence, the bounds on every read of the drift
  log, and quarantine, which marks affected propositions stale rather than deleting them.
- [metamodel-wiring.md](metamodel-wiring.md) — turning governance on in a Spring Boot host: the
  opt-in is supplying a `DeclaredSchemaSource` bean, the escalation tier is one property, and the
  default tier reports drift without touching any proposition.

## Modules

DICE ships as eight Maven modules; [architecture.md](architecture.md#module-map) has the full
dependency map. Quick pointer to where each is documented:

| Module | Documented in |
| --- | --- |
| `dice` (core) | most notes above — propositions, pipeline, projections, hygiene, retrieval |
| `dice-storage` | [durable-storage.md](durable-storage.md), [graph-projection.md](graph-projection.md), [prolog-projection.md](prolog-projection.md) |
| `dice-storage-autoconfigure` | [durable-storage.md](durable-storage.md), [metamodel-wiring.md](metamodel-wiring.md) |
| `dice-mcp-autoconfigure` | [architecture.md](architecture.md#expose-agent-tools-rest-and-mcp) |
| `dice-ingestion` | [ingestion.md](ingestion.md) |
| `dice-report` | [report.md](report.md) |
| `dice-metamodel` | [metamodel-versioning.md](metamodel-versioning.md), [metamodel-diff.md](metamodel-diff.md), [metamodel-drift.md](metamodel-drift.md), [metamodel-wiring.md](metamodel-wiring.md) |
| `dice-integration-tests` | not separately documented — exercises the above end-to-end |
</content>
