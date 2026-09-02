# DICE competitive positioning

Survey date: mid-2026. Zep/Graphiti, Mem0, Letta (MemGPT), Cognee, Hindsight, LangMem, Neo4j agent
memory (labs SDK and NAMS), Google Vertex AI Memory Bank, AWS Bedrock AgentCore Memory, Microsoft
Foundry. Several of them publish roadmap intent and shipped behaviour in the same posts, so
re-verify a claim before reusing it.

## What DICE is

DICE is Domain-Integrated Context Engineering. It uses a domain model to structure the context an
LLM reads, and applies the same structure to what the LLM produces (README.md:24-36).

Natural language propositions are the system of record. Everything else derives from them: a Neo4j
graph, a Prolog fact base, vector embeddings, agent working memory, and reports (README.md:48-92,
`docs/design/architecture.md`).

A proposition carries:

- `confidence` and `importance`, each a `ZeroToOne`
  (`dice/src/main/kotlin/com/embabel/dice/proposition/Proposition.kt:101-103`).
- `decay`. Effective confidence falls exponentially with age, anchored at the later of
  `contentRevised` and `lastAccessed`, so using a claim refreshes it (`Proposition.kt:358-403`).
- A status: ACTIVE, SUPERSEDED, CONTRADICTED, PROMOTED, STALE. Reinforcement lifts a STALE
  proposition back to ACTIVE (`Proposition.kt:31-54`).
- `grounding`. References to the source chunks the claim was extracted from.
- `provenanceEntries`. Typed source locator, chunk id, character offsets, content hash
  (`dice/src/main/kotlin/com/embabel/dice/provenance/ProvenanceEntry.kt:18-52`).
- `reinforceCount`. How often the claim has been re-observed (`Proposition.kt:118`).

The confidence-weighted proposition and the exponential decay formula come from "Creating General
User Models from Computer Use" (Shaikh, Sapkota, Rizvi, Horvitz, Park, Yang, Bernstein;
arXiv:2505.10831, UIST 2025, ACM DOI 10.1145/3746059.3747722), cited in the README beside the decay
maths. GUM is user-modelling research.

## Unit of knowledge

| System | Unit of knowledge | What the unit carries |
|---|---|---|
| **Zep / Graphiti** | Episodes ingested into a temporal knowledge graph of entity nodes and edges | Bi-temporal edges: `valid_from`/`valid_until` for world time plus ingestion time; entity and edge attributes typed by Pydantic models; cross-session entity dedup |
| **Mem0** | Extracted facts, short natural language strings | Full SQLite change history (old value, new value, event, actor), mention counts. No confidence |
| **Letta (MemGPT)** | Memory blocks in core context (label, value, limit, description) and archival passages in Postgres/pgvector | Free text. The agent decides promotion and conflict resolution through tool calls |
| **Cognee** | LLM-extracted nodes and RDF triples | An `ontology_valid` flag from matching against a declared OWL/RDF ontology. No confidence or decay |
| **Neo4j agent-memory (labs SDK, NAMS)** | Entity and fact nodes, plus `(Message)` nodes chained by `[:NEXT]` per session | POLE+O typing, `valid_from`/`valid_until`, geospatial attributes |
| **LangMem** | Semantic, episodic and procedural memory items | Optional typed Pydantic profiles; consolidation state |
| **Hindsight** | Structured facts in a knowledge graph | Entity resolution that links "Alice" to "my coworker Alice" |
| **Google / AWS / Microsoft** | Extracted memories as flat fact strings or items | A strategy or topic label and an IAM scope |
| **DICE** | Proposition | Confidence, importance, decay, status, grounding, provenance entries, reinforce count |

Three of those units carry a field DICE's proposition lacks.

- **Bi-temporal edges (Zep / Graphiti).** World time is stored separately from ingestion time.
  DICE's `TemporalMetadata` carries `observedAt`, `validFrom`, `validTo` and `invalidatedAt`, with
  no transaction-time axis, so a query for what the store believed at a past instant has no basis.
