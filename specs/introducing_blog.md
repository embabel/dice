# Introducing DICE: Structured Memory for JVM Agents

## Memory is bigger than recall

When people talk about "memory" for AI agents, they usually mean something like human recall — the agent remembers that you like coffee, that you work at Google, that you asked about deployment last Tuesday. This is real and important. But it's only one slice of the memory problem.

Think about the memories your bank has of you. Your transaction history, account relationships, credit decisions, interaction records across channels. Or your telco: usage patterns, plan changes, support tickets, network preferences. These aren't narrative recollections — they're structured data, living in relational databases and CRM systems, with typed entities, audit trails, and business rules. They're *memory* in the fullest sense, and they're far richer than anything a flat fact store can represent.

The AI agent memory systems built so far — Zep, Mem0, LangMem, the managed offerings from Google and AWS — focus almost exclusively on the recall side. They store text strings. They don't connect to your existing entities. They don't integrate with the structured knowledge your organization already has. This creates a strange gap: your agent can remember that "Alice mentioned she's a gold customer" but can't link that to Alice's actual customer record, her transaction history, or her account status.

DICE bridges this gap. It's AI-forward — propositions extracted by LLMs, with confidence scores, decay, contradiction detection, and multi-level abstraction. But it's also systems-integrated — propositions resolve against your existing entities, project into your existing graph databases and inference engines, and query through composable, typed APIs. The result is agent memory that works *with* your existing data, not parallel to it. That combination — structured AI memory integrated with structured enterprise data — is what makes DICE novel, and what makes it useful for production systems rather than demos.

## The flat fact problem

Most AI agent memory systems store flat strings. "Alice likes coffee." "Bob works at Google." These systems treat memory
as a bag of facts — opaque text blobs with no structure, no confidence, no decay, no entity awareness. They work for
demos. They don't work for production systems where your agent needs to *reason* about what it knows.

DICE takes a different approach. Every piece of knowledge is a **proposition** — a structured unit of memory with typed
entity mentions, confidence scores, time-based decay, provenance tracking, and a reinforcement counter. Propositions
aren't just stored; they're revised, merged, contradicted, abstracted, and projected into multiple views. The result is
a memory system that gets *more reliable* as it processes more information, not less.

## Why propositions, not facts?

A "fact" in most memory systems is a string. DICE's proposition carries:

- **Text**: The natural language statement ("Alice is a senior ML engineer at Google")
- **Entity mentions**: Typed, resolved references to real entities — not just substrings, but links to your existing
  entity stores
- **Confidence**: LLM-assessed certainty (0.0–1.0) with hedging detection
- **Decay**: How quickly this becomes stale (0.0 = permanent, 1.0 = very temporary)
- **Effective confidence**: `confidence * exp(-decay * k * age_days)` — a formula from
  the [GUM paper](https://arxiv.org/abs/2505.10831) that demonstrated 76% accuracy overall and 100% for high-confidence
  propositions
- **Grounding**: Which source chunks this was extracted from (full provenance)
- **Reinforcement count**: How many times this has been confirmed across conversations
- **Level**: Abstraction level (0 = raw observation, 1+ = synthesized insight)
- **Source IDs**: For abstractions, which lower-level propositions it was derived from

This isn't just metadata. It changes how memory behaves. A proposition seen three times gets higher confidence. A
proposition contradicted by new evidence gets reduced confidence and accelerated decay. A proposition about someone's
mood fades quickly; a proposition about their profession persists.

## Standing on the shoulders of GUM

DICE's proposition model is directly influenced by the [General User Models (GUM)](https://arxiv.org/abs/2505.10831)
research from Shaikh et al. at Stanford and Microsoft. GUM demonstrated that confidence-weighted propositions —
extracted from unstructured observations, continuously revised through inference and retrieval — can build accurate,
persistent models of user knowledge and preferences. Their system achieved 76% accuracy overall and 100% for
high-confidence propositions, validating the core insight that *structured propositions with calibrated confidence
outperform flat fact stores*.

DICE adopts GUM's three-mechanism architecture — **inference** (extract propositions from input), **retrieval** (surface
related propositions for context), and **revision** (update existing propositions against new evidence) — and extends it
in several directions:

- **Entity resolution**: GUM extracts propositions from screenshots and text. DICE adds a seven-strategy entity
  resolution pipeline that links mentions to existing domain entities, grounding propositions in your application's
  real-world model.
- **Exponential time decay**: GUM tracks confidence but doesn't model temporal staleness. DICE's decay parameter and
  effective confidence formula (`confidence * exp(-decay * k * age_days)`) mean that transient facts like current mood
  naturally fade while stable facts like professional role persist.
