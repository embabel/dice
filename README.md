![Build](https://github.com/embabel/dice/actions/workflows/maven.yml/badge.svg)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

<img align="left" src="https://github.com/embabel/embabel-agent/blob/main/embabel-agent-api/images/315px-Meister_der_Weltenchronik_001.jpg?raw=true" width="180">

&nbsp;&nbsp;&nbsp;&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;

# Embabel DICE

Knowledge graph construction and reasoning library with proposition-based architecture and Prolog inference.

```
    ____  _____ _____ ______
   / __ \/  _/ ___// ____/
  / / / // // /   / __/
 / /_/ // // /___/ /___
/_____/___/\____/_____/

 Domain-Integrated Context Engineering
```

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

### Content Ingestion Pipeline

The `ContentIngestionPipeline` provides a unified interface for processing any content that can yield propositions:

```kotlin
// Create pipeline
val pipeline = ContentIngestionPipeline.create(ai, repository, "gum_propose")

// Process any ProposableContent
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

### Projector Architecture

Projectors transform propositions into specialized representations. Each projector creates a
different "view" optimized for specific query patterns:

```
  Propositions (source of truth)
       │
       ├──► GraphProjector ──► Neo4j relationships (graph traversal)
       │
       ├──► PrologProjector ──► Prolog facts (logical inference)
       │
       ├──► MemoryProjection ──► Agent context (LLM injection)
       │
       └──► [Your Projector] ──► Custom representation
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
├── proposition/              # Core types (source of truth)
│   ├── Proposition           # Natural language fact with confidence/decay
│   ├── EntityMention         # Entity reference within proposition
│   ├── Projector<T>          # Generic projection interface
│   ├── PropositionRepository # Storage interface
│   ├── content/              # Content ingestion
│   │   ├── ProposableContent # Interface for any content that yields propositions
│   │   ├── ChunkContent      # Adapter for document chunks
│   │   └── ContentIngestionPipeline
│   ├── revision/             # Proposition revision
│   │   ├── PropositionReviser
│   │   └── RevisionResult    # New, Merged, Reinforced, Contradicted
│   └── extraction/           # Proposition extraction
│       └── LlmPropositionExtractor
│
├── projection/               # Materialized views from propositions
│   ├── graph/                # Knowledge graph projection
│   │   ├── GraphProjector    # Proposition -> Neo4j relationships
│   │   ├── LlmGraphProjector # LLM-based relationship classification
│   │   └── ProjectionPolicy  # Filter propositions before projection
│   │
│   ├── prolog/               # Prolog projection for inference
│   │   ├── PrologProjector   # Relationship -> Prolog facts
│   │   ├── PrologEngine      # tuProlog wrapper
│   │   └── PrologSchema      # Predicate mappings
│   │
│   └── memory/               # Agent memory projection
│       ├── MemoryProjection  # User profiles, events, rules
│       └── MemoryRetriever   # Recall with memory semantics
│
├── query/oracle/             # Question answering
│   ├── Oracle                # Q&A interface
│   ├── ToolOracle            # LLM tool-calling implementation
│   └── PrologTools           # @LlmTool annotated Prolog operations
│
├── pipeline/                 # Extraction pipeline orchestration
│   └── PropositionPipeline   # Fluent pipeline with cross-chunk resolution
│
└── text2graph/               # Knowledge graph building
    ├── KnowledgeGraphBuilder # Multi-pass graph construction
    ├── SourceAnalyzer        # Source classification
    └── EntityResolver        # Entity resolution
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