- **Ontology grounding (Cognee).** Extracted entity and type names resolve against a declared
  OWL/RDF ontology, with fuzzy matching, before any graph node is built
  (https://docs.cognee.ai/core-concepts/ontologies). DICE declares types in a `DataDictionary` and
  applies them at extraction, with no resolution against a stored ontology.
- **Change history (Mem0).** Old value, new value, event and actor per change. DICE records why a
  collapse or merge decision was made
  (`dice/src/main/kotlin/com/embabel/dice/spi/CollectorSignals.kt:115`) and where a claim came from
  (`ProvenanceEntry`). Neither carries an extraction-run identifier, so a stored claim has no link
  back to the run that produced it.

## Projections

Every DICE projection derives from the one proposition set and can be rebuilt from it, so adding a
representation means adding a projector: `dice-storage` for the Neo4j graph, the Prolog fact base
and lineage, `dice/src/main/kotlin/com/embabel/dice/projection/memory/MemoryProjector.kt:46` for
agent working memory, `dice-report` for rationale and structured reports.

| System | Where knowledge lives | Retrieval |
|---|---|---|
| **Zep / Graphiti** | Neo4j property graph, with Leiden community clustering and summaries over it | Cosine plus BM25 plus BFS, with five reranking strategies |
| **Mem0** | A vector store, plus a separate graph pipeline over Neo4j, Memgraph, Neptune or Kuzu | Vector search; the graph pipeline runs its own extraction path |
| **Letta** | pgvector passages | Agent tool calls |
| **Cognee** | RDF-grounded graph plus embeddings | Search returns nodes |
| **Neo4j agent memory** | Native property graph | Hybrid vector plus up to 3-hop traversal |
| **LangMem** | Embedding space | Dilated-window retrieval |
| **Google / AWS / Microsoft** | Stored memories, with no exposed intermediate model | Similarity search |
| **DICE** | Propositions, projected into Neo4j, Prolog, vectors, memory and reports | `RetrievalRouter` over vector, entity, graph walk, temporal and hybrid modes |

In every surveyed system the store is the knowledge model, so a second representation costs a second
extraction path or a migration. Mem0's graph pipeline is such a second representation, fed by its
own extraction over the source material.

## Knowledge hygiene

DICE splits hygiene into three interventions at three moments (`docs/design/knowledge-hygiene.md`).

- **Admission.** Gates run at extraction: confidence qualification, deduplication against canonical
  and stored propositions, conflict classification, trust scoring. Gating costs less than removing
  junk later, and a low-confidence fact is easiest to judge while its extraction context is close.
- **Reclamation.** `DecaySweepPass` retires softly to STALE and never hard-deletes, with
  dual-threshold hysteresis: stale below 0.1 effective confidence, recovery edge at 0.25
  (`dice/src/main/kotlin/com/embabel/dice/operations/consolidation/DecaySweepPass.kt:63-64`).
  Revival happens only on reinforcement.
- **Consolidation.** Between-session passes. `ContradictionResolutionPass` retires the weaker of a
  contradictory pair to CONTRADICTED, auto-merge collapses duplicates, abstraction synthesises
  higher-level propositions from groups.

The four managed services also form memory off the request path.

| Service | Background formation |
|---|---|
| Bedrock AgentCore | Extraction then consolidation as background processes, with start and completion marks and success counts in the logs ([metrics](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/observability-memory-metrics.html)) |
| Vertex AI Memory Bank | Asynchronous extraction, ingested via `add_memory()` or an end-of-conversation callback ([announcement](https://cloud.google.com/blog/products/ai-machine-learning/vertex-ai-memory-bank-in-public-preview), 2025-07-08) |
| LangMem | Hot-path tools or background managers ([writeup](https://rywalker.com/research/langmem)) |
| Neo4j | Background enrichment and a multi-stage extraction cascade ([labs](https://neo4j.com/labs/agent-memory/)) |

No surveyed system documents a hard TTL delete. Decay, supersession and consolidation are the common
answer.

Two DICE hygiene mechanisms are absent from every other system surveyed: a lifecycle status on the
stored unit, and a retirement path that keeps the retired unit.

- Vertex AI Memory Bank deletes the superseded memory on contradiction.
- Microsoft Foundry discards the old value.
- Bedrock AgentCore writes a new entry, with no contradiction detection.
- Mem0 v3 (April 2026) is ADD-only, so supersession and contradiction are inexpressible in the model.
- Cognee keeps its non-conforming nodes. They are "stored, embedded, and returned by search exactly
  like grounded ones" (https://docs.cognee.ai/core-concepts/ontologies), so the flag has no effect
  on reads.

## Schema governance

Declaring a schema for extraction is common. What happens to the declaration varies.

| System | Declaration | Enforcement |
|---|---|---|
| **Graphiti** | Pydantic entity and edge models; `set_ontology` with a `strict_ontology` flag | "Each entity is validated against the appropriate Pydantic model" before graph construction ([docs](https://help.getzep.com/graphiti/core-concepts/custom-entity-and-edge-types)) |
| **Cognee** | An OWL/RDF ontology file | Checked before any graph node is built. Content that fails to match is kept, tagged `ontology_valid=False`: "Nothing is rejected or discarded" ([docs](https://docs.cognee.ai/core-concepts/ontologies)) |
| **Neo4j labs SDK / NAMS** | `DomainSchema` of entity types and descriptions | Steers GLiNER extraction. No validation, versioning or rejection documented ([faq](https://neo4j.com/labs/agent-memory/faq/)) |
| **Bedrock AgentCore** | Output schema, self-managed strategy only | Built-in and overridden strategies "do not let you change the final output schema" ([docs](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/memory-custom-strategy.html)) |
| **Vertex Memory Bank / Mem0** | "Custom topics" and "custom categories": a label plus free-text prompt instructions, few-shot examples recommended | Prompt text, no type enforcement ([google](https://cloud.google.com/agent-builder/agent-engine/memory-bank/generate-memories); [mem0](https://docs.mem0.ai/open-source/features/custom-fact-extraction-prompt)) |
| **Letta** | None. Memory blocks are free-text segments | None |
| **Neo4j GRAPH TYPE** (general database feature, Cypher 25 preview in 2026.02, Enterprise, Infinigraph and all Aura tiers) | Nodes, labels and relationship connections via `SET`/`ADD`/`ALTER`/`DROP` | Hard-rejects a non-conforming write at write time ([blog](https://neo4j.com/blog/developer/graph-type-schema-enforcement-made-easy-preview/)) |
| **DICE** | A `DataDictionary` from embabel-agent (`com.embabel.agent.core.DataDictionary`), held by name in `SchemaRegistry` (`dice/src/main/kotlin/com/embabel/dice/common/SchemaRegistry.kt`) | `SchemaAdherence` over `entities` and `predicates` flags, with STRICT, DEFAULT and RELAXED presets (`dice/src/main/kotlin/com/embabel/dice/common/SchemaAdherence.kt:26-49`) |

GRAPH TYPE is the strongest enforcement found, and it sits outside Neo4j's agent-memory product,
where `DomainSchema` remains unenforced extraction guidance. `SHOW CURRENT GRAPH TYPE` returns the
declaration, with no comparison against what the graph holds.

Two capabilities are absent from every system surveyed, DICE included. No system gives an extraction
schema a version identity or history: Graphiti evolves a schema by adding attributes with no version
numbers, GRAPH TYPE's `ADD`/`ALTER`/`DROP` leave no discrete version record, and neither a DICE
declaration nor a stored proposition carries a version stamp. No system compares a declaration
against what a live store holds. No surveyed vendor has published roadmap intent for either.

## Shared mechanisms

Places where DICE and a surveyed system reached the same solution independently, beyond the decay
and consolidation overlap above.

- **LLM extraction into a structured store.** All four surveyed memory services take a conversation
  turn, run an LLM extraction, and persist the result into a vector, graph or relational store
  (cited under knowledge hygiene above, plus
  https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/memory.html). DICE's pipeline has the
  same shape, with the proposition as the stored unit.
- **Integer re-indexing of proposition IDs across LLM calls.** DICE and Mem0 both renumber IDs per
  call so the model cannot invent one.
- **Confidence-qualified, SNR-shaped extraction prompts.** DICE and LangMem arrived at the same
  prompt controls independently (https://rywalker.com/research/langmem).

## Remaining gaps

Capabilities DICE lacks, against the four managed memory services: LangMem, Vertex AI Memory Bank,
Bedrock AgentCore Memory, Neo4j Agent Memory. The count column says how many of the four ship it.

| Gap | Count | Evidence | DICE today |
|---|---|---|---|
| Operational tooling and memory inspection | 4 | Bedrock emits CloudWatch metrics for latency, invocations, errors and memory creation count, with spans over CreateEvent, GetEvent, ListEvents, DeleteEvent and RetrieveMemoryRecords, plus extraction and consolidation logs ([metrics](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/observability-memory-metrics.html)). Vertex lists stored memories in the Cloud Console (Memory Bank UI announcement, Google Developer Forums, no stable URL recorded). The NAMS dashboard shows health, entity count and queue lag with graph visualisation and Cypher queryability ([tour](https://medium.com/neo4j/a-tour-of-the-neo4j-agent-memory-service-nams-0f2d535a4fdb), 2026-06, GA unconfirmed). LangSmith traces execution paths and state transitions with cost, latency and error dashboards (langchain.com/resources/llm-observability-tools) | "Why was this proposition formed" needs custom logging over `CollectorTraceStore` |
| Background memory formation pipelines | 4 | Formation runs off the request path, cited under knowledge hygiene above | Async `@EventListener` on conversation analysis, a non-blocking `PropositionIncrementalAnalyzer` (`dice/src/main/kotlin/com/embabel/dice/incremental/proposition/PropositionIncrementalAnalyzer.kt`), and consolidation passes an application invokes. The scheduler that runs them is missing |
| TTL and eviction controls | 0 publish a TTL API | The AWS TTL setting and Google's retention policy are unverified | Decay rate multiplier defaults to 2.0 (`Proposition.kt:365`); sweep thresholds default to 0.1 and 0.25 (`DecaySweepPass.kt:63-64`). All are constructor parameters, so an operator has no retention policy to set |
| Procedural memory | 2 | LangMem's `procedural` type lets agents update their own prompt rules from feedback ([writeup](https://rywalker.com/research/langmem)). Neo4j stores tool usage and reasoning traces with similarity search over trace lineage ([labs](https://neo4j.com/labs/agent-memory/)). AWS and Google extract facts only | Prolog projection over propositions as a view layer, with no rule-formation path |
| Namespacing and scoping APIs | 2 | AWS uses hierarchical namespaces for fine-grained access control plus session headers `Mcp-Session-Id` and `X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` ([memory](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/memory.html)). Neo4j chains `(Message)` nodes by `[:NEXT]` per session, with entity and fact nodes shared across sessions ([labs](https://neo4j.com/labs/agent-memory/)). Google scopes by identity and LangMem by embedding space, neither with a namespace API | `ContextId` is a query filter over shared storage (README.md:1238-1256). It scopes reads. The storage layer stays shared |
| Cross-session profile modelling | 4 aggregate, 0 publish a profile schema | AWS extracts user preferences, facts and session summaries across sessions. Google personalises long-term memories per user ([announcement](https://cloud.google.com/blog/products/ai-machine-learning/vertex-ai-memory-bank-in-public-preview), 2025-07-08). Neo4j persists preference and fact entities in a shared graph ([labs](https://neo4j.com/labs/agent-memory/)). LangMem keeps preferences in semantic memory with optional typed Pydantic profiles ([writeup](https://rywalker.com/research/langmem)) | `ContextId` scopes knowledge and defines no profile structure or cross-session aggregation |

Absent from DICE and from every surveyed system: an extraction-run identifier linking a stored claim
to the run and schema that produced it; temporal anchoring, so relative dates are stored as literal
text; surprise-prioritised retention, so novel facts get no durability preference; versioning or an
audit trail on conflict policy.

Absent from DICE and present in Zep/Graphiti: a transaction-time axis, so DICE has no point-in-time
query over past belief and no temporal contradiction resolution.

## Candidate modules

Each gap above consumes propositions and adds no field to the proposition model, so each can ship as
a separate module over the DICE state the table records.

**Working memory.** A bounded active set held in a protected prompt region, ranked by an activation
score blending recency, reinforcement and decay, evicted into durable storage so evicted material
stays queryable. DICE supplies storage, extraction, decay, revision and pinning
(`dice/src/main/kotlin/com/embabel/dice/proposition/PropositionStore.kt:234-241`) as primitives. The
module adds four SPIs:

- activation ranking as a pluggable policy;
- authority and trust levels with a promotion gate;
- per-turn lifecycle events for reinforce, evict and reactivate;
- a token and unit budget over the resident set.

**Session memory management.** Session-scoped active-context assembly and summarisation over
`MemoryProjector`, which classifies propositions by knowledge type for prompt injection
(`MemoryProjector.kt:46`), and the `Memory` tool, which runs hybrid vector plus keyword retrieval
over context-scoped propositions (`dice/src/main/kotlin/com/embabel/dice/agent/Memory.kt:112`).

**Background consolidation.** A scheduler over the existing passes, the
extraction-then-consolidation staging AWS and Google run, and lifecycle logging that makes a run
inspectable.

**Procedural memory.** A formation path over the existing Prolog projection, turning agent feedback
into stored rules that later runs read back.

## Orthogonal research

Mechanisms from non-LLM fields that map onto problems DICE has.

| Field | Mechanism | DICE analog | Citation |
|---|---|---|---|
| Truth maintenance (TMS/ATMS) | Justifications record why a belief holds; retracting a premise un-derives its dependents; ATMS labels a node with the assumption sets that support it | `ContradictionResolutionPass` retires the weaker of a contradictory pair to CONTRADICTED by comparing `effectiveConfidence()`, with no dependency record (`dice/src/main/kotlin/com/embabel/dice/operations/consolidation/ContradictionResolutionPass.kt:84-87`) | Doyle, "A Truth Maintenance System," *Artificial Intelligence* 12(3), 1979; de Kleer, "An Assumption-Based TMS," *Artificial Intelligence* 28, 1986 |
| AGM belief revision | Revision and contraction obey minimal-change postulates over a selection function | Decay adjustments (contradiction +0.15, merge ×0.7, reinforcement ×0.85) are hand-tuned constants (`dice/src/main/kotlin/com/embabel/dice/proposition/revision/LlmPropositionReviser.kt:528,670,694`) | Alchourrón, Gärdenfors, Makinson, "On the Logic of Theory Change," *J. Symbolic Logic* 50, 1985 |
| Provenance semirings | Provenance of a derived fact is a semiring expression over source tokens, composed under the query's + and × operators | `ProvenanceEntry` is a flat per-proposition list with no algebra for merge or abstraction | Green, Karvounarakis, Tannen, "Provenance Semirings," PODS 2007, DOI 10.1145/1265530.1265535 |
| Bitemporal databases | Valid time and transaction time as orthogonal axes, with defined as-of and point-in-time queries | `TemporalMetadata` carries `observedAt`/`validFrom`/`validTo`/`invalidatedAt` and no transaction-time axis | Snodgrass, *Developing Time-Oriented Database Applications in SQL*, Morgan Kaufmann, 1999 |
| Record linkage | Fellegi-Sunter match decisions from per-field m/u agreement probabilities against a likelihood-ratio threshold | Entity resolution uses fuzzy, vector, exact, partial and agentic searchers with LLM disambiguation | Fellegi, Sunter, "A Theory for Record Linkage," *JASA* 64, 1969, DOI 10.1080/01621459.1969.10501049 |
| ACT-R declarative memory | Base-level activation `B_i = ln(Σ_j t_j^-d)` folds recency and frequency into one retrieval score | `effectiveConfidence()` decays on recency alone. `reinforceCount` sits outside the decay maths, and a working-memory module needs both signals to rank on | Anderson & Schooler, "Reflections of the Environment in Memory," *Psychological Science* 2, 1991 |
| Argumentation frameworks | Arguments plus an attack relation; admissible, preferred and grounded semantics decide which sets survive collectively | `ContradictionResolutionPass` is pairwise strongest-wins, with pinned propositions branched out into a review event | Dung, "On the Acceptability of Arguments...," *Artificial Intelligence* 77(2), 1995 |

Three borrowing opportunities:

- **ATMS justification tracking, to make CONTRADICTED reversible.** The status flip follows a
  confidence comparison at classification time and records no reason for the loss. A justification
  set per proposition lets retracting the evidence un-derive the dependent status.
- **ACT-R base-level activation, to unify `reinforceCount` and decay.** Both signals exist and never
  combine. The activation equation is a closed form for the ranking score a working-memory module
  needs, and for decay that counts frequency of use.
- **Semiring-formalised provenance composition.** Treat auto-merge as the union-like operator and
  abstraction synthesis (which requires all its sources) as the product-like one. That gives one
  queryable answer to "what composed this fact" across both passes.

AGM's postulates cover logical theories and DICE's beliefs are graded, so AGM serves as a checklist.
LLM disambiguation already covers what Fellegi-Sunter scoring would add. The first two opportunities
touch `Proposition.kt` and `ContradictionResolutionPass.kt`, which consolidation, projection and
retrieval ranking all depend on.

## Open questions

- **Which working-memory capabilities belong in DICE and which in the consuming application.**
  Recommendation: activation ranking and per-turn lifecycle events in DICE, prompt-region and budget
  policy in the application, since only the application knows its token budget.
- **Whether to close the schema version-identity gap.** Recommendation: treat it as a candidate
  module. No surveyed system offers it and no surveyed vendor has published roadmap intent, so
  nothing external forces the timing.
- **Whether entity resolution can run concurrently in a working-memory module.** Recommendation: keep
  it serial where shared identity is involved, matching the pipeline's serial resolution stage
  (`docs/design/architecture.md`).

## Out of scope

- **Zep's five-reranker retrieval.** Deep feature tied to Neo4j traversal. The bi-temporal model is
  the better investment from that system.
- **LangMem's prompt optimisation.** Orthogonal to memory quality.
- **Mem0's separate graph pipeline.** DICE has entity mentions plus a Neo4j projection over the same
  propositions.
- **Google's multimodal extraction.** The proposition model is format-agnostic. Add on demand.
- **AWS's episodic reflection.** The abstraction pipeline already synthesises across propositions.
- **Neo4j's POLE+O ontology.** Domain-specific subtypes. The proposition model is domain-agnostic.
- **Neo4j's spaCy to GLiNER to LLM cascade.** Cost-effective and operationally heavy: model
  downloads and dependency management.
- **Contract YAML as the authoring surface.** DICE's declared schema is a JVM type an application
  owns. A YAML dialect would be a second source of truth.
- **Managed hosting.** The embeddable library is the distribution model.
