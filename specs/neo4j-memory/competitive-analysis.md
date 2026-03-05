# Neo4j Agent Memory — Competitive Analysis

**Repository**: https://github.com/neo4j-labs/agent-memory
**Package**: `neo4j-agent-memory` (PyPI, v0.0.4)
**Status**: Neo4j Labs — experimental, no SLAs, no backward compatibility guarantees
**License**: Apache 2.0
**Created**: January 2026
**Language**: Python only

## Overview

Neo4j Agent Memory is a graph-native memory system for AI agents, built by Neo4j Labs. It stores conversations, builds knowledge graphs, and captures reasoning traces — all backed by Neo4j's property graph database.

It implements a **three-tier cognitive memory model**:
1. **Short-Term Memory** — episodic/conversation history within sessions
2. **Long-Term Memory** — entities, facts, preferences, and relationships (POLE+O ontology)
3. **Reasoning Memory** — decision traces, tool call tracking, agent self-improvement

## Architecture

```
BaseMemory (orchestrator)
  ├── Neo4jClient (persistence via Cypher)
  ├── Embedder (pluggable: OpenAI, Bedrock, Vertex AI, Sentence Transformers)
  └── EntityExtractor
       ├── ExtractionPipeline (spaCy → GLiNER → LLM cascade)
       ├── CompositeResolver (exact → fuzzy → semantic dedup)
       └── Enrichment (Wikipedia/Diffbot background tasks)
```

## Key Technical Features

### Entity Extraction Pipeline
Multi-stage cascade: spaCy (fast/free) → GLiNER (zero-shot NER) → LLM (high accuracy). Five merge strategies for combining results: UNION, INTERSECTION, CONFIDENCE, CASCADE, FIRST_SUCCESS.

### POLE+O Data Model
Person, Object, Location, Event, Organization with subtypes (e.g., Person → Individual/Alias/Suspect/Witness/Victim). Entities get dynamic Neo4j labels.

### Entity Resolution
Chained resolvers: exact match → fuzzy (RapidFuzz) → semantic (embedding similarity). Type-aware guards prevent cross-type conflation. Auto-merge at similarity >= 0.95, flag for review at 0.85-0.95.

### Reasoning Memory
Captures complete decision-making workflows as `ReasoningTrace` objects. Hierarchical: Trace → Steps → ToolCalls. Pre-aggregated tool statistics (success rates, avg execution times). `get_similar_traces()` enables learning from past problem-solving.

### Graph Features
- Vector indexes (1536 dims, Neo4j 5.11+) on messages, entities, preferences, facts, reasoning traces
- Point indexes for geospatial queries on Location entities
- Temporal facts with valid_from / valid_until windows
- Provenance tracking: EXTRACTED_FROM and EXTRACTED_BY relationships
- Multi-hop graph traversal (configurable up to 3 hops)

### Integration Breadth
9 framework adapters: LangChain, LlamaIndex, Pydantic AI, CrewAI, Google ADK, AWS Strands, Microsoft Agent Framework, OpenAI Agents, AgentCore. Also provides an MCP server with 6 tools.

## Proposition Equivalent: The `Fact` Model

Their closest analog to DICE's proposition is **`Fact`** — an RDF-style subject-predicate-object triple:

```python
class Fact(MemoryEntry):
    subject: str
    predicate: str
    object: str
    confidence: float        # static, set once at creation
    source_id: UUID          # single source link
    valid_from: datetime
    valid_until: datetime
```

All long-term memory types (Entity, Fact, Preference, Relationship) inherit from `MemoryEntry`, which provides `id`, `created_at`, `updated_at`, `embedding`, and generic `metadata`.

### Fact vs Proposition Comparison

| | DICE Proposition | Neo4j Fact |
|---|---|---|
| **Structure** | Arbitrary structured knowledge claim | RDF subject-predicate-object triple |
| **Confidence** | Dynamic — decays over time, adjusted by outcome (contradicted +0.15, merged *0.7, reinforced *0.85) | Static — set once at creation, never updated |
| **Reinforcement** | `reinforceCount` boosted on re-observation via MERGE | None — uses `CREATE` not `MERGE`, so duplicate observations accumulate as separate nodes |
| **Contradiction** | Detected via 5-way classification, both retained with reduced confidence | None — no contradiction detection, facts pile up |
| **Decay** | Exponential time decay from GUM paper | Hard `valid_until` cutoff only — no gradual decay |
| **Provenance** | Grounding chain linking to source chunks | Single `source_id` field, no chain |
| **Graph connectivity** | Connected to entities, sources, other propositions | **Island nodes** — not connected to entities, messages, or extractors via any relationship |
| **Abstraction** | Multi-level (level 0 = raw, level 1+ = synthesized) with source tracking | Flat — all facts at same level |
| **Classification** | IDENTICAL/SIMILAR/CONTRADICTORY/UNRELATED/GENERALIZES with edge-case guidance | No classification taxonomy |
| **Observation tracking** | Count, timestamps, access patterns | None |