- **Multi-projection architecture**: GUM targets a single user model. DICE projects the same propositions into knowledge
  graphs, Prolog inference engines, agent memory systems, and vector search — making the proposition a universal
  knowledge primitive.
- **Five-way classification**: GUM's revision mechanism updates propositions. DICE's classification adds nuance —
  IDENTICAL vs SIMILAR vs CONTRADICTORY vs UNRELATED vs GENERALIZES — with outcome-dependent confidence adjustments for
  each.
- **Batch processing and cost optimization**: GUM processes observations individually. DICE batches classification
  calls, uses algorithmic fast paths to skip the LLM entirely for high-similarity matches, and supports tiered models (
  cheaper LLM for classification, more capable for extraction).

We're grateful to the GUM team for demonstrating that proposition-based user modeling works — and works well. DICE is
our attempt to take that insight and build production infrastructure around it for the JVM ecosystem.

## Integrate with your existing entities

Most memory systems create their own entity model from scratch. DICE does something different: it **resolves mentions
against your existing entities**.

The entity resolution pipeline uses an escalating chain of seven strategies, ordered cheapest-first with early stopping:

1. **ID lookup** — instant, for known entity IDs
2. **Exact name match** — string equality
3. **Normalized name** — strips titles ("Dr.", "Jr.") and matches
4. **Partial name** — "Brahms" matches "Johannes Brahms"
5. **Fuzzy name** — Levenshtein distance for typos and variations
6. **Vector similarity** — embedding-based search for semantic matches
7. **Agentic search** — LLM-driven candidate selection as a last resort

Most resolutions complete at steps 1–3. The expensive LLM call only fires when heuristics can't resolve the entity. And
the resolver works against any `NamedEntityDataRepository` implementation — it doesn't care whether your entities live
in Neo4j, a relational database, an in-memory store, or a microservice.

This means DICE doesn't create a parallel entity universe. If your application already has a `Customer` entity or a
`Product` catalog, DICE resolves mentions against them directly. The propositions it creates reference *your* entities,
not copies.

## Built for the JVM

DICE is the only proposition-based memory system built natively for the JVM. Every competitor — Zep, Mem0, LangMem,
Google Vertex Memory Bank, AWS Bedrock AgentCore Memory — is Python-first (Zep also Go, the managed services are
API-only).

If your agents run on Spring Boot, Kotlin, or Java, DICE is a library dependency, not an infrastructure deployment:

```java

@Bean
PropositionPipeline propositionPipeline(
        PropositionExtractor extractor,
        PropositionReviser reviser,
        PropositionRepository repository) {
    return PropositionPipeline
            .withExtractor(extractor)
            .withRevision(reviser, repository);
}
```

Spring auto-configuration wires REST endpoints, API key authentication, and controller beans when the right components
are present. But Spring is optional — DICE's core has no framework dependency.

The API is designed for both Kotlin and Java:

```java
// Java builder pattern
PropositionQuery query = PropositionQuery.againstContext("session-123")
                .withEntityId("alice-123")
                .withMinEffectiveConfidence(0.5)
                .orderedByEffectiveConfidence()
                .withLimit(20);
```

```kotlin
// Kotlin infix notation
val query = PropositionQuery forContextId sessionContext
```

## Five-way classification, not three operations

When new information arrives, it has to be reconciled against what's already known. Most systems offer three operations:
add, update, delete. DICE classifies every new proposition against existing ones using a five-way taxonomy:

| Classification    | Meaning                      | Result                              |
|-------------------|------------------------------|-------------------------------------|
| **IDENTICAL**     | Same fact, different wording | Merge: boost confidence, slow decay |
| **SIMILAR**       | Same core fact, same entity  | Reinforce: modest confidence boost  |
| **CONTRADICTORY** | Directly conflicts           | Reduce old confidence, keep both    |
| **UNRELATED**     | Different topics             | Store as new                        |
| **GENERALIZES**   | Abstracts existing facts     | Link as higher-level insight        |

The critical distinction: DICE **retains contradicted propositions** with reduced confidence rather than deleting them.
If your agent learns "Alice is 30" and later hears "Alice is 35", both exist in memory — the old one with suppressed
confidence and accelerated decay. This preserves audit trail and allows the system to recover if the contradiction was
actually noise.

## Three layers of cost optimization

LLM calls are the dominant cost in any memory pipeline. DICE uses three layers to minimize them:

**Layer 1: Algorithmic fast paths** (zero LLM cost)

