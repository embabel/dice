# DICE Gap Analysis: Competitive Intelligence & Implementation Specs

Based on deep analysis of Zep/Graphiti, Mem0, and LangChain/LangMem memory systems.

## Current State

### What DICE Already Does Well

| Capability | DICE Implementation | Competitors |
|---|---|---|
| Batch classification | N propositions in 1 LLM call via `classifyBatch()` | Zep: sequential per-edge. Mem0: sequential per-fact. LangMem: sequential tool calls. |
| Auto-merge fast path | Embedding score >= 0.95 skips LLM entirely | Zep: text-identical fast path only (no similarity threshold). Others: none. |
| Outcome-dependent decay | Contradicted +0.15, merged *0.7, reinforced *0.85 | Zep: binary temporal invalidation. Mem0: hard delete. LangMem: none. |
| Canonical text dedup | Cheap string match before vector search | Zep: similar for identical edge text but only after vector search. |
| 5-way classification | IDENTICAL/SIMILAR/CONTRADICTORY/UNRELATED/GENERALIZES with edge-case guidance + few-shot | Mem0: only ADD/UPDATE/DELETE/NONE. LangMem: insert/update/delete tool calls. |
| JVM native | Only JVM-based memory system | All competitors are Python (Zep also Go). |
| Confidence + decay model | Per-proposition confidence with exponential time decay from GUM paper | Zep: no decay. Mem0: no confidence model. LangMem: asks for p(x) in prompts but no decay math. |

---

## Gaps

### GAP-1: Extraction-Time Confidence Qualification

**Source**: LangMem

**Problem**: DICE's extraction prompt asks the LLM for `confidence` and `decay` values, but doesn't instruct it to *qualify uncertain information* or explain its confidence reasoning. The `reasoning` field exists on `SuggestedProposition` but the prompt doesn't emphasize using it for uncertainty qualification.

**What competitors do**: LangMem's core extraction prompt says:
> "Caveat uncertain or suppositional information with confidence levels (p(x)) and reasoning. Quote supporting information when necessary."

**Current state in DICE**:
- `extract_propositions.jinja` lines 49-53 describe confidence/decay but only by example ("High confidence: Explicit statements", "Lower confidence: Implied information")
- `SuggestedProposition.reasoning` exists but the prompt doesn't strongly guide its use

**Proposed change**: Add a section to `extract_propositions.jinja` after the current "Confidence & Decay" section.

**Affected files**:
- `src/main/resources/prompts/dice/extract_propositions.jinja` — add qualification instructions

**Prompt text to add** (after line 53 of `extract_propositions.jinja`):

```
## Uncertainty Qualification

- When a fact is inferred rather than explicitly stated, lower confidence and explain in `reasoning`
- When the text uses hedging language ("I think", "maybe", "probably"), reflect this in confidence
- Quote the supporting evidence in `reasoning` when the fact is non-obvious
- Example: Text says "I think Alice might work at Google"
  -> confidence: 0.4, reasoning: "Hedged statement — 'I think ... might' indicates uncertainty"
- Example: Text says "Alice is a senior engineer at Google"
  -> confidence: 0.95, reasoning: "Explicit factual statement"
```

**Acceptance criteria**:
- [ ] Extraction prompt includes uncertainty qualification instructions
- [ ] Existing tests still pass (prompt change only, no code change)
- [ ] Verify with a manual test that hedged input produces lower confidence + reasoning

---

### GAP-2: Surprise-Prioritized Retention

**Source**: LangMem

**Problem**: DICE's decay model handles the "persistent/reinforced" side (slower decay for reinforced propositions) but has no mechanism to prioritize *surprising* new information — facts that deviate from existing patterns.

**What competitors do**: LangMem instructs:
> "Prioritize retention of surprising (pattern deviation) and persistent (frequently reinforced) information, ensuring nothing worth remembering is forgotten and nothing false is remembered."

This mirrors neuroscience models where prediction errors drive memory consolidation.

**Current state in DICE**:
- `RevisionResult.New` treats all new propositions identically regardless of how surprising they are
- No signal distinguishes "completely novel topic" from "expected continuation of existing pattern"

**Proposed change**: When a new proposition is classified UNRELATED to all candidates but those candidates exist (i.e., the context has prior knowledge), this is a *surprise* — the new fact doesn't fit existing patterns. Give such propositions a lower initial decay (more durable).

**Affected files**:
- `src/main/kotlin/.../revision/LlmPropositionReviser.kt` — in `classifiedToResult()`, the `else` branch (line 427-430) that produces `RevisionResult.New`

**Implementation sketch**:

```kotlin
// In classifiedToResult(), the else branch:
else -> {
    // If candidates existed but none matched, this is a surprising fact
    // (the context has prior knowledge but this is genuinely new information).
    // Reduce decay to make surprising facts more durable.
    val adjustedProposition = if (classified.isNotEmpty()) {
        val surpriseDecay = (newProposition.decay * 0.8).coerceAtLeast(0.0)
        newProposition.copy(decay = surpriseDecay)
    } else {
        newProposition
    }
    RevisionResult.New(adjustedProposition)
}
```

**Design decision**: The 0.8 multiplier is conservative. A more aggressive approach would also boost confidence, but that risks over-weighting noise. Start with decay reduction only.

**Acceptance criteria**:
- [ ] New propositions classified UNRELATED against existing candidates get reduced decay
- [ ] New propositions with no candidates at all (empty context) get default decay
- [ ] Unit test: proposition with candidates all UNRELATED has decay * 0.8
- [ ] Unit test: proposition with no candidates has unchanged decay

---

### GAP-3: Signal-to-Noise Maximization Instruction

**Source**: LangMem

**Problem**: DICE's extraction prompt doesn't explicitly ask the LLM to produce dense, information-rich propositions. This can lead to verbose or redundant extractions.

**What competitors do**: LangMem instructs:
> "Consolidate and compress redundant memories to maintain information-density; strengthen based on reliability and recency; maximize SNR by avoiding idle words."

**Proposed change**: Add an SNR instruction to the extraction prompt.

**Affected files**:
- `src/main/resources/prompts/dice/extract_propositions.jinja` — add to extraction rules section

**Prompt text to add** (as rule 5 after existing rule 4, line 15):

```
5. **Dense and specific**: Each proposition should be information-dense — maximize signal-to-noise ratio. Avoid filler words, vague qualifiers, or restating context that's already captured in entity mentions. Prefer "Alice is a senior ML engineer at Google" over "Alice works at a company called Google where she is in a senior position doing machine learning engineering".
```

**Acceptance criteria**:
- [ ] Extraction prompt includes density/SNR instruction
- [ ] Existing tests still pass

---

### GAP-4: Temporal Anchoring in Extraction

**Source**: Zep/Graphiti

**Problem**: DICE propositions have `created` and `revised` timestamps but don't resolve *relative time expressions* during extraction. If a user says "I started at Google last week", DICE stores the literal text but doesn't anchor "last week" to a concrete date.

**What competitors do**: Zep passes `REFERENCE_TIME` into every extraction prompt with explicit rules:
> - "Use REFERENCE_TIME to resolve vague or relative temporal expressions (e.g., 'last week')"
> - "If the fact is ongoing (present tense), set valid_at to REFERENCE_TIME"
> - "If a change/termination is expressed, set invalid_at to the relevant timestamp"
> - "Use ISO 8601 with 'Z' suffix (UTC)"

**Current state in DICE**:
- `Proposition` has `created: Instant` and `revised: Instant` (system timestamps)
- No `validAt` / `invalidAt` fields for event-time semantics
- `TemplateModel` doesn't pass a reference timestamp to the template
- `extract_propositions.jinja` has no temporal resolution instructions

**Proposed change** (two phases):

#### Phase A: Prompt-level temporal anchoring (low effort)

Add reference time to the template model and instruct the LLM to resolve relative times in proposition text.

**Affected files**:
- `src/main/kotlin/.../extraction/LlmPropositionExtractor.kt` — add `Instant.now()` (or chunk timestamp) to `TemplateModel`
- `src/main/resources/prompts/dice/extract_propositions.jinja` — add temporal resolution section
- `TemplateModel` data class — add `referenceTime: Instant` field

**Prompt text to add**:

```
## Temporal Resolution

Reference time: {{ model.referenceTime }}

When the text contains relative time expressions, resolve them to concrete dates in the proposition text:
- "last week" -> "the week of [date]"
- "yesterday" -> "[specific date]"
- "recently" -> leave as-is (too vague to resolve)
- "three years ago" -> "since approximately [year]"

If a fact has an explicit or resolvable time, include the resolved date in the proposition text.
Example: Text says "I moved to NYC last month" (reference time: 2026-02-17)
-> "Alice moved to NYC in approximately January 2026"
```

#### Phase B: Bi-temporal model (higher effort, future)

Add `validAt: Instant?` and `invalidAt: Instant?` fields to `Proposition` for proper event-time semantics. This enables point-in-time queries and temporal contradiction resolution.

This is a larger change affecting `Proposition.kt`, `PropositionRepository`, storage layer, and query logic. Spec separately when Phase A proves valuable.

**Acceptance criteria (Phase A)**:
- [ ] `TemplateModel` includes `referenceTime`
- [ ] Extraction prompt includes temporal resolution instructions
- [ ] Extraction prompt renders the reference time
- [ ] Existing tests still pass

---

### GAP-5: Role-Aware Extraction