### The Island Node Problem

Facts are **island nodes** in their Neo4j graph. Unlike Entities — which participate in rich provenance (`EXTRACTED_FROM` → Message with position/context, `EXTRACTED_BY` → Extractor), deduplication (`SAME_AS` with confidence/match_type/status), and inter-entity relationships (`RELATED_TO`, `MENTIONS`) — Facts exist as standalone triples retrieved only via vector similarity search or subject string lookup.

Their sophisticated lifecycle management is reserved for **Entities**, not Facts. This reveals the fundamental architectural choice: Neo4j Agent Memory is an **entity-centric** system with a bolted-on triple store. DICE is a **proposition-centric** epistemics system where knowledge claims are first-class objects with full lifecycle management.

Notably, even their Entity model lacks key proposition features — no decay, no reinforcement counting, no contradiction detection. The `confidence` field on Entities is updated only during entity resolution merges (taking the higher confidence), not through any lifecycle process.

## DICE vs Neo4j Agent Memory

| Dimension | DICE | Neo4j Agent Memory | Edge |
|---|---|---|---|
| **Data model** | Proposition-based with confidence, decay, reinforceCount, grounding chain | POLE+O entity ontology with relationships, facts, preferences | Different models — Neo4j is entity-centric, DICE is proposition-centric |
| **Classification nuance** | 5-way (IDENTICAL/SIMILAR/CONTRADICTORY/UNRELATED/GENERALIZES) with edge-case guidance + few-shot | No explicit classification taxonomy — entity resolution handles dedup | **DICE** |
| **Batch processing** | N propositions in 1 LLM call via `classifyBatch()` | Sequential entity extraction (though extraction pipeline stages run in cascade) | **DICE** |
| **Auto-merge fast path** | Embedding >= 0.95 skips LLM entirely | Similar threshold (>= 0.95 auto-merge) for entity resolution | Tie |
| **Confidence/decay** | Exponential decay from GUM paper + outcome-dependent adjustment | No confidence decay model — entities are binary (exist or merged) | **DICE** |
| **Contradiction handling** | Both propositions retained with reduced confidence | No explicit contradiction detection — entities merged or left distinct | **DICE** |
| **Abstraction hierarchy** | Multi-level propositions (level 0 = raw, level 1+ = synthesized) | Flat — all entities/facts at same level | **DICE** |
| **Extraction pipeline** | Single LLM call, SNR-maximizing, confidence-qualified | Multi-stage cascade (spaCy → GLiNER → LLM) with 5 merge strategies | **Neo4j** — more flexible, lower cost for simple entities |
| **Entity resolution** | Fuzzy name, vector, exact, partial, agentic; LLM disambiguation | Exact → fuzzy (RapidFuzz) → semantic; type-aware guards | Tie — different strengths |
| **Graph structure** | Propositions + entity mentions, Neo4j as projection | Full native Neo4j knowledge graph with POLE+O ontology | **Neo4j** |
| **Temporal model** | System timestamps only (created/revised) | Facts with valid_from/valid_until windows | **Neo4j** |
| **Geospatial** | None | Radius and bounding-box queries via Neo4j Point indexes | **Neo4j** |
| **Reasoning traces** | Not applicable | Full trace → step → tool call hierarchy with aggregated tool stats | **Neo4j** |
| **Retrieval** | Vector similarity + canonical match + entity-based + composable PropositionQuery | Hybrid vector + graph traversal (up to 3 hops) | **Neo4j** |
| **Memory types** | Unified proposition model with KnowledgeType classifier | Three explicit types: short-term, long-term, reasoning | **Neo4j** — clearer separation |
| **Provenance** | Grounding chain links propositions to source chunks | EXTRACTED_FROM/EXTRACTED_BY relationships to source messages and extractors | Tie |
| **Entity enrichment** | None | Background Wikipedia/Diffbot integration | **Neo4j** |
| **Infrastructure weight** | Embeddable JVM library, no external deps | Requires Neo4j 5.11+, plus optional spaCy/GLiNER models | **DICE** |
| **Language/ecosystem** | JVM/Kotlin/Spring native | Python only | Depends on stack |
| **Framework integrations** | Spring/JVM native | 9 frameworks + MCP server | **Neo4j** — breadth |
| **Maturity** | Pre-release | v0.0.4, experimental Labs project, 36 open issues | Both early |
| **Observability** | Application-managed | OpenTelemetry + Opik built-in | **Neo4j** |

## Their Moat