- Canonical text dedup catches exact matches before vector search
- Auto-merge at embedding similarity >= 0.95 skips classification entirely
- Entity-overlap pre-filter eliminates candidates that share no entities with the new proposition — a cheap set
  intersection that prevents the LLM from classifying obviously unrelated pairs

**Layer 2: Batching** (amortized cost)

- Multiple propositions classified in a single LLM call instead of one call per proposition
- Configurable batch size (default 15) to balance latency and throughput

**Layer 3: Model tiering** (reduced per-call cost)

- Classification uses a separate, cheaper LLM from extraction — it's a structured categorization task (pick from 5
  labels) that doesn't need the most capable model
- Entity resolution escalates through seven strategies before reaching the expensive LLM bakeoff

In practice, these layers combine to eliminate 70–80% of what would otherwise be LLM classification calls.

## One source of truth, multiple projections

Propositions are the canonical representation. From that single source of truth, DICE projects into multiple views:

### Knowledge graph

Neo4j's [LLM Graph Builder](https://neo4j.com/labs/genai-ecosystem/llm-graph-builder/) demonstrated the power of using
LLMs to construct knowledge graphs from unstructured text — and Neo4j remains an excellent graph database for this kind
of work. DICE's graph projection builds on this idea, but with a key difference: not every application can or should
adopt a graph database as its primary store. Many production systems have existing entities in relational databases,
document stores, or microservices. DICE projects to graphs *when you want them* without requiring them.

The `RelationBasedGraphProjector` converts propositions into typed relationships. If a proposition says "Alice works at
Google" with SUBJECT mention "Alice" (Person) and OBJECT mention "Google" (Company), the projector creates a `WORKS_AT`
relationship in Neo4j — or any graph backend that implements `GraphRelationshipPersister`.

Relationship types come from your domain schema (`@Semantics` annotations), a `Relations` collection, or property name
derivation. The projector respects confidence thresholds — low-confidence propositions don't get projected to the graph
until they're reinforced.

### Prolog inference