**Source**: Mem0

**Problem**: DICE doesn't distinguish between user-stated facts and assistant-stated facts during extraction. This can lead to extracting facts *about the assistant* as if they were facts about the user.

**What competitors do**: Mem0 has two separate extraction prompts:
- `USER_MEMORY_EXTRACTION_PROMPT`: "GENERATE FACTS SOLELY BASED ON THE USER'S MESSAGES. DO NOT INCLUDE INFORMATION FROM ASSISTANT OR SYSTEM MESSAGES."
- `AGENT_MEMORY_EXTRACTION_PROMPT`: Extracts facts from assistant messages only.

The user prompt uses penalty framing: "YOU WILL BE PENALIZED IF YOU INCLUDE INFORMATION FROM ASSISTANT OR SYSTEM MESSAGES."

**Current state in DICE**:
- `extract_propositions.jinja` treats the input text uniformly — no role distinction
- The chunk text (`model.chunk.text`) may contain both user and assistant messages

**Proposed change**: Add a role-awareness instruction to the extraction prompt. This doesn't require separate prompts — a single instruction to attribute facts to the correct speaker is sufficient.

**Affected files**:
- `src/main/resources/prompts/dice/extract_propositions.jinja` — add role attribution rule

**Prompt text to add** (as rule 6 in extraction rules):

```
6. **Speaker attribution**: When the text contains dialogue between a user and an assistant, extract facts about the USER only unless the assistant reveals factual information (e.g., the assistant's name or capabilities). Do not extract the assistant's responses as facts about the user. Always attribute facts to the correct speaker using their name as an entity mention.
```

**Acceptance criteria**:
- [ ] Extraction prompt includes speaker attribution guidance
- [ ] Existing tests still pass

---

### GAP-6: ID Hallucination Prevention in Classification

**Source**: Mem0

**Problem**: DICE's batch classification prompt sends real proposition UUIDs to the LLM. The LLM could hallucinate or corrupt these IDs in its response, causing `candidates.find { it.id == classification.propositionId }` to return null (line 321 of `LlmPropositionReviser.kt`), silently dropping classifications.

**What competitors do**: Mem0 re-indexes existing memories with sequential integers before sending to the LLM:
```python
temp_uuid_mapping = {}
for idx, item in enumerate(retrieved_old_memory):
    temp_uuid_mapping[str(idx)] = item["id"]
    retrieved_old_memory[idx]["id"] = str(idx)
```

**Current state in DICE**:
- `classify_proposition.jinja` line 10: `[{{ loop.index0 }}] ID: {{ prop.id }}`
- `classify_propositions_batch.jinja` line 11: `[{{ loop.index0 }}.{{ loop.index0 }}] ID: {{ prop.id }}`
- Both send the full UUID and also show a numeric index — but the LLM is asked to return the UUID in `ClassificationItem.propositionId`
- If the LLM hallucinates the UUID, the classification is silently dropped (returns null from `mapNotNull`)

**Proposed change**: Use integer indices as IDs in the prompt and map back to real IDs in code.

**Affected files**:
- `src/main/resources/prompts/dice/classify_proposition.jinja` — use index as ID
- `src/main/resources/prompts/dice/classify_propositions_batch.jinja` — use index as ID
- `src/main/kotlin/.../revision/LlmPropositionReviser.kt` — in `classify()` and `classifyBatch()`, build index-to-ID mapping and translate response IDs back

**Implementation sketch for `classify()`**:

```kotlin
// Before LLM call: build mapping
val indexToId = candidates.mapIndexed { idx, p -> idx.toString() to p.id }.toMap()
val candidateData = candidates.mapIndexed { idx, p ->
    mapOf(
        "id" to idx.toString(),  // integer index, not UUID
        "text" to p.text,
        "confidence" to p.effectiveConfidence(),
    )
}

// After LLM call: translate back
return response.classifications.mapNotNull { classification ->
    val realId = indexToId[classification.propositionId] ?: return@mapNotNull null
    val candidate = candidates.find { it.id == realId } ?: return@mapNotNull null
    // ...
}
```

**Template change** (classify_proposition.jinja):
```
{% for prop in candidates %}
[{{ loop.index0 }}] ID: {{ loop.index0 }}
    Text: "{{ prop.text }}"
    Confidence: {{ prop.confidence }}
{% endfor %}
```

**Acceptance criteria**:
- [ ] Classification prompts use integer indices instead of UUIDs
- [ ] Code maps integer indices back to real proposition IDs
- [ ] Existing classification tests still pass
- [ ] Add test: verify that a classification response with correct integer indices maps to correct propositions

---

### GAP-7: Mentions/Frequency Counter

**Source**: Mem0

**Problem**: DICE has no concept of how frequently a proposition or entity is referenced across conversations. All propositions are treated as equally important aside from confidence and decay.

