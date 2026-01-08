![Build](https://github.com/embabel/dice/actions/workflows/maven.yml/badge.svg)
![Incubating](https://img.shields.io/badge/status-incubating-yellow)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

<img align="left" src="https://github.com/embabel/embabel-agent/blob/main/embabel-agent-api/images/315px-Meister_der_Weltenchronik_001.jpg?raw=true" width="180">

&nbsp;&nbsp;&nbsp;&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;

# Embabel DICE

Knowledge graph construction and reasoning library with proposition-based architecture and Prolog inference.

<p align="center">
  <img src="images/dice.jpg" alt="DICE - Domain-Integrated Context Engineering" width="400">
</p>

<p align="center"><strong>Domain-Integrated Context Engineering</strong></p>

## What is DICE?

**DICE (Domain-Integrated Context Engineering)** extends context engineering by emphasizing the importance of a domain
model to structure context, and considering LLM outputs as well as inputs.

> Despite their seductive ability to work with natural language, LLMs become safer to use the more we add structure to
> inputs and outputs. DICE helps LLMs converse in the established language of our business and applications.
>
> Domain objects are not mere structs. They not only provide typing, but define focused behaviour. In an agentic system,
> behaviour can be exposed to manually authored code and selectively exposed to LLMs as tools.
>
> — [Context Engineering Needs Domain Understanding](https://medium.com/@springrod/context-engineering-needs-domain-understanding-b4387e8e4bf8)

### Benefits of Domain Integration

| Benefit                | Description                                                        |
|------------------------|--------------------------------------------------------------------|
| **Structured Context** | Use code to fill the context window—less delicate, more scientific |
| **System Integration** | Precisely integrate with existing systems using domain objects     |
| **Reuse**              | Domain models capture business understanding across agents         |
| **Persistence**        | Structured query via SQL, Cypher, Prolog—not just vector search    |
| **Testability**        | Structure and encapsulation facilitate testing                     |
| **Observability**      | Debuggers and tracing tools understand typed objects               |

## Architecture Overview

DICE uses a **proposition-based architecture** inspired by
the [General User Models (GUM)](https://arxiv.org/abs/2505.10831) research from Stanford/Microsoft. Like GUM, it
constructs confidence-weighted propositions that capture knowledge and preferences through a pipeline of Propose,
Retrieve, and Revise operations.

Natural language propositions are the system of record. They accumulate evidence and project to multiple typed views for
different use cases.

```
                         +-----------------------+
                         |     TEXT / CHUNKS     |
                         +-----------------------+
                                    |
                                    v
                         +-----------------------+
                         |  PROPOSITION PIPELINE |
                         |  (LLM Extraction)     |
                         +-----------------------+
                                    |
                                    v
                         +-----------------------+
                         |    PROPOSITIONS       |
                         |  (System of Record)   |
                         +-----------------------+
                                    |
         +-------------+------------+------------+-------------+
         |             |            |            |             |
         v             v            v            v             v
    +--------+    +--------+   +--------+   +--------+   +----------+
    | VECTOR |    | NEO4J  |   | PROLOG |   | MEMORY |   |  ORACLE  |
    +--------+    +--------+   +--------+   +--------+   +----------+
         |             |            |            |             |
    Semantic      Graph        Inference     Agent        Natural
    Retrieval     Traversal    & Rules       Context      Language QA
```

## Key Features

### Proposition Pipeline

- **Extraction**: LLM extracts typed propositions from text with confidence and decay scores
- **Entity Resolution**: Mentions resolve to canonical entity IDs
- **Evidence Accumulation**: Multiple observations reinforce or contradict propositions
- **Revision**: Merge identical, reinforce similar, contradict conflicting propositions
- **Promotion**: High-confidence propositions project to typed backends

```mermaid
flowchart TB
    subgraph Extraction["1️⃣ Extraction"]
        Text["📄 Source Text"] --> LLM["🤖 LLM Extractor"]
        LLM --> Props["Propositions<br/>+ confidence<br/>+ decay"]
    end

    subgraph Resolution["2️⃣ Entity Resolution"]
        Props --> ER["Entity Resolver"]
        ER --> Resolved["Resolved Mentions<br/>→ canonical IDs"]
    end

    subgraph Revision["3️⃣ Revision"]
        Resolved --> Similar["Find Similar<br/>(vector search)"]
        Similar --> Classify["LLM Classify"]

        Classify --> Identical["🔄 IDENTICAL<br/>merge, boost confidence"]
        Classify --> SimilarR["🔗 SIMILAR<br/>reinforce existing"]
        Classify --> Contra["⚡ CONTRADICTORY<br/>reduce old confidence"]
        Classify --> General["📊 GENERALIZES<br/>abstracts existing"]
        Classify --> Unrel["✨ UNRELATED<br/>add as new"]
    end

    subgraph Persist["4️⃣ Persistence"]
        Identical --> Repo[("PropositionRepository")]
        SimilarR --> Repo
        Contra --> Repo
        General --> Repo
        Unrel --> Repo
    end

    style Extraction fill:#e8dcf4,stroke:#9f77cd,color:#1e1e1e
    style Resolution fill:#d4eeff,stroke:#63c0f5,color:#1e1e1e
    style Revision fill:#fff3cd,stroke:#e9b306,color:#1e1e1e
    style Persist fill:#d4f5d4,stroke:#3fd73c,color:#1e1e1e
```

### Source Analysis Context

All DICE operations require a `SourceAnalysisContext` that carries configuration for source analysis:

| Property         | Description                                                          |
|------------------|----------------------------------------------------------------------|
| `schema`         | `DataDictionary` defining valid entity and relationship types        |
| `entityResolver` | Strategy for resolving entity mentions to canonical IDs              |
| `contextId`      | Identifies the source/purpose of the analysis (session, batch, etc.) |
| `knownEntities`  | Optional list of pre-defined entities to assist disambiguation       |
| `templateModel`  | Optional model data passed to LLM prompt templates                   |

### ContextId: The Starting Point for All Queries

The `ContextId` is a Kotlin value class that tags all propositions extracted during
a processing run. **ContextId is the primary scoping mechanism for all proposition queries**
and should be considered the starting point when retrieving knowledge.

| Scoping Pattern | Description | Example |
|-----------------|-------------|---------|
| **User-specific context** | Each user has their own context | `ContextId("user-alice-123")` |
| **Shared context** | Multiple users share knowledge | `ContextId("team-engineering")` |
| **Session context** | Per-conversation knowledge | `ContextId("session-abc")` |
| **Batch context** | Processing run grouping | `ContextId("batch-2025-01-09")` |

**Key design points:**

- One user can have **multiple contexts** (personal, team, project-specific)
- One context can be **shared between users** (team knowledge, organizational facts)
- ContextId is independent of entity identity—an entity like "Alice" can appear in many contexts
- Query by contextId first, then refine with entity, confidence, or temporal filters

```kotlin
// Create context for a processing run
val context = SourceAnalysisContext(
    schema = DataDictionary.fromClasses(Person::class.java, Company::class.java),
    entityResolver = AlwaysCreateEntityResolver,
    contextId = ContextId("user-session-123"),
)

// Process chunks with context
val result = pipeline.process(chunks, context)
```

> **Java Interop**: Since `ContextId` is a Kotlin value class, Java code should use the
> strongly-typed builder pattern and access the context ID via `getContextIdValue()`:
> ```java
> SourceAnalysisContext context = SourceAnalysisContext
>     .withContextId("my-context")
>     .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
>     .withSchema(DataDictionary.fromClasses(Person.class))
>     .withKnownEntities(knownEntities)  // optional
>     .withTemplateModel(templateModel); // optional
> ```

### PropositionQuery: Composable Repository Queries

`PropositionQuery` provides a composable, Java-friendly builder pattern for querying propositions.
It consolidates filtering, ordering, and limiting into a single specification object.

```mermaid
flowchart LR
    subgraph Filters["Filters"]
        CTX["contextId<br/>(primary scope)"]
        ENT["entityId<br/>(mentions entity)"]
        CONF["minEffectiveConfidence<br/>(with decay)"]
        TIME["createdAfter/Before<br/>revisedAfter/Before"]
        LVL["minLevel/maxLevel<br/>(abstraction)"]
        STAT["status<br/>(ACTIVE, etc.)"]
    end

    subgraph Order["Ordering"]
        ORD["orderBy<br/>EFFECTIVE_CONFIDENCE_DESC<br/>CREATED_DESC<br/>REVISED_DESC"]
    end

    subgraph Limit["Limiting"]
        LIM["limit<br/>(max results)"]
    end

    Filters --> Order --> Limit --> Results[("Propositions")]

    style Filters fill:#d4eeff,stroke:#63c0f5,color:#1e1e1e
    style Order fill:#fff3cd,stroke:#e9b306,color:#1e1e1e
    style Limit fill:#d4f5d4,stroke:#3fd73c,color:#1e1e1e
```

**Kotlin usage** (infix factory methods + direct construction):

```kotlin
// Query by context using infix notation (the primary scope)
val contextProps = repository.query(
    PropositionQuery forContextId sessionContext
)

// Query with multiple filters using direct construction
val query = PropositionQuery(
    contextId = sessionContext,
    entityId = "alice-123",
    minEffectiveConfidence = 0.5,
    orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC,
    limit = 20,
)
val results = repository.query(query)

// Infix with entity
val entityProps = repository.query(
    PropositionQuery mentioningEntity "alice-123"
)
```

**Java usage** (builder pattern via withers):

```java
// Start with factory method, chain withers
PropositionQuery query = PropositionQuery.againstContext("session-123")
    .withEntityId("alice-123")
    .withMinEffectiveConfidence(0.5)
    .orderedByEffectiveConfidence()
    .withLimit(20);

List<Proposition> results = repository.query(query);
```

**Factory methods** (all are `infix` for Kotlin):

| Method | Description |
|--------|-------------|
| `PropositionQuery.forContextId(contextId)` | Scoped to a ContextId |
| `PropositionQuery.againstContext(contextIdValue)` | Scoped to a context (Java-friendly, takes String) |
| `PropositionQuery.mentioningEntity(entityId)` | Propositions mentioning an entity |

> **Note**: There is no `create()` method by design—always start with a scoped query
> to avoid accidentally fetching all propositions.

**Effective confidence** applies time-based decay to confidence scores, so older propositions
with high decay rates rank lower than recent ones. This is useful for ranking memories by
relevance rather than just raw confidence.

### Content Ingestion Pipeline

The `ContentIngestionPipeline` provides a unified interface for processing any content that can yield propositions.
Content must implement `ProposableContent`, which requires a `contextId`:

```kotlin
// Create content with context
val content = SimpleContent(
    contextId = ContextId("batch-2025-01-05"),
    sourceId = "doc-123",
    context = "Alice works at Acme Corp as a senior engineer."
)

// Create pipeline
val pipeline = ContentIngestionPipeline.create(ai, repository, "gum_propose")

// Process content (contextId flows through automatically)
val result = pipeline.process(content)

// Persist entities and propositions to storage
result.persist(propositionRepository, entityRepository)

// Access revision results
result.revisionResults.forEach { revisionResult ->
    when (revisionResult) {
        is RevisionResult.New -> println("New: ${revisionResult.proposition.text}")
        is RevisionResult.Merged -> println("Merged: ${revisionResult.revised.text}")
        is RevisionResult.Reinforced -> println("Reinforced: ${revisionResult.revised.text}")
        is RevisionResult.Contradicted -> println("Contradicted: ${revisionResult.original.text}")
    }
}
```

> **Note**: The pipeline does not persist automatically. You must call `persist()` to save
> entities and propositions to your repositories. This gives you full control over when
> and whether to commit extracted data.

### Relations and Predicates

The `Relations` class provides a builder-style API for defining relationship predicates with their
knowledge types. These predicates are used for classification and graph projection:

```kotlin
val relations = Relations.empty()
    .withProcedural("likes", "expresses preference for")
    .withProcedural("prefers", "indicates preference")
    .withSemantic("works at", "is employed by")
    .withSemantic("is located in", "geographical location")
    .withEpisodic("met", "encountered")
    .withEpisodic("visited", "went to")
```

Predicates can also be defined on schema properties using `@Semantics` annotations:

```kotlin
data class Person(
    val id: String,
    val name: String,
    @field:Semantics([With(key = Proposition.PREDICATE, value = "works at")])
    val employer: Company? = null,
) : NamedEntity
```

### Projector Architecture

Projectors transform propositions into specialized representations. Each projector creates a
different "view" optimized for specific query patterns:

```mermaid
flowchart TB
    subgraph Source["📝 Source of Truth"]
        P[("Propositions<br/>with confidence & decay")]
    end

    subgraph Projectors["🔄 Projectors"]
        GP["GraphProjector<br/>━━━━━━━━━━━━━━━<br/>RelationBasedGraphProjector<br/>LlmGraphProjector"]
        PP["PrologProjector<br/>━━━━━━━━━━━━━━━<br/>Logical inference"]
        MP["MemoryProjection<br/>━━━━━━━━━━━━━━━<br/>Agent context"]
        CP["Custom Projector<br/>━━━━━━━━━━━━━━━<br/>Your representation"]
    end

    subgraph Targets["🎯 Materialized Views"]
        Neo[("Neo4j<br/>Graph Traversal")]
        Pro[("tuProlog<br/>Inference & Rules")]
        Mem[("Agent Memory<br/>Semantic/Episodic/Procedural")]
        Cus[("Your Backend")]
    end

    P --> GP --> Neo
    P --> PP --> Pro
    P --> MP --> Mem
    P --> CP --> Cus

    style Source fill:#d4eeff,stroke:#63c0f5,color:#1e1e1e
    style Projectors fill:#fff3cd,stroke:#e9b306,color:#1e1e1e
    style Targets fill:#d4f5d4,stroke:#3fd73c,color:#1e1e1e
```

### Graph Projection

The `RelationBasedGraphProjector` projects propositions to graph relationships by matching
predicates from the schema and `Relations`:

```mermaid
flowchart LR
    subgraph Input
        Prop["Proposition<br/>'Bob works at Acme'"]
    end

    subgraph Matching["Predicate Matching"]
        Schema["1️⃣ Schema<br/>@Semantics predicate"]
        Rel["2️⃣ Relations<br/>fallback predicates"]
    end

    subgraph Output
        Graph["(:Person)-[:employer]->(:Company)"]
    end

    Prop --> Schema
    Schema -->|"match: 'works at'"| Graph
    Schema -->|"no match"| Rel
    Rel -->|"match"| Graph

    style Input fill:#d4eeff,stroke:#63c0f5,color:#1e1e1e
    style Matching fill:#fff3cd,stroke:#e9b306,color:#1e1e1e
    style Output fill:#d4f5d4,stroke:#3fd73c,color:#1e1e1e
```

**Priority order:**

1. Schema relationships with `@Semantics(predicate="...")` → uses property name as relationship type
2. `Relations` predicates → derives relationship type via UPPER_SNAKE_CASE

```kotlin
// Schema-driven: uses property name "employer"
// "Bob works at Acme" → (bob)-[:employer]->(acme)

// Relations fallback: derives from predicate
val relations = Relations.empty().withProcedural("likes")
// "Alice likes jazz" → (alice)-[:LIKES]->(jazz)

val projector = RelationBasedGraphProjector.from(relations)
val results = projector.projectAll(propositions, schema)

// Persist to graph database
val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repository)
val persistResult = persister.persist(results)
```

### Prolog Projection (Experimental)

The Prolog projector converts propositions to Prolog facts for logical inference:

```
  +----------------+     +------------------+     +------------------+
  |  Propositions  | --> | GraphProjector   | --> | PrologProjector  |
  |                |     | (LLM classifies) |     | (converts to     |
  | "Alice knows   |     |                  |     |  Prolog syntax)  |
  |  Kubernetes"   |     | EXPERT_IN        |     |                  |
  +----------------+     +------------------+     +------------------+
                                                          |
                                                          v
                                                   +----------------+
                                                   |  tuProlog      |
                                                   |  Knowledge     |
                                                   |  Base          |
                                                   +----------------+
```

Facts project to Prolog predicates:

- `expert_in(Person, Technology)` - expertise relationships
- `friend_of(Person, Person)` - social connections
- `works_at(Person, Company)` - employment
- `reports_to(Person, Manager)` - hierarchy

#### Custom Inference Rules

Rules are loaded from `prolog/dice-rules.pl` on the classpath:

```prolog
% Transitive reporting chain
reports_to_chain(X, Y) :- reports_to(X, Y).
reports_to_chain(X, Y) :- reports_to(X, Z), reports_to_chain(Z, Y).

% Derived relationships
coworker(X, Y) :- works_at(X, Company), works_at(Y, Company), X \= Y.

% Expertise queries
can_consult(Person, Expert, Topic) :-
    friend_of(Person, Expert),
    expert_in(Expert, Topic).
```

### Memory Projection

Memory projection classifies propositions into cognitive memory types for agent context.
The design separates **querying** (via `PropositionQuery`) from **classification** (via `MemoryProjector`).

```mermaid
flowchart LR
    subgraph Query["1. Query Propositions"]
        PQ["PropositionQuery<br/>━━━━━━━━━━━━━━━<br/>contextId, entityId,<br/>confidence, temporal"]
        REPO[("Repository")]
        PQ --> REPO
    end

    subgraph Project["2. Project to Memory"]
        PROJ["MemoryProjector<br/>━━━━━━━━━━━━━━━<br/>Classify by<br/>KnowledgeType"]
    end

    subgraph Result["3. MemoryProjection"]
        SEM["🧠 semantic<br/>Facts"]
        EPI["📅 episodic<br/>Events"]
        PRO["⚙️ procedural<br/>Preferences"]
        WRK["💭 working<br/>Session"]
    end

    REPO -->|"List<Proposition>"| PROJ
    PROJ --> SEM
    PROJ --> EPI
    PROJ --> PRO
    PROJ --> WRK

    style Query fill:#d4eeff,stroke:#63c0f5,color:#1e1e1e
    style Project fill:#fff3cd,stroke:#e9b306,color:#1e1e1e
    style Result fill:#e8dcf4,stroke:#9f77cd,color:#1e1e1e
```

**Complete example:**

```kotlin
// 1. Query propositions (caller controls what to fetch)
val props = repository.query(
    (PropositionQuery forContextId sessionContext)
        .withEntityId("alice-123")
        .withMinEffectiveConfidence(0.5)
        .orderedByEffectiveConfidence()
        .withLimit(50)
)

// 2. Project into memory types
val projector = DefaultMemoryProjector.DEFAULT
val memory = projector.project(props)

// 3. Use classified propositions
memory.semantic   // factual knowledge
memory.episodic   // event-based memories
memory.procedural // preferences and rules
memory.working    // session context

// Access by type
val facts = memory[KnowledgeType.SEMANTIC]
```

**Classification sources:**

- **Relations predicates**: "likes" → PROCEDURAL, "works at" → SEMANTIC, "met" → EPISODIC
- **Heuristic fallback**: High decay → EPISODIC, High confidence + low decay → SEMANTIC

```kotlin
val relations = Relations.empty()
    .withProcedural("likes", "prefers", "enjoys")
    .withSemantic("works at", "is located in")
    .withEpisodic("met", "visited", "attended")

val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)
val projector = DefaultMemoryProjector.create(classifier)
val memory = projector.project(propositions)
```

### Proposition Operations

Operations transform groups of propositions into new, derived propositions. Unlike projections
(which convert to different representations), operations produce new propositions at higher
abstraction levels.

```mermaid
flowchart LR
    subgraph Input["Input"]
        G1["PropositionGroup<br/>'Alice'"]
        G2["PropositionGroup<br/>'Bob'"]
    end

    subgraph Operations["Operations"]
        ABS["🔭 Abstract<br/>━━━━━━━━━━━━<br/>Synthesize insights"]
        CON["⚖️ Contrast<br/>━━━━━━━━━━━━<br/>Find differences"]
        CMP["🔗 Compose<br/>━━━━━━━━━━━━<br/>Chain relationships"]
    end

    subgraph Output["Output"]
        P["Derived Propositions<br/>level > 0<br/>sourceIds populated"]
    end

    G1 --> ABS --> P
    G1 --> CON
    G2 --> CON --> P
    G1 --> CMP --> P

    style Input fill:#d4eeff,stroke:#63c0f5,color:#1e1e1e
    style Operations fill:#e8dcf4,stroke:#9f77cd,color:#1e1e1e
    style Output fill:#d4f5d4,stroke:#3fd73c,color:#1e1e1e
```

| Operation | Description | Example |
|-----------|-------------|---------|
| **Abstract** | Synthesize higher-level insights from a group | "likes jazz, blues, classical" → "enjoys music" |
| **Contrast** | Identify differences between groups | Alice vs Bob → "opposite meeting preferences" |
| **Compose** | Chain transitive relationships (via Prolog) | "A→B, B→C" → "A indirectly relates to C" |

#### Abstraction

Generate higher-level propositions that capture the essence of a group:

```kotlin
val abstractor = LlmPropositionAbstractor.withLlm(llm).withAi(ai)

// Group propositions with a label
val bobGroup = PropositionGroup("Bob", repository.findByEntity("bob-123"))

// Generate abstractions
val abstractions = abstractor.abstract(bobGroup, targetCount = 2)
// "Bob values thoroughness and clarity in work processes"
// "Bob prefers structured communication"
```

#### Contrast

Identify and articulate differences between two groups:

```kotlin
val contraster = LlmPropositionContraster.withLlm(llm).withAi(ai)

val aliceGroup = PropositionGroup("Alice", aliceProps)
val bobGroup = PropositionGroup("Bob", bobProps)

val differences = contraster.contrast(aliceGroup, bobGroup, targetCount = 3)
// "Alice prefers morning meetings while Bob prefers afternoons"
// "Alice and Bob have different language preferences (Python vs Java)"
```

#### Proposition Levels

Derived propositions track their abstraction level and provenance:

```kotlin
data class Proposition(
    // ... other fields ...
    val level: Int = 0,              // 0 = raw, 1+ = derived
    val sourceIds: List<String>,     // IDs of source propositions
)

// Query by abstraction level
val rawObservations = repository.findByMinLevel(0)
val abstractions = repository.findByMinLevel(1)
```

### Oracle: Natural Language Q&A

The Oracle answers questions using LLM tool calling with Prolog reasoning:

| Tool              | Description                                    |
|-------------------|------------------------------------------------|
| `show_facts`      | Display sample facts with human-readable names |
| `query_prolog`    | Execute Prolog queries with variable bindings  |
| `check_fact`      | Verify if a specific fact is true              |
| `list_entities`   | Browse all known entities                      |
| `list_predicates` | Show available relationship types              |

## Package Structure

```
com.embabel.dice
├── common/                   # Shared types
│   ├── SourceAnalysisContext # Context for all DICE operations
│   ├── EntityResolver        # Entity disambiguation interface
│   ├── KnownEntity           # Pre-defined entity for hints
│   ├── Relation              # Predicate with KnowledgeType
│   ├── Relations             # Builder for relation collections
│   └── KnowledgeType         # SEMANTIC, EPISODIC, PROCEDURAL, WORKING
│
├── proposition/              # Core types (source of truth)
│   ├── Proposition           # Natural language fact with confidence/decay
│   ├── EntityMention         # Entity reference within proposition
│   ├── PropositionQuery      # Composable query specification
│   ├── Projector<T>          # Generic projection interface
│   ├── PropositionRepository # Storage interface (with query() method)
│   ├── content/              # Content ingestion
│   │   ├── ProposableContent
│   │   ├── ChunkContent
│   │   └── ContentIngestionPipeline
│   ├── revision/             # Proposition revision
│   │   ├── PropositionReviser
│   │   ├── LlmPropositionReviser
│   │   └── RevisionResult    # New, Merged, Reinforced, Contradicted, Generalized
│   └── extraction/
│       └── LlmPropositionExtractor
│
├── projection/               # Materialized views from propositions
│   ├── graph/                # Knowledge graph projection
│   │   ├── GraphProjector    # Interface for graph projection
│   │   ├── RelationBasedGraphProjector  # Predicate-based (no LLM)
│   │   ├── LlmGraphProjector # LLM-based classification
│   │   ├── ProjectionPolicy  # Filter before projection
│   │   ├── GraphRelationshipPersister   # Persistence interface
│   │   └── NamedEntityDataRepositoryGraphRelationshipPersister
│   │
│   ├── prolog/               # Prolog projection for inference
│   │   ├── PrologProjector
│   │   ├── PrologEngine      # tuProlog wrapper
│   │   └── PrologSchema
│   │
│   └── memory/               # Agent memory projection
│       ├── MemoryProjector              # Interface: project(propositions) -> MemoryProjection
│       ├── MemoryProjection             # Result: semantic, episodic, procedural, working
│       ├── KnowledgeTypeClassifier      # Interface for classification
│       ├── RelationBasedKnowledgeTypeClassifier
│       ├── HeuristicKnowledgeTypeClassifier
│       ├── DefaultMemoryProjector       # Default implementation
│       └── MemoryRetriever              # Similarity + entity + recency retrieval
│
├── query/oracle/             # Question answering
│   ├── Oracle
│   ├── ToolOracle
│   └── PrologTools
│
├── operations/               # Proposition transformations
│   ├── PropositionGroup      # Labeled collection of propositions
│   ├── abstraction/          # Higher-level synthesis
│   │   ├── PropositionAbstractor
│   │   └── LlmPropositionAbstractor
│   └── contrast/             # Difference identification
│       ├── PropositionContraster
│       └── LlmPropositionContraster
│
├── pipeline/                 # Extraction pipeline orchestration
│   └── PropositionPipeline
│
└── text2graph/               # Knowledge graph building
    ├── KnowledgeGraphBuilder
    ├── SourceAnalyzer
    └── EntityResolver
```

## Installation

Add to your `pom.xml`:

```xml

<dependency>
    <groupId>com.embabel</groupId>
    <artifactId>dice</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Technology Stack

- **tuProlog (2p-kt)**: Pure Kotlin Prolog engine for inference
- **Spring Boot**: Application framework
- **Spring Shell**: Interactive CLI (not brought into downstream projects)
- **OpenAI/Anthropic**: LLM providers for extraction and Q&A
- **Kotlin**: Primary language

## References

### DICE: Domain-Integrated Context Engineering

Johnson, R. (2025). *Context Engineering Needs Domain Understanding*. Medium.
https://medium.com/@springrod/context-engineering-needs-domain-understanding-b4387e8e4bf8

### GUM: General User Models

Shaikh, O., Sapkota, S., Rizvi, S., Horvitz, E., Park, J.S., Yang, D., & Bernstein, M.S. (2025). *Creating General User
Models from Computer Use*. arXiv:2505.10831.
https://arxiv.org/abs/2505.10831

The proposition-based architecture is inspired by GUM's approach to building unified user models through
confidence-weighted propositions. GUM's four-module pipeline (Propose, Retrieve, Revise, Audit) demonstrates 76%
accuracy overall and 100% for high-confidence propositions.

```
  GUM Pipeline                    DICE
  ────────────                    ────
  Propose     ─────────────────►  PropositionExtractor
  Retrieve    ─────────────────►  PropositionRepository.findSimilar()
  Revise      ─────────────────►  PropositionReviser
  Audit       ─────────────────►  ProjectionPolicy
```

## License

Apache License 2.0
