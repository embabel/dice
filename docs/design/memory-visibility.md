# Memory visibility

`PolicyReadView` adds finite row grants and field projection to a scoped reader. The trusted
host supplies a `ReadPolicyStamp` and a `RowPolicy`; DICE does not authenticate the principal,
resolve grants or verify the host's source-eligibility attestation. Treat these objects as host
configuration, never as model-supplied tool arguments.

Each `RowGrant` names a `PropositionKey(contextIdValue, propositionId)` and requires
`sourceEligibilityAttested=true`. Grants are copied and validated, with at most 1,000 supplied
grants before duplicate collapse and 1 MiB of literal projected text across the resulting unique
grants. Identical duplicate grants collapse; conflicting
duplicates fail. Reusing a version string does not preserve an earlier policy's content or
principal. Replace the immutable view when grants change.

## Java

This listing-only strict view grants one literal summary. The original text, identity,
confidence, metadata and sources are absent from its `ProjectedMemory` result.

```java
import com.embabel.dice.agent.*;
import com.embabel.dice.proposition.*;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class MemoryVisibilityJava {
    public static void main(String[] args) {
        var store = new InMemoryPropositionRepository();
        var now = Instant.now();
        store.save(Proposition.create("fact", "A", "Private source text", List.of(),
                0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE));
        var projection = RowProjection.projecting(new ApprovedText.Literal("Approved summary"))
                .withFields(Set.of()).projection();
        var grant = RowGrant.of(new PropositionKey("A", "fact"), projection, true, false);
        var stamp = new ReadPolicyStamp("host-principal", "recall", "host-policy", "1");
        var policy = RowPolicy.of(stamp, List.of(grant), HiddenFieldInfluence.STRICT);
        var reader = ScopedPropositionReader.open(store, ContextReadScope.ofValues(List.of("A")));
        var view = PolicyReadView.open(reader, policy);
        var row = view.snapshot(now, 2.0).list(1).getFirst();
        if (!"Approved summary".equals(row.getText()) || row.getId() != null || row.getOwner() != null
                || row.getConfidence() != null || !row.getMetadata().isEmpty() || !row.getSources().isEmpty()) {
            throw new AssertionError("Projection disclosed hidden fields");
        }
        var memory = PolicyMemory.configuring(view).withPlan(PolicyMemoryPlan.listingOnly()).memory();
        if (memory.tools().size() != 1) throw new AssertionError("Missing policy tool");
        System.out.println("MemoryVisibilityJava OK");
    }
}
```

## Kotlin

```kotlin
import com.embabel.dice.agent.*
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import java.time.Instant

fun main() {
    val store = InMemoryPropositionRepository()
    val now = Instant.now()
    store.save(Proposition.create("fact", "A", "Private source text", emptyList(),
        0.9, 0.0, reasoning = null, grounding = emptyList(), created = now,
        revised = now, status = PropositionStatus.ACTIVE))
    val projection = RowProjection(ApprovedText.Literal("Approved summary")) { fields = emptySet() }
    val grant = RowGrant.of(PropositionKey("A", "fact"), projection, true, false)
    val policy = RowPolicy.of(ReadPolicyStamp("host-principal", "recall", "host-policy", "1"),
        listOf(grant), HiddenFieldInfluence.STRICT)
    val reader = ScopedPropositionReader.open(store, ContextReadScope.ofValues(listOf("A")))
    val view = PolicyReadView.open(reader, policy)
    val row = view.snapshot(now, 2.0).list(1).single()
    check(row.text == "Approved summary" && row.id == null && row.owner == null)
    check(row.confidence == null && row.metadata.isEmpty() && row.sources.isEmpty())
    val memory = PolicyMemory(view) { plan = PolicyMemoryPlan { searchTiers = emptySet() } }
    check(memory.tools().size == 1)
    println("MemoryVisibilityKotlin OK")
}
```

## Strict influence and output

The default `HiddenFieldInfluence.STRICT` validates every read dependency before backend work.
Row membership applies before candidate generation, counts, ranking, limits and hydration in
both influence modes. Projection happens before fusion, rendering, eager publication and
provenance resolution. `ProjectedMemory` retains approved output only, without a raw proposition,
internal row key or policy envelope.

`ApprovedText.Omitted`, `Stored` and `Literal` select absent, full stored or host-supplied text.
`MemoryField` controls explicit nullable system fields, `metadataKeys` controls individual metadata
entries, and `AllowedSource` separately approves source descriptor and locator output. Raw
confidence and effective confidence are distinct grants. `TEMPORAL` exposes timestamps only,
without cross-row relationship IDs. Entity handles may be host-supplied opaque labels; raw
entity IDs require `ENTITY_IDS`.

Strict filters and ordering require approval for the fields they inspect: status, level,
confidence, importance, reinforcement, pinning, creation/access times, entities and cached trust.
Revision predicates and `REVISED_DESC` use `lastTouched = max(contentRevised, metadataRevised)`
and require both `CONTENT_REVISED` and `METADATA_REVISED`. An effective-confidence grant permits
the frozen metric computation without exposing its raw inputs. A trust grant permits its cached
value without granting arbitrary metadata.

Strict listing uses the host grant order, avoiding influence from hidden database ordering
fields. Keyword search scores only approved stored or literal text, with grant-order ties.
Related search needs approved entity IDs. Strict vector search requires full stored text,
explicit `storedEmbeddingAllowed=true` attestation, and every filter/tie dependency, including
effective confidence and owner/ID. `SCORE` controls numeric score output; it neither grants text
or embeddings nor makes hidden scoring dependencies acceptable. Approved search material can
be ranked without exposing the numeric score.

`PolicyMemoryPlan.listingOnly()` enables listing without search tiers. Explicit plans can enable
`VECTOR`, `KEYWORD` and `RELATED`; an unsupported plan or missing strict backend capability fails
before candidate work. `ALLOW_SEARCH_INFLUENCE` deliberately permits hidden fields to affect
search. It still enforces row scope and output projection. Select that mode only when the host
permits this influence; it is not a redaction substitute for strict search.

`PolicyProvenanceResolver.resolve(stamp, requests)` receives the `ReadPolicyStamp` and a list of
`PolicyProvenanceRequest` values. Each request names a selected row's approved `PolicySourceKey`
and its descriptor/locator permission flags. The resolver is a trusted host boundary and may see
those internal source and proposition keys; they are not automatically exposed to the model.
Its returned `Map<PolicySourceKey, ProjectedSource>` must contain only requested keys and permitted
descriptor/locator fields. Unsolicited keys or fields abort publication. Eager publication and
lazy exclusion share the scoped memory rules and finite
limits in [multi-context memory](authorized-multi-context-memory.md). Grant changes require a
fresh view; already published model context cannot be retracted by replacing a policy.