The `PrologProjector` compiles propositions into Prolog facts for logical reasoning. If you need transitive closure ("
Alice knows Bob, Bob knows Charlie, therefore Alice is connected to Charlie"), Prolog rules handle it without additional
LLM calls.

### Agent memory

The `MemoryProjector` classifies propositions into four cognitive categories drawn from the cognitive science taxonomy
established by [Tulving (1972)](https://psycnet.apa.org/record/1973-08477-007)
and [Squire (1992)](https://www.pnas.org/content/93/24/13515):

- **Semantic**: Stable facts ("Alice works at Google") — Tulving's semantic memory: general knowledge independent of
  temporal context
- **Episodic**: Events with temporal context ("Alice met Bob yesterday") — Tulving's episodic memory: autobiographical
  events anchored in time and place
- **Procedural**: Preferences and patterns ("Alice prefers morning meetings") — Squire's nondeclarative memory: learned
  behaviors and habits
- **Working**: Transient session context ("The user just asked about deployment") — transient, active processing context

These aren't arbitrary labels. The distinction between semantic and episodic memory has been foundational in cognitive
science since Tulving first proposed it in 1972, and Squire's later work established the broader
declarative/nondeclarative taxonomy that includes procedural memory. DICE's classification preserves these distinctions
because they affect how memory should be retrieved — stable facts are always relevant, episodic memories need temporal
proximity, procedural knowledge surfaces when behavioral patterns are useful, and working memory is session-scoped.

The `Memory` tool provides two-tier retrieval for LLM agents:

1. **Eager loading**: Key memories injected directly into the system prompt at zero latency
2. **On-demand search**: The LLM calls a tool to search for additional memories when needed, with automatic
   deduplication against eagerly loaded ones

### Vector search

Every `PropositionRepository` supports similarity search. The `InMemoryPropositionRepository` uses your existing
`EmbeddingService` — no separate vector database required. DICE integrates with [Embabel](https://embabel.com)'s RAG
module, which provides a portable abstraction across vector stores, relational databases, and other backends — so your
proposition storage isn't locked to any single infrastructure choice.

## Multi-level abstraction with provenance

Raw observations accumulate. Over time, you want higher-level insights. DICE's abstraction system synthesizes them:

- Level 0: "Bob prefers morning meetings", "Bob likes detailed documentation", "Bob reviews PRs thoroughly"
- Level 1: "Bob values thoroughness and clarity in work processes"

The abstraction links back to its sources via `sourceIds`. You can navigate the hierarchy in both directions — find what
sources an abstraction was derived from, or find what abstractions cite a given observation. When source propositions
decay or get contradicted, you know which abstractions may need revision.

The `PropositionContraster` does the same across entity groups: "Alice prefers Python while Bob prefers Java" — with
full provenance tracking back to the individual observations.

## Incremental processing for conversations

Agent memory typically accumulates from conversations — a stream of messages, not a batch of documents. DICE's
`IncrementalAnalyzer` handles this with windowed processing:

- Configurable window size and overlap for context continuity
- Trigger-based extraction (every N messages)
- Content hashing prevents reprocessing identical windows
- Cross-chunk entity deduplication via in-memory resolver

For a chatbot processing ongoing conversations, this means extraction happens automatically at configurable intervals,
with built-in dedup so the same information doesn't get extracted twice from overlapping windows.

## Composable queries

`PropositionQuery` provides a composable query DSL:

```kotlin
// What does Alice know? (high-confidence, recent, semantic facts)
val aliceKnowledge = repository.query(
    PropositionQuery.mentioningEntity("alice-123")
        .withStatus(PropositionStatus.ACTIVE)
        .withMinEffectiveConfidence(0.5)
        .orderedByEffectiveConfidence()
        .withLimit(20)
)

// What's been reinforced the most? (most frequently confirmed facts)
val strongestFacts = repository.query(
    PropositionQuery.againstContext(sessionId)
        .withMinReinforceCount(3)
        .orderedByReinforceCount()
)

// What changed this week?
val recentChanges = repository.query(
    PropositionQuery.againstContext(sessionId)
        .revisedSince(Duration.ofDays(7))
        .orderedByRevised()
)
```

Filter by context, entity (single, any-of, all-of), status, abstraction level, time range, effective confidence (with
decay applied at query time), and reinforcement count. Order by confidence, recency, or reinforcement frequency.

## How DICE compares

We've done deep analysis of every major agent memory system. Here's where things stand:

| Capability              | DICE             | Zep                   | Mem0         | LangMem      | Google Vertex | AWS Bedrock  |
|-------------------------|------------------|-----------------------|--------------|--------------|---------------|--------------|
| Structured propositions | Yes              | Edges                 | Flat facts   | Flat facts   | Flat facts    | Flat facts   |
| Entity resolution       | 7-strategy chain | Neo4j nodes           | Graph nodes  | None         | None          | None         |
| Confidence + decay      | GUM-based model  | None                  | None         | Prompts only | None          | None         |
| Classification nuance   | 5-way            | Binary                | 4-op         | 3 tool calls | 3-outcome     | 3-op         |
| Contradiction retention | Both kept        | Temporal invalidation | Hard delete  | None         | Hard delete   | No detection |
| Multi-level abstraction | Yes              | None                  | None         | None         | None          | None         |
| Graph projection        | Neo4j, Prolog    | Built-in (Neo4j)      | Neo4j/others | None         | None          | None         |
| Batch classification    | N in 1 call      | Sequential            | Sequential   | Sequential   | Opaque        | Opaque       |
| JVM native              | Yes              | No (Python/Go)        | No (Python)  | No (Python)  | No (GCP API)  | No (AWS API) |
| Self-hosted             | Yes              | Yes (heavy)           | Yes          | Yes          | No            | No           |

DICE's core advantage isn't any single feature — it's the interaction between structured propositions, escalating entity
resolution, confidence-weighted revision, and multiple projection targets. The proposition is the unit of knowledge, and
everything else derives from it.

## Getting started

DICE is a library. Add the dependency, configure an `EmbeddingService` and an LLM, and build a pipeline:

```kotlin
// 1. Define your domain schema
val schema = DataDictionary.fromClasses("myapp", Customer::class.java, Product::class.java)

// 2. Set up extraction
val extractor = LlmPropositionExtractor
    .withLlm(LlmOptions("gpt-4.1-mini"))
    .withAi(ai)
    .withSchemaAdherence(SchemaAdherence.DEFAULT)

// 3. Set up revision (dedup + classification)
val reviser = LlmPropositionReviser
    .withLlm(LlmOptions("gpt-4.1-mini"))
    .withAi(ai)
    .withClassifyLlm(LlmOptions("gpt-4.1-nano"))  // cheaper model for classification

// 4. Build the pipeline
val pipeline = PropositionPipeline
    .withExtractor(extractor)
    .withRevision(reviser, repository)

// 5. Process text
val result = pipeline.processChunk(chunk, context)
// result.propositions: extracted and resolved propositions
// result.revisionResults: how each was classified against existing knowledge
```

From there, project to a graph, build agent memory, run Prolog queries, or just search by similarity. The propositions
are yours — DICE doesn't lock you into any particular consumption pattern.

---

*DICE is part of the [Embabel](https://embabel.com) platform for building intelligent JVM applications.*
