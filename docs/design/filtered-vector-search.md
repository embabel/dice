# Filtered vector search

Filtered similarity search applies the complete membership predicate before ranking and global
top-K selection. A high-scoring row outside the scope, a rejected status or an excluded grant
cannot occupy an eligible candidate's slot. `PropositionQuery.orderBy` and `limit` do not replace
the vector request's ranking and `topK`. Equal scores use context then proposition ID for a
deterministic order. Scores use raw cosine similarity; the request threshold is inclusive.

The scoped vector path ranks exactly over the admitted eligible set: it scores every row with
a usable stored embedding before applying the threshold and final `topK`. This differs from
approximate nearest-neighbor (ANN) index retrieval. A global vector index's recall does not
establish exact top-K within an eligible subset. The scoped path and corrected Drivine filtered
overload do not use the global index/ANN path as a fallback. If the eligible set exceeds the
candidate budget, the operation fails explicitly instead of returning an approximate partial
answer. Missing embeddings still consume admission budget but cannot produce a scored hit.

The two-argument legacy `findSimilarWithScores(request, query)` default throws
`FILTERED_VECTOR_UNSUPPORTED` unless the backend implements filtering correctly. This changes
the behavior seen by `RetrievalRouter` and `LlmPropositionReviser`: unsupported filtered search
is an explicit failure. The unfiltered overload remains separate. A legacy Drivine filtered
query must specify a context and have a native executor; an unscoped query fails explicitly.

## Java

The small deterministic embedder makes this example runnable without a model service. A host
supplies its own `EmbeddingService` in an application. C has the same vector and cannot displace A.

```java
import com.embabel.common.ai.model.*;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.embabel.dice.proposition.*;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class FilteredVectorsJava {
    public static void main(String[] args) {
        var metadata = EmbeddingServiceMetadata.create("example", "local");
        var embedder = new EmbeddingService() {
            public float[] embed(String text) { return new float[] {1, 0}; }
            public List<float[]> embed(List<String> texts) { return texts.stream().map(this::embed).toList(); }
            public int getDimensions() { return 2; }
            public String getName() { return metadata.getName(); }
            public String getProvider() { return metadata.getProvider(); }
            public PricingModel getPricingModel() { return metadata.getPricingModel(); }
        };
        var store = new InMemoryPropositionRepository(embedder);
        var now = Instant.now();
        for (var owner : List.of("C", "A")) {
            store.save(Proposition.create(owner + "-fact", owner, "A useful fact", List.of(),
                    0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE));
        }
        var budget = new ExactVectorBudget(10, Duration.ofSeconds(5));
        var limits = ScopedReadLimits.configuring().withVectorBudget(budget).limits();
        var session = ScopedPropositionReader.open(store, ContextReadScope.ofValues(List.of("A")), limits)
                .snapshot(now, 2.0);
        var hits = session.findSimilarWithScores(TextSimilaritySearchRequest.create("fact", 0.5, 1));
        if (hits.size() != 1 || !hits.getFirst().getMatch().getContextIdValue().equals("A")) {
            throw new AssertionError("Ineligible row occupied top K");
        }
        System.out.println("FilteredVectorsJava OK");
    }
}
```

## Kotlin

```kotlin
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import java.time.Duration
import java.time.Instant

fun main() {
    val embedder = object : EmbeddingService,
        EmbeddingServiceMetadata by EmbeddingServiceMetadata.create("example", "local") {
        override val dimensions = 2
        override fun embed(text: String) = floatArrayOf(1f, 0f)
        override fun embed(texts: List<String>) = texts.map(::embed)
    }
    val store = InMemoryPropositionRepository(embedder)
    val now = Instant.now()
    listOf("C", "A").forEach { owner ->
        store.save(Proposition.create("$owner-fact", owner, "A useful fact", emptyList(),
            0.9, 0.0, reasoning = null, grounding = emptyList(), created = now,
            revised = now, status = PropositionStatus.ACTIVE))
    }
    val limits = ScopedReadLimits { vectorBudget = ExactVectorBudget(10, Duration.ofSeconds(5)) }
    val session = ScopedPropositionReader.open(store, ContextReadScope.ofValues(listOf("A")), limits)
        .snapshot(now, 2.0)
    val hits = session.findSimilarWithScores(TextSimilaritySearchRequest("fact", 0.5, 1))
    check(hits.size == 1 && hits.single().match.contextId.value == "A")
    println("FilteredVectorsKotlin OK")
}
```

## Cost and timeouts

Scoped exact search is bounded by `ExactVectorBudget(maxCandidates, timeout)`, defaulting to
10,000 eligible rows and five seconds. The eligible-row count includes rows with missing
embeddings. The backend probes one extra row to detect exhaustion before vector scoring.
Resident stores admit candidates before embedding the query. The native store embeds the query
first, then admits candidates in its database statement before scoring. Exceeding the allowance
throws `EXACT_VECTOR_BUDGET_EXCEEDED`; it never returns
a sampled partial result. Missing stored embeddings are omitted from hits. Invalid nonfinite
or dimension-mismatched vectors fail; an empty answer is not evidence that every row had a vector.
A store without an embedder reports scoped vector search unsupported.

For the legacy filtered Drivine overload, Spring binds these finite default-budget keys:

```properties
embabel.dice.store.exact-vector.max-candidates=10000
embabel.dice.store.exact-vector.timeout=5s
```

Manual hosts use `withExactVectorBudget(budget)` and `withNativeReadExecutor(executor)` on the
repository and retain the returned instance. Scoped calls use their bound reader's budget.
An old manually constructed Drivine instance still links, but lacks the executor required for
scoped and corrected legacy filtered vector reads. See [migration](scoped-proposition-reads.md#backends-and-migration).

The native runner supplies the transaction timeout to the driver, and local monotonic checks
run around embedding and before database operations. Deadline exhaustion surfaces as
`EXACT_VECTOR_DEADLINE_EXCEEDED`. The database cooperates in terminating work through its own
timeout mechanism. Its transaction timeout excludes embedding, network and connection-acquisition
latency. Local checks detect elapsed time when control returns; they cannot cancel an
uninterruptible embedder or promise a hard wall-clock bound on external calls. Native reads use
independent committed-data transactions, with no managed retry resetting the allowance.
