/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.pipeline

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.PropositionPersisted
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.EventEmittingPropositionRepository
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.RelationshipTypes
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.UriLocator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * What a save landed on, and what happens to the edges written afterwards.
 *
 * The thing being pinned is one substitution. `DrivinePropositionRepository` answers a fresh insert
 * of text it already holds with the proposition it already holds — a different id. Everything
 * downstream that writes an edge against the id extraction minted then points at a node that was
 * never stored. These tests run over a repository that deduplicates the same way, because one that
 * never deduplicates cannot tell the two paths apart.
 */
class CanonicalPersistenceResultTest {

    private val tenant = ContextId("canonical-tenant")
    private val schema: DataDictionary = DataDictionary.fromClasses("canonical")

    /**
     * `DrivinePropositionRepository`'s dedup rule, in memory: a *new* id carrying text already
     * stored in the same context collapses onto the stored proposition, and `save` returns that
     * one. An update to an id already stored writes to its own node.
     */
    private class DeduplicatingRepository(
        private val delegate: PropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {

        override fun save(proposition: Proposition): Proposition {
            val existing = delegate.findAll().firstOrNull {
                it.contextId == proposition.contextId &&
                    it.text == proposition.text &&
                    it.id != proposition.id
            }
            val isUpdate = delegate.findById(proposition.id) != null
            return if (existing != null && !isUpdate) existing else delegate.save(proposition)
        }

        override fun saveAll(propositions: Collection<Proposition>) {
            propositions.forEach { save(it) }
        }

        // `by delegate` would forward the interface default straight past this class's save.
        override fun saveAllReturningCanonical(
            propositions: Collection<Proposition>,
        ): PropositionPersistenceResult {
            val inputs = propositions.toList()
            return PropositionPersistenceResult.of(inputs, inputs.map { save(it) })
        }
    }

    /**
     * The dedup rule plus the evidence merge that goes with it, as `DrivinePropositionRepository`
     * does it: a collapsed insert does not just answer with the winner, it writes the incoming
     * evidence into the winner and answers with the winner as it now stands. `reinforceCount`
     * stands in for that merge, so a stale answer is visible as a number.
     */
    private class MergingDeduplicatingRepository(
        private val delegate: PropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {

        override fun save(proposition: Proposition): Proposition {
            val existing = delegate.findAll().firstOrNull {
                it.contextId == proposition.contextId &&
                    it.text == proposition.text &&
                    it.id != proposition.id
            }
            val isUpdate = delegate.findById(proposition.id) != null
            if (existing == null || isUpdate) return delegate.save(proposition)
            return delegate.save(existing.copy(reinforceCount = existing.reinforceCount + 1))
        }

        override fun saveAll(propositions: Collection<Proposition>) {
            propositions.forEach { save(it) }
        }

        override fun saveAllReturningCanonical(
            propositions: Collection<Proposition>,
        ): PropositionPersistenceResult {
            val inputs = propositions.toList()
            return PropositionPersistenceResult.of(inputs, inputs.map { save(it) })
        }
    }

    private fun proposition(
        text: String,
        id: String,
        grounding: List<String> = listOf("chunk-1"),
    ) = Proposition(
        id = id,
        contextId = tenant,
        text = text,
        mentions = listOf(
            EntityMention(
                span = "Alice",
                type = "Person",
                resolvedId = "entity-alice",
                role = MentionRole.SUBJECT,
            ),
        ),
        confidence = 0.9,
        grounding = grounding,
    )

    private fun persistable(vararg propositions: Proposition): PersistablePropositions =
        object : PersistablePropositions {
            override val propositions: List<Proposition> = propositions.toList()
            override val revisionResults: List<RevisionResult> = emptyList()
            override fun newEntities(): List<NamedEntityData> = emptyList()
            override fun updatedEntities(): List<NamedEntityData> = emptyList()
            override fun referenceOnlyEntities(): List<NamedEntityData> = emptyList()
        }

    private fun edgeTargets(repo: TrackingEntityRepository, type: String): List<String> =
        repo.relationshipsOfType(type).map { it.target.id }

    private fun edgeSources(repo: TrackingEntityRepository, type: String): List<String> =
        repo.relationshipsOfType(type).map { it.source.id }

    // ---- what the store hands back ----

    @Test
    fun `saveAll drops the canonical id and the new call keeps it`() {
        val repository = DeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))
        val minted = proposition("Alice likes coffee", id = "minted-by-extraction")

        // The old call returns Unit. Everything a caller could learn about where the write landed
        // is gone by the time it returns.
        repository.saveAll(listOf(minted))
        assertThat(repository.count()).isEqualTo(1)

        val result = repository.saveAllReturningCanonical(listOf(minted))

        assertThat(result.canonicalIdOf("minted-by-extraction")).isEqualTo("canonical")
        assertThat(result.canonicalIds).containsExactly("canonical")
        assertThat(result.dedupedInputIds).containsExactly("minted-by-extraction")
        assertThat(result.isIdentity).isFalse()
    }