1. **Native graph database**: Neo4j's property graph gives first-class entity relationships, multi-hop traversal, and community detection that flat stores can't match. This is their fundamental architectural advantage.
2. **Multi-stage extraction cascade**: spaCy → GLiNER → LLM gives cost/quality tradeoffs per entity — cheap NER for obvious entities, LLM only when needed. DICE uses LLM for everything.
3. **POLE+O ontology**: Structured entity typing with subtypes provides domain-level modeling (Person → Suspect/Witness/Victim). Rich for law enforcement, intelligence, and investigation domains.
4. **Reasoning memory**: Capturing agent decision traces with tool call statistics and similar-trace retrieval is unique. Enables agent self-improvement.
5. **Integration breadth**: 9 framework adapters + MCP server covers virtually every major agent framework.
6. **Geospatial and temporal**: Location-based queries and temporal fact windows are uncommon features.

## Their Weakness

1. **Neo4j hard dependency**: Requires Neo4j 5.11+ with vector index support. Significant infrastructure commitment compared to DICE's embeddable library model.
2. **No classification taxonomy**: Entity resolution handles dedup, but there's no explicit classification of how new information relates to existing knowledge (SIMILAR vs CONTRADICTORY vs GENERALIZES). New entities either merge or stay distinct — nuance is lost.
3. **No confidence decay**: Entities are binary — they exist or are merged. No mechanism for information to age, lose confidence, or be weighted by outcome. Everything is equally weighted forever.
4. **No abstraction hierarchy**: All entities/facts/preferences live at the same level. No ability to synthesize higher-level insights from groups of lower-level facts.
5. **No batch classification**: Extraction pipeline processes entities sequentially through cascade stages. No mechanism to classify N items in a single LLM call.
6. **No contradiction retention**: When entities merge, the old information is absorbed. No mechanism to retain both sides of a contradiction with reduced confidence.
7. **Python only**: No JVM, JavaScript, or other language SDKs.
8. **Experimental status**: Neo4j Labs project — no SLAs, no backward compatibility guarantees, API may change without notice.
9. **Heavy dependency tree**: Full install requires Neo4j + spaCy models + GLiNER models + embedding provider. Complex operational footprint.

## Attack Angle

Neo4j Agent Memory is the most architecturally sophisticated competitor to DICE — both systems take knowledge representation seriously, unlike the flat-fact models of Google Memory Bank, AWS AgentCore, and Microsoft Foundry.

However, the competition is **proposition-centric vs entity-centric**:

- **Neo4j Agent Memory** excels at building and querying a knowledge graph of entities and their relationships. It's ideal when the goal is "what do we know about entity X and how does it relate to entity Y?"
- **DICE** excels at managing the lifecycle of individual knowledge claims — how they evolve, conflict, reinforce, and decay over time. It's ideal when the goal is "what do we believe with what confidence, and how has that changed?"

**Key differentiators to emphasize**:

1. **Knowledge lifecycle management**: DICE's 5-way classification, confidence decay, outcome-dependent adjustment, and contradiction retention provide a fundamentally richer model for how knowledge evolves. Neo4j Agent Memory has no equivalent — entities are static once created.

2. **Batch efficiency**: DICE's `classifyBatch()` processes N propositions in 1 LLM call. Neo4j's extraction pipeline cascades through stages per-entity.

3. **Embeddable, no infrastructure**: DICE is a JVM library with no external database dependency. Neo4j Agent Memory requires a running Neo4j 5.11+ instance.

4. **Abstraction synthesis**: DICE's multi-level proposition hierarchy enables emergent higher-level insights. Neo4j's entities are all flat.

**What to learn from them**:

1. **Multi-stage extraction cascade**: Their spaCy → GLiNER → LLM pipeline is cost-effective. Consider whether DICE could benefit from a cheap pre-filter before LLM extraction.
2. **Reasoning traces**: Capturing agent decision workflows with tool statistics is valuable. Worth considering as a projection type.
3. **Geospatial/temporal facts**: Their valid_from/valid_until on facts and Point index queries are concrete implementations of temporal anchoring (GAP-4A/4B).
4. **MCP server**: Exposing memory as MCP tools is a clean integration pattern.
5. **Entity enrichment**: Background Wikipedia/Diffbot enrichment could enhance entity resolution quality.

## Summary Position

Neo4j Agent Memory is a strong entry that validates the graph-based approach to agent memory. But it focuses on **building a knowledge graph** while DICE focuses on **managing knowledge lifecycle**. These are complementary concerns — and DICE's proposition model with confidence decay, batch classification, contradiction retention, and abstraction hierarchy addresses the harder problem of how knowledge evolves over time. Neo4j Agent Memory treats entities as static facts in a graph; DICE treats propositions as living claims that strengthen, weaken, and interact.

The Neo4j dependency is also a significant strategic weakness. DICE already projects to Neo4j when graph structure is needed, but doesn't require it. Being embeddable with no infrastructure requirements is a major advantage for adoption.
