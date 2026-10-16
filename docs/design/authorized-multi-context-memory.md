# Authorized multi-context memory

`Memory.forContexts(scope, repository)` creates a `ScopedMemory` over a host-authorized set of
contexts. It is an immutable read view: it does not merge contexts, transfer ownership or issue
grants. The host resolves authorization and binds the scope before exposing the tool. Model
input accepts only `query` and `limit`; context, principal and policy keys are rejected.

Use [scoped proposition reads](scoped-proposition-reads.md) for direct repository access and
[memory visibility](memory-visibility.md) when a host also needs row and field projection.
The legacy `Memory.forContext(...).withRepository(...)` remains a singleton-context API.

## Java

```java
import com.embabel.agent.api.tool.Tool;
import com.embabel.dice.agent.Memory;
import com.embabel.dice.agent.ScopedMemory;
import com.embabel.dice.proposition.*;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import java.time.Instant;
import java.util.List;

public class MultiContextMemoryJava {
    public static void main(String[] args) {
        var store = new InMemoryPropositionRepository();
        var now = Instant.now();
        for (var owner : List.of("A", "B", "C")) {
            store.save(Proposition.create(owner + "-fact", owner, owner + " remembers tea", List.of(),
                    0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE));
        }
        var scope = ContextReadScope.ofValues(List.of("A", "B"));
        var reader = ScopedPropositionReader.open(store, scope);
        var memory = ScopedMemory.configuring(reader).withTopic("preferences")
                .withEagerQuery(q -> q.withLimit(1)).memory();
        var contribution = memory.contribution();
        if (!contribution.equals(memory.contribution()) || contribution.contains("C remembers")) {
            throw new AssertionError("Invalid eager publication");
        }
        var lazy = ((Tool.Result.Text) memory.call("{\"limit\":2}")).getContent();
        if (lazy.contains("C remembers") || !lazy.contains("remembers tea")) {
            throw new AssertionError("Invalid lazy scope");
        }
        var simple = Memory.forContexts(scope, store).withTopic("preferences");
        if (simple.tools().size() != 1) throw new AssertionError("Missing tool");
        System.out.println("MultiContextMemoryJava OK");
    }
}
```

## Kotlin

```kotlin
import com.embabel.agent.api.tool.Tool
import com.embabel.dice.agent.Memory
import com.embabel.dice.agent.ScopedMemory
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import java.time.Instant
import java.util.function.UnaryOperator

fun main() {
    val store = InMemoryPropositionRepository()
    val now = Instant.now()
    listOf("A", "B", "C").forEach { owner ->
        store.save(Proposition.create("$owner-fact", owner, "$owner remembers tea", emptyList(),
            0.9, 0.0, reasoning = null, grounding = emptyList(), created = now,
            revised = now, status = PropositionStatus.ACTIVE))
    }
    val scope = ContextReadScope.ofValues(listOf("A", "B"))
    val reader = ScopedPropositionReader.open(store, scope)
    val memory = ScopedMemory(reader) {
        topic = "preferences"
        eagerQuery = UnaryOperator { it.withLimit(1) }
    }
    val contribution = memory.contribution()
    check(contribution == memory.contribution() && "C remembers" !in contribution)
    val lazy = (memory.call("{\"limit\":2}") as Tool.Result.Text).content
    check("C remembers" !in lazy && "remembers tea" in lazy)
    check(Memory.forContexts(scope, store).withTopic("preferences").tools().size == 1)
    println("MultiContextMemoryKotlin OK")
}
```

## Retrieval and publication

The memory engine probes vector and keyword tiers across authorized contexts, adding a
related-entity tier when the direct unique hits are fewer than the requested limit. Each tier
deduplicates by `(contextId, propositionId)` and contributes its first `C` unique rows. Reciprocal
rank fusion adds `1 / (60 + rank)` per contributing tier, using contiguous ranks starting at one
after deduplication, then applies the final global limit. Fusion ties use the first contributing
tier followed by the adapter's tie order. A short first tier does not suppress later tiers.

Keyword scoring uses distinct normalized substring tokens. Candidate capacity is
`C = min(400, max(32, 4 * limit))` and probes use `C + 1`. An extra unique row marks the result as
a bounded pool and does not participate in that tier's fusion or related-search seeds. Fusion
therefore ranks the bounded tier pools; it does not claim exact fusion over all eligible rows.
Insufficient configured `maxProbe` fails before count or tier calls. Defaults permit 100 final
results and 401 probes. Eager output has its own
`maxEager` cap. A count reports eligible membership rather than a page length.

Eager query, topic and text searches union their selected keys. Lazy calls exclude keys only
after successful eager publication. Rendering or provenance failures reserve no unpublished
keys. Repeated `contribution()` calls return the same published snapshot on that immutable
view, and lazy selection still excludes its keys even if confidence ranking changes later.
Create a fresh view when the host's grants change. Replacing a view cannot retract text already
delivered to a model; revocation governs future reads and publications.

`ScopedProvenanceResolver` receives only selected owner-qualified keys. It cannot make an
unselected row eligible. Provenance output must remain appropriate for the authorized host
view. Use the policy API for explicit field and source permissions.

## Compatibility and migration

`Memory` retains its existing constructor, generated copy/component and `forContext` JVM
descriptors, and `PropositionQuery` retains its constructor/copy/default surface. The separate
`ScopedMemory` facade carries multi-context behavior. The compatibility runner compares the
captured baseline descriptors, compiles old Java and Kotlin clients against that baseline, and
executes the compiled clients against candidate classes. It also extracts, compiles and runs
the complete Java and Kotlin programs in these four read-API documents.

Singleton narrowing hooks now preserve the original scope, ACTIVE membership and confidence
floor. A hook cannot replace or clear existing membership conditions to widen a read. Eager
hooks also retain these boundaries. Applications that depended on widening must resolve a new
authorized view explicitly.

Legacy `Memory`, `MemoryRetriever` and MCP recall surface filtered-vector unsupported,
candidate-budget and deadline failures instead of silently trying later tiers. They do not
interpret infrastructure failure as an empty search. `RetrievalRouter` and
`LlmPropositionReviser` also use corrected filtered-vector semantics. See
[filtered vector search](filtered-vector-search.md) for the failure codes and finite budgets.
Manual Drivine construction requires `withNativeReadExecutor`; its unscoped legacy filtered
queries fail explicitly. MCP and REST retain their existing singleton-context surfaces and do
not resolve identity, grants or multi-context authorization on the caller's behalf.