    @Test
    fun `with nothing to deduplicate the mapping is the identity`() {
        val repository = DeduplicatingRepository()

        val result = repository.saveAllReturningCanonical(
            listOf(
                proposition("Alice likes coffee", id = "one"),
                proposition("Bob likes tea", id = "two"),
            ),
        )

        assertThat(result.isIdentity).isTrue()
        assertThat(result.canonicalIds).containsExactly("one", "two")
        assertThat(result.canonicalIdByInputId["one"]).isEqualTo("one")
        assertThat(result.canonicalIdByInputId["two"]).isEqualTo("two")
    }

    @Test
    fun `two inputs that deduplicate onto one appear twice positionally and once distinctly`() {
        val repository = DeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))

        val result = repository.saveAllReturningCanonical(
            listOf(
                proposition("Alice likes coffee", id = "minted-a"),
                proposition("Alice likes coffee", id = "minted-b"),
            ),
        )

        assertThat(result.canonicalPropositions.map { it.id })
            .containsExactly("canonical", "canonical")
        assertThat(result.canonicalIds).containsExactly("canonical")
    }

    @Test
    fun `an empty batch is the empty result`() {
        val result = DeduplicatingRepository().saveAllReturningCanonical(emptyList())

        assertThat(result.canonicalPropositions).isEmpty()
        assertThat(result.canonicalIds).isEmpty()
        assertThat(result.isIdentity).isTrue()
    }

    @Test
    fun `one input id twice resolves every position to the object that ended up stored`() {
        // A batch can name one id twice — two revision results touching one original, say. An
        // ordinary replace-by-id store answers the first save with the first object and then
        // overwrites it, so the first answer is stale the moment the second save lands. Both answers
        // carry the same id, so the "landed on two different ids" guard cannot see it.
        //
        // Resolving rather than rejecting, deliberately: `of` is called from
        // persistReturningCanonical *after* the saves have run, so throwing there would fail
        // an extraction whose propositions are already written. Last-write-wins is also simply what
        // the store holds.
        val repository = DeduplicatingRepository()
        val first = proposition("Alice likes coffee", id = "same-id")
        val second = first.copy(text = "Alice likes coffee, strongly")

        val result = repository.saveAllReturningCanonical(listOf(first, second))

        assertThat(result.canonicalPropositions).hasSize(2)
        assertThat(result.canonicalPropositions.map { it.text })
            .describedAs("no position may hold the object the second save overwrote")
            .containsExactly("Alice likes coffee, strongly", "Alice likes coffee, strongly")
        assertThat(result.canonicalIds).containsExactly("same-id")
        assertThat(result.canonicalIdOf("same-id")).isEqualTo("same-id")
        // And what the store actually holds is what the result reports.
        assertThat(repository.findById("same-id")?.text).isEqualTo("Alice likes coffee, strongly")
    }

    @Test
    fun `two inputs converging on one canonical both report the store's final object`() {
        // The dedup case, where the second save also *updates* the canonical — which is exactly
        // what DrivinePropositionRepository does: it unions the incoming provenance into the winner
        // and returns the winner as it now stands. Resolving by input id is not enough here,
        // because minted-a and minted-b are different input ids; the first position would keep the
        // canonical as it looked before minted-b's evidence merged in, and structural wiring,
        // projection and grounding would run over that stale object.
        val repository = MergingDeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))

        val result = repository.saveAllReturningCanonical(
            listOf(
                proposition("Alice likes coffee", id = "minted-a"),
                proposition("Alice likes coffee", id = "minted-b"),
            ),
        )

        assertThat(result.canonicalPropositions.map { it.reinforceCount })
            .describedAs("both positions report the canonical as it finally stands")
            .containsExactly(2, 2)
        assertThat(result.canonicalIds).containsExactly("canonical")
        assertThat(repository.findById("canonical")?.reinforceCount).isEqualTo(2)
    }

    @Test
    fun `a canonical result from another tenant is rejected`() {
        // Lineage would refuse to link it — the run link store resolves every proposition inside the
        // run's tenant — but lineage is the last step of the pipeline, and it only runs when the
        // analysis carries a run. By the time it could refuse, structural wiring, projection and
        // grounding have already run over a foreign-tenant object, writing this tenant's edges
        // against a neighbour's claim; an analysis with no run never reaches that check at all.
        // The check belongs where the object enters the pipeline.
        val mine = proposition("Alice likes coffee", id = "mine")
        val theirs = mine.copy(id = "theirs", contextId = ContextId("someone-else"))

        assertThatIllegalArgumentException().isThrownBy {
            PropositionPersistenceResult.of(inputs = listOf(mine), canonical = listOf(theirs))
        }.withMessageContaining("another context")
    }

    @Test
    fun `one canonical id answered under two contexts is rejected`() {
        // The hole the per-position tenant check leaves open. Both positions pass it — input A was
        // answered with an A-tenant object, input B with a B-tenant one — and both answers carry the
        // same id, so the "two different stored ids" check passes too. Then final-answer
        // normalization, keyed by canonical id alone, overwrites the A answer with the B one and
        // hands position 0 a foreign-context proposition. Every guard individually satisfied, and
        // the composition still wrong.
        //
        // One id is one proposition in one tenant — the graph carries a uniqueness constraint
        // saying so — and a batch claiming otherwise is describing a store that cannot exist.
        val underA = proposition("Alice likes coffee", id = "shared")
        val underB = underA.copy(contextId = ContextId("someone-else"))

        assertThatIllegalArgumentException().isThrownBy {
            PropositionPersistenceResult.of(
                inputs = listOf(underA, underB),
                canonical = listOf(underA, underB),
            )
        }.withMessageContaining("under two different contexts")
    }

    @Test
    fun `co-deduplicated inputs are one unit of downstream work`() {
        // The positional list repeats by design, so callers can line results up with what they
        // passed. Handing that list to projection, grounding and structural wiring means the same
        // stored proposition is projected twice and grounded twice, which is duplicate work and —
        // because the projection recorder writes one record per result — duplicate audit rows.
        val repository = DeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))

        val result = repository.saveAllReturningCanonical(
            listOf(
                proposition("Alice likes coffee", id = "minted-a"),
                proposition("Alice likes coffee", id = "minted-b"),
            ),
        )

        assertThat(result.canonicalPropositions.map { it.id })
            .describedAs("the positional view still lines up with the inputs")
            .containsExactly("canonical", "canonical")
        assertThat(result.distinctCanonicalPropositions.map { it.id })
            .describedAs("the downstream view is one entry per stored proposition")
            .containsExactly("canonical")
        assertThat(result.distinctCanonicalPropositions.map { it.id })
            .isEqualTo(result.canonicalIds)
    }

    @Test
    fun `structural wiring runs once per stored proposition, not once per input`() {
        val repository = DeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))
        val entities = TrackingEntityRepository(schema)

        persistable(
            proposition("Alice likes coffee", id = "minted-a"),
            proposition("Alice likes coffee", id = "minted-b"),
        ).persistReturningCanonical(repository, entities)

        assertThat(edgeTargets(entities, RelationshipTypes.HAS_PROPOSITION))
            .describedAs("one HAS_PROPOSITION edge, not the same merge issued twice")
            .containsExactly("canonical")
    }

    @Test
    fun `a repeated input id that lands on two different stored ids is still rejected`() {
        // Resolution is for one id answered twice with the same identity. Two different canonical
        // ids for one input id is an incoherent backend, and staying loud about that is worth more
        // than guessing which one is real.
        val one = proposition("Alice likes coffee", id = "same-id")
        val landedElsewhere = proposition("Alice likes coffee", id = "somewhere-else")

        assertThatIllegalArgumentException().isThrownBy {
            PropositionPersistenceResult.of(
                inputs = listOf(one, one),
                canonical = listOf(one, landedElsewhere),
            )
        }.withMessageContaining("two different stored ids")
    }

    @Test
    fun `a backend that answers fewer propositions than it was given is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            PropositionPersistenceResult.of(
                inputs = listOf(proposition("Alice likes coffee", id = "one")),
                canonical = emptyList(),
            )
        }.withMessageContaining("every proposition it was given")
    }

    // ---- which id the edges are written against ----

    @Test
    fun `persist wires structural edges against the id extraction minted`() {
        // The behaviour on main, pinned so the new variant is visibly a second path rather than a
        // silent replacement. The HAS_PROPOSITION edge points at a node the store does not hold.
        val repository = DeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))
        val entities = TrackingEntityRepository(schema)

        persistable(proposition("Alice likes coffee", id = "minted")).persist(repository, entities)

        assertThat(edgeTargets(entities, RelationshipTypes.HAS_PROPOSITION)).containsExactly("minted")
        assertThat(repository.findById("minted")).isNull()
    }

    @Test
    fun `the new variant wires structural edges against the canonical id`() {
        val repository = DeduplicatingRepository()
        repository.save(proposition("Alice likes coffee", id = "canonical"))
        val entities = TrackingEntityRepository(schema)

        val result = persistable(proposition("Alice likes coffee", id = "minted"))
            .persistReturningCanonical(repository, entities)

        assertThat(result.canonicalIdOf("minted")).isEqualTo("canonical")
        assertThat(edgeTargets(entities, RelationshipTypes.HAS_PROPOSITION)).containsExactly("canonical")
        assertThat(edgeSources(entities, RelationshipTypes.MENTIONS)).containsExactly("canonical")
        assertThat(repository.findById("canonical")).isNotNull()
    }

    @Test
    fun `with nothing deduplicated both variants write the same edges`() {
        // The compatibility claim in one test: with no substitution the new path is the old path.
        // Anything else would be a behaviour change hiding behind a dedup-only assertion.
        val legacy = TrackingEntityRepository(schema)
        val canonical = TrackingEntityRepository(schema)
        val one = proposition("Alice likes coffee", id = "one")
        val two = proposition("Bob likes tea", id = "two", grounding = listOf("chunk-2"))

        persistable(one, two).persist(DeduplicatingRepository(), legacy)
        persistable(one, two).persistReturningCanonical(DeduplicatingRepository(), canonical)

        assertThat(canonical.createdRelationships).isEqualTo(legacy.createdRelationships)
    }

    @Test
    fun `the new variant persists the same propositions persist does`() {
        val viaPersist = DeduplicatingRepository()
        val viaCanonical = DeduplicatingRepository()
        val one = proposition("Alice likes coffee", id = "one")
        val two = proposition("Bob likes tea", id = "two")

        persistable(one, two).persist(viaPersist, TrackingEntityRepository(schema))
        persistable(one, two).persistReturningCanonical(viaCanonical, TrackingEntityRepository(schema))

        assertThat(viaCanonical.findAll().map { it.id }.sorted())
            .isEqualTo(viaPersist.findAll().map { it.id }.sorted())
    }

    // ---- the decorator keeps emitting ----

    @Test
    fun `the event-emitting decorator emits for the new call and reports canonical ids`() {
        // The by-delegate trap, asserted rather than argued. `PropositionRepository by delegate`
        // generates a forwarder for every interface member the class does not declare, so an
        // inherited default body would run against the *delegate's* save and this decorator would
        // emit nothing. The override is what stops that, and nothing else in the suite would notice
        // if it were deleted.
        val delegate = DeduplicatingRepository()
        delegate.save(proposition("Alice likes coffee", id = "canonical"))
        val emitted = mutableListOf<DiceEvent>()
        val decorated = EventEmittingPropositionRepository(delegate) { emitted += it }

        val result = decorated.saveAllReturningCanonical(
            listOf(
                proposition("Alice likes coffee", id = "minted"),
                proposition("Bob likes tea", id = "fresh"),
            ),
        )

        assertThat(emitted.filterIsInstance<PropositionPersisted>().map { it.proposition.id })
            .describedAs("one event per proposition, carrying what was stored")
            .containsExactly("canonical", "fresh")
        assertThat(result.canonicalIdOf("minted")).isEqualTo("canonical")
        assertThat(result.canonicalIdOf("fresh")).isEqualTo("fresh")
    }

    // ---- run identity stays out of source provenance ----

    @Test
    fun `run identity is not part of provenance or locator equality`() {
        // Two claims read from one source under two different runs have equal provenance, and this
        // is where that is nailed down. Folding run identity into source identity would make
        // evidence from two runs over one document look like evidence from two documents — and it
        // would change what `SourceLocator.key()` means, which is the `:Source` node's key.
        val locator = UriLocator("https://example.com/doc")
        val underOneRun = ProvenanceEntry(locator = locator, chunkId = "chunk-1")
        val underAnotherRun = ProvenanceEntry(locator = locator, chunkId = "chunk-1")

        assertThat(underOneRun).isEqualTo(underAnotherRun)
        assertThat(underOneRun.hashCode()).isEqualTo(underAnotherRun.hashCode())
        assertThat(locator.key()).isEqualTo(UriLocator("https://example.com/doc").key())

        // Structural, not just behavioural: neither type has anywhere to put a run.
        val provenanceFields = ProvenanceEntry::class.java.declaredFields.map { it.name }
        assertThat(provenanceFields).noneMatch { it.contains("run", ignoreCase = true) }
        assertThat(SourceLocator::class.java.declaredFields.map { it.name })
            .noneMatch { it.contains("run", ignoreCase = true) }
    }
}
