# DICE and CoALA

How DICE relates to the CoALA cognitive-architecture framework, and how to use DICE as the
long-term memory engine inside a CoALA-based agent architecture.

## The short answer

"Why GUM instead of CoALA?" is a category error. The two operate at different levels:

- **CoALA** ([Sumers, Yao, Narasimhan & Griffiths, arXiv:2309.02427](https://arxiv.org/abs/2309.02427),
  TMLR 2024) is a **cognitive-architecture framework**: a taxonomy that organizes a language agent
  around working memory, three kinds of long-term memory (episodic, semantic, procedural), an action
  space (internal reasoning/retrieval/learning actions, external grounding actions), and a
  decision-making loop. It tells you *what subsystems an agent should have*. It deliberately does
  not specify mechanisms — how propositions are extracted, deduplicated, revised, decayed,
  consolidated, or audited.
- **GUM** ([Shaikh et al., arXiv:2505.10831](https://arxiv.org/abs/2505.10831), Stanford/Microsoft
  2025) is a **concrete, empirically validated mechanism** for one of those subsystems: building a
  semantic user model from confidence-weighted propositions, with published accuracy results
  (76% overall, 100% for high-confidence propositions).

DICE did not choose GUM *over* CoALA. It chose GUM as the implementation mechanism for the
long-term memory component that a CoALA-style architecture calls for. An organization
standardizing on CoALA as its architectural vocabulary still needs a concrete engine behind each
memory box in the diagram. DICE is that engine for long-term memory — designed for the JVM,
integrated with existing enterprise entities, and auditable.

## Mapping DICE onto CoALA's memory taxonomy

DICE classifies every proposition with a `KnowledgeType` that corresponds one-to-one with CoALA's
memory taxonomy:

| CoALA memory | DICE `KnowledgeType` | DICE mechanism |
|---|---|---|
| **Semantic** — facts about the world and the user | `SEMANTIC` | The core proposition store: confidence-weighted, entity-resolved, revised through five-way classification, decaying over time. Low decay, long-lived. |
| **Episodic** — records of the agent's experiences | `EPISODIC` | Propositions with temporal context and higher decay, plus the **grounding chain**: every proposition links back to the source chunks it was extracted from, so the raw experience record is retained alongside the distilled knowledge. |
| **Procedural** — how things are done | `PROCEDURAL` | Declarative statements of preference, habit, and rule ("prefers X", "when deploying, use Y") that steer agent behaviour. Executable skills and code remain the agent framework's concern (see below). |
| **Working** — current, session-scoped context | `WORKING` | Transient propositions not yet consolidated into long-term memory. Working-memory *management* — the context assembled for each LLM call — belongs to the agent framework, with DICE's memory projection supplying the long-term contribution to it. |

Two CoALA internal action types also have direct DICE counterparts:

- **Retrieval actions** — CoALA's "read from long-term memory into working memory" — are DICE's
  projections and query surfaces: vector similarity, canonical match, entity-based lookup,
  composable `PropositionQuery`, graph traversal via the Neo4j projection, Prolog inference,
  and natural-language QA via the Oracle.
- **Learning actions** — CoALA's "write experience into long-term memory" — are the proposition
  pipeline (extract → resolve → revise) plus the consolidation dream loop, which synthesizes
  higher-level propositions from lower-level ones (episodic → semantic distillation, with source
  tracking through the abstraction hierarchy).

## What CoALA leaves open — and DICE's answers

CoALA is explicit that it is a conceptual framework, not an implementation. Building a real
memory system from it means making mechanism decisions the paper does not make. These are exactly
the decisions DICE takes a position on:

| Open question in a CoALA build | DICE's answer |
|---|---|
| What is a memory unit? | A structured **proposition**: text, typed entity mentions, confidence, decay, grounding, reinforcement count, abstraction level — not a flat fact string. |
| How is new information reconciled with old? | Five-way revision classification (IDENTICAL / SIMILAR / CONTRADICTORY / UNRELATED / GENERALIZES) with outcome-dependent confidence adjustment, batched for throughput, with deterministic fast paths that skip the LLM. |
| What happens on contradiction? | **Both propositions are retained** with reduced confidence — history is never silently deleted. |
| How does memory age? | Effective confidence = `confidence * exp(-decay * k * age_days)` (the GUM formula), so transient knowledge fades and stable knowledge persists, without hard deletion. |
| How does memory connect to existing systems? | A seven-strategy entity-resolution pipeline links mentions to the organization's *existing* entities (any `NamedEntityDataRepository`), rather than creating a parallel entity universe. |
| How is consolidation performed? | Admission gates, mark-and-sweep reclamation with an audit trail, and the consolidation dream loop producing multi-level abstractions with source lineage. |
| How is memory trusted and audited? | Per-proposition provenance (grounding chain to source chunks), source authority and trust scoring, reinforcement counting, and query-time authority filtering. |

## Using DICE inside a CoALA-based architecture

For an organization adopting CoALA as its organizing framework:

1. **Keep CoALA as the architectural vocabulary.** It is a good shared language for what an agent
   needs: the memory taxonomy, the action space, the decision loop.
2. **Let the agent framework own the decision loop and working memory.** CoALA locates planning,
   action selection, and working-memory management in the agent itself. In the Embabel stack that
   is the [Embabel Agent Framework](https://github.com/embabel/embabel-agent) (typed actions,
   goal-oriented planning, blackboard state); DICE deliberately does not duplicate it.
3. **Use DICE as the long-term memory engine** behind the semantic, episodic, and (declarative)
   procedural boxes, with the memory projection feeding the long-term contribution to working
   context.
4. **Exploit the audit properties in regulated environments.** Because contradictions are retained
   rather than deleted, every proposition is grounded in source evidence, and confidence evolution
   is explicit, the question "why does the system believe X, and how sure is it?" has an
   inspectable answer. Memory systems that hard-delete on conflict cannot reconstruct that history.
5. **Deploy inside the perimeter.** DICE is an embeddable JVM library with no mandatory cloud
   service or graph-database dependency — relevant where data residency and vendor-risk review
   constrain managed memory services.

## What DICE does not attempt

Honesty about scope, in CoALA's terms:

- **The decision loop.** DICE is memory, not an agent. Planning, action selection, and grounding
  actions belong to the agent framework.
- **Working-memory management.** DICE classifies and stores working-type knowledge and supplies
  retrieval into context, but assembling each LLM call's context window is the caller's job.
- **Procedural memory as executable skills.** CoALA's procedural memory includes the agent's own
  code and learned skills. DICE covers the declarative slice (preferences, habits, rules as
  propositions); skill acquisition and storage are out of scope.

For how DICE compares against other *memory implementations* (Zep/Graphiti, Mem0, LangMem, the
Google/AWS/Microsoft managed offerings, Neo4j Agent Memory, LiveGraph), see
[competitive-positioning.md](competitive-positioning.md).