**What competitors do**: Mem0 tracks `mentions` counters on both graph nodes and edges, incrementing on each MERGE operation. This provides an importance-by-frequency signal complementary to confidence.

**Current state in DICE**:
- `Proposition.grounding` accumulates chunk IDs (indirect frequency signal)
- No explicit `mentions` or `reinforceCount` field
- `mergePropositions()` boosts confidence but doesn't track how many times a merge occurred

**Proposed change**: Add a `reinforceCount: Int` field to `Proposition`. Increment on merge and reinforce. This provides an explicit frequency signal for retrieval ranking.

**Affected files**:
- `src/main/kotlin/.../proposition/Proposition.kt` — add `reinforceCount: Int = 0`
- `src/main/kotlin/.../revision/LlmPropositionReviser.kt` — increment in `mergePropositions()` and `reinforceProposition()`

**Implementation sketch**:

```kotlin
// In Proposition.kt:
data class Proposition(
    // ... existing fields ...
    val reinforceCount: Int = 0,
)

// In mergePropositions():
return existing.copy(
    confidence = boostedConfidence,
    decay = slowedDecay,
    grounding = combinedGrounding,
    reinforceCount = existing.reinforceCount + 1,
    revised = Instant.now(),
)
```

**Acceptance criteria**:
- [ ] `Proposition` has `reinforceCount` field defaulting to 0
- [ ] Merge increments reinforceCount
- [ ] Reinforce increments reinforceCount
- [ ] Existing tests compile and pass with new field default

---

## Competitive Positioning Summary

### vs Zep/Graphiti

**Their moat**: Bi-temporal fact model, custom ontology via Pydantic, 5 reranking strategies, community subgraph summaries, Neo4j-backed graph traversal.

**Their weakness**: Sequential-only ingestion ("episodes must be added sequentially and awaited"), Python/Go only, heavy infrastructure (Neo4j required), no batch classification.

**Attack angle**: DICE is embeddable — no Neo4j dependency. Batch pipeline is faster for high-throughput ingestion. Position as "memory for JVM agents" vs their "memory infrastructure platform." GAP-4 Phase B (bi-temporal) closes their biggest technical advantage.

### vs Mem0

**Their moat**: Graph memory with Neo4j/Memgraph/Neptune/Kuzu, vision support, procedural memory for agent traces, mentions counting.

**Their weakness**: Coarse 4-operation model (ADD/UPDATE/DELETE/NONE) — no SIMILAR/GENERALIZES distinction. Sequential per-fact processing. Graph memory is a separate bolted-on pipeline.

**Attack angle**: DICE's 5-way classification preserves nuance that Mem0's 4-op model loses. Unified revision pipeline vs their split vector+graph paths. GAP-6 (ID hallucination prevention) adopts their best defensive technique. GAP-7 (reinforceCount) matches their mentions signal.

### vs LangChain/LangMem

**Their moat**: Excellent extraction prompts (confidence-qualified, surprise-prioritized, SNR-maximizing), prompt optimization via gradient analogy, debounced background reflection, dilated windows retrieval.

**Their weakness**: Graph memory is literally commented-out code. No structured dedup pipeline — relies on LLM tool calls for consolidation. Tightly coupled to LangGraph ecosystem.

**Attack angle**: LangMem has great prompts but weak infrastructure. DICE's explicit classification taxonomy + fast paths + batch processing is more reliable at scale. GAPs 1-3 adopt their best prompt ideas and combine them with our superior pipeline mechanics.

---

## Implementation Priority

| Priority | Gap | Effort | Impact | Dependencies |
|---|---|---|---|---|
| 1 | GAP-3: SNR instruction | ~15 min | High — improves extraction quality | None |
| 2 | GAP-1: Confidence qualification | ~30 min | High — better calibrated confidence | None |
| 3 | GAP-5: Role-aware extraction | ~15 min | Medium — prevents misattribution | None |
| 4 | GAP-6: ID hallucination prevention | ~1 hr | Medium — prevents silent classification drops | None |
| 5 | GAP-2: Surprise-prioritized retention | ~30 min | Medium — more durable novel facts | None |
| 6 | GAP-7: Mentions/frequency counter | ~1 hr | Medium — frequency signal for ranking | None |
| 7 | GAP-4A: Temporal anchoring (prompt) | ~1 hr | Medium — resolves relative dates | None |
| 8 | GAP-4B: Bi-temporal model | ~1 week | High — temporal queries, closes Zep gap | GAP-4A |

GAPs 1-3 are prompt-only changes (no code), can be done in a single pass.
GAPs 4A-6 are code + prompt changes, independent of each other.
GAP-7 is a schema addition.
GAP-4B is a larger architectural change that should be specced separately.
