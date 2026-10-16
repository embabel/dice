# Scoped proposition reads

`ScopedPropositionReader` binds a store to a finite, nonempty `ContextReadScope` supplied by
the trusted host. Context IDs identify data; possession of an ID is not authorization. Resolve
the caller's grants before constructing the reader and keep that construction outside model tools.

The scope is independent of `PropositionQuery`. `restrict` adds an ALL-OF membership fragment;
later fragments cannot remove earlier conditions. A fragment's context may narrow to one member
of the scope. A context outside it fails. `includingOnly` intersects owner-qualified
`PropositionKey` sets, with an empty set denying every row; `excluding` accumulates exclusions.
Inputs are copied. Duplicate context IDs collapse, blank IDs and an empty scope fail, and
nonblank IDs retain their whitespace.

`snapshot(asOf, rankingDecayK)` freezes the confidence evaluation instant for every operation
on that session. It freezes the read specification, not a database snapshot across calls.
Query ordering and limits are terminal arguments; values on a restriction do not truncate
membership. `count()` counts eligible rows independently of presentation limits. Ordinary
orders use context then proposition ID for equal values. Keyword overlap uses distinct trimmed
tokens, case folding with `Locale.ROOT`, positive substring overlap, then effective confidence,
context and ID. All filters, row restrictions, ordering and global limits apply in the backend
before selected rows are hydrated.

`ScopedReadLimits` defaults to 100 final results, 401 probes and 100 eager results. `maxProbe`
must cover both other caps. Low-level session operations allow up to `maxProbe`; memory output
uses `maxResults`, and eager publication uses `maxEager`. Invalid limits fail explicitly.
For exact vector costs and failures, see [filtered vector search](filtered-vector-search.md).

## Java

This standalone example reads A and B while keeping a row from C out of both count and results.
The compatibility runner extracts and executes this fenced program.

```java
import com.embabel.dice.proposition.*;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import java.time.Instant;
import java.util.List;

public class ScopedReadsJava {
    public static void main(String[] args) {
        var store = new InMemoryPropositionRepository();
        var now = Instant.now();
        for (var owner : List.of("A", "B", "C")) {
            store.save(Proposition.create(owner + "-fact", owner, "A useful fact", List.of(),
                    0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE));
        }
        var limits = ScopedReadLimits.configuring().withMaxResults(10).withMaxProbe(41)
                .withMaxEager(10).limits();
        var reader = ScopedPropositionReader.open(store, ContextReadScope.ofValues(List.of("A", "B")), limits);
        var session = reader.snapshot(now, 2.0);
        if (session.count() != 2 || session.query(ReadOrder.NONE, 10).size() != 2) {
            throw new AssertionError("Scope did not constrain membership");
        }
        var narrowed = session.restrict(PropositionQuery.againstContext("A").withMinConfidence(0.8));
        if (narrowed.count() != 1) throw new AssertionError("Conjunction was lost");
        System.out.println("ScopedReadsJava OK");
    }
}
```

## Kotlin

```kotlin
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import java.time.Instant

fun main() {
    val store = InMemoryPropositionRepository()
    val now = Instant.now()
    listOf("A", "B", "C").forEach { owner ->
        store.save(Proposition.create("$owner-fact", owner, "A useful fact", emptyList(),
            0.9, 0.0, reasoning = null, grounding = emptyList(), created = now,
            revised = now, status = PropositionStatus.ACTIVE))
    }
    val limits = ScopedReadLimits { maxResults = 10; maxProbe = 41; maxEager = 10 }
    val session = ScopedPropositionReader.open(store, ContextReadScope.ofValues(listOf("A", "B")), limits)
        .snapshot(now, 2.0)
    check(session.count() == 2L)
    check(session.query(ReadOrder.NONE, 10).size == 2)
    check(session.restrict(PropositionQuery.againstContext("A").withMinConfidence(0.8)).count() == 1L)
    println("ScopedReadsKotlin OK")
}
```

## Backends and migration

The in-memory and JSON-file stores implement the scoped operations locally. The Drivine store
executes native scoped reads; decorators forward only supported capabilities. An unsupported
store fails with `SCOPED_READ_UNSUPPORTED` or `SCOPED_VECTOR_UNSUPPORTED`, without a whole-store
fallback. Invalid backend rows fail with `SCOPED_READ_RESULT_INVALID`; unexpected backend
errors become `SCOPED_READ_FAILED`, without attaching sensitive backend details.

Manual Drivine construction retains its original JVM constructor signatures. To enable the new
operations, construct a new repository with `withNativeReadExecutor` and the configured driver
and physical database resolved by `Neo4jScopedReadExecutor.from`:

```text
repository.withNativeReadExecutor(
    Neo4jScopedReadExecutor.from(persistenceManager, databaseRegistry, dataSourceMap))
```

The returned repository must replace the old reference. A datasource alias is not necessarily
the physical database name. Spring auto-configuration wires this executor when its registry and
datasource map are available; otherwise scoped reads remain explicitly unsupported.

Native reads open their own read transaction and observe committed data, even within an ambient
Drivine write transaction. Selection and bounded hydration share that transaction. They do not
see the ambient transaction's uncommitted writes. Each call has its own transaction; a session
does not promise repeatable database contents across terminal calls. The driver transaction
timeout cooperates with database enforcement and local monotonic checks guard subsequent
operations. Connection acquisition, embedding and network latency require separate consideration;
see [filtered vector search](filtered-vector-search.md#cost-and-timeouts).
