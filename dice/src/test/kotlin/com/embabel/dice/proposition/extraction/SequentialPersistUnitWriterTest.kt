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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SequentialPersistUnitWriterTest {

    private val tenant = ContextId("unit-tenant")

    private val key = ExtractionRunKey.of("unit-tenant", "run-1")

    /** Every repository and store call, in the order the writer made them. */
    private val calls = mutableListOf<String>()

    private val entityRepository = mockk<NamedEntityDataRepository>(relaxed = true).also { repo ->
        every { repo.save(any()) } answers {
            calls += "save ${firstArg<NamedEntityData>().id}"
            firstArg()
        }
        every { repo.update(any()) } answers {
            calls += "update ${firstArg<NamedEntityData>().id}"
            firstArg()
        }
        every { repo.mergeRelationship(any(), any(), any()) } answers {
            calls += "merge ${thirdArg<RelationshipData>().name}"
        }
    }

    private val propositionRepository = mockk<PropositionRepository>(relaxed = true).also { repo ->
        every { repo.saveAllReturningCanonical(any()) } answers {
            calls += "propositions"
            val saved = firstArg<Collection<Proposition>>().toList()
            PropositionPersistenceResult.of(saved, saved)
        }
    }

    private fun proposition(text: String, id: String, entityId: String? = "entity-alice") = Proposition(
        id = id,
        contextId = tenant,
        text = text,
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = entityId, role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    private fun entity(id: String) = SimpleNamedEntityData(
        id = id,
        name = id,
        description = "test",
        labels = setOf("Person"),
        properties = emptyMap(),
    )

    private val p = proposition("Alice likes tea", "p-1")

    private fun unitWithEntity() = PersistUnit.of(listOf(p), listOf(entity("entity-alice")), emptyList())

    /**
     * A run link store that records each link call and answers with [answer].
     *
     * @param answer What a link call returns, or throws.
     */
    private inner class RecordingLinkStore(
        private val answer: () -> Int,
    ) : PropositionRunLinkStore {
        override fun link(key: ExtractionRunKey, propositionIds: Collection<String>): Int {
            calls += "link $propositionIds"
            return answer()
        }

        override fun runsOf(contextIdValue: String, propositionId: String, limit: Int): List<ExtractionRunRef> =
            emptyList()

        override fun propositionsOf(key: ExtractionRunKey, limit: Int): List<String> = emptyList()
    }

    private val linkFailure = IllegalStateException("link store down")

    private fun throwingLinkStore() = RecordingLinkStore { throw linkFailure }

    private fun writer(runLinkStore: PropositionRunLinkStore?) =
        PersistUnitWriter.sequential(propositionRepository, entityRepository, runLinkStore)

    @Test
    fun `E1 a successful write saves entities, updates entities, saves propositions, links the run, then merges relationships`() {
        val bob = proposition("Bob owns a cat", "p-2", entityId = "entity-bob")
        val unit = PersistUnit.of(listOf(p, bob), listOf(entity("entity-alice")), listOf(entity("entity-bob")))
            .withRun(key)

        val outcome = writer(RecordingLinkStore { 2 }).write(unit)

        assertThat(outcome).isInstanceOf(PersistOutcome.Stored::class.java)
        assertThat(outcome.runLinks).isEqualTo(2)
        assertThat(calls.take(4)).containsExactly("save entity-alice", "update entity-bob", "propositions", "link [p-1, p-2]")
        assertThat(calls.drop(4)).isNotEmpty.allMatch { it.startsWith("merge ") }
    }

    @Test
    fun `E2 a failing entity save fails at ENTITIES and never saves propositions`() {
        val failure = IllegalStateException("entity store down")
        every { entityRepository.save(any()) } throws failure

        val outcome = writer(null).write(unitWithEntity())

        assertThat(outcome).isInstanceOf(PersistOutcome.Failed::class.java)
        val failed = outcome as PersistOutcome.Failed
        assertThat(failed.step).isEqualTo(PersistStep.ENTITIES)
        assertThat(failed.cause).isSameAs(failure)
        assertThat(failed.leftInStore).containsExactly(PersistStep.ENTITIES)
        verify(exactly = 0) { propositionRepository.saveAllReturningCanonical(any()) }
    }

    @Test
    fun `E3 a failing proposition save on a unit with no entities leaves only PROPOSITIONS in the store`() {
        every { propositionRepository.saveAllReturningCanonical(any()) } throws IllegalStateException("down")

        val outcome = writer(null).write(PersistUnit.of(listOf(p), emptyList(), emptyList())) as PersistOutcome.Failed

        assertThat(outcome.step).isEqualTo(PersistStep.PROPOSITIONS)
        assertThat(outcome.leftInStore).containsExactly(PersistStep.PROPOSITIONS)
    }

    @Test
    fun `E4 under STRICT a throwing link store fails at RUN_LINKS and merges no relationship`() {
        val unit = unitWithEntity().withRun(key, LineageFailurePolicy.STRICT)

        val outcome = writer(throwingLinkStore()).write(unit) as PersistOutcome.Failed

        assertThat(outcome.step).isEqualTo(PersistStep.RUN_LINKS)
        assertThat(outcome.cause).isInstanceOf(LineageNotRecordedException::class.java)
        assertThat(outcome.cause.cause).isSameAs(linkFailure)
        assertThat(outcome.lineageFailure).isSameAs(outcome.cause)
        assertThat(outcome.leftInStore)
            .containsExactly(PersistStep.ENTITIES, PersistStep.PROPOSITIONS, PersistStep.RUN_LINKS)
        verify(exactly = 0) { entityRepository.mergeRelationship(any(), any(), any()) }
    }

    @Test
    fun `E5 under STRICT a run with no link store bound fails at RUN_LINKS with only entities and propositions stored`() {
        val unit = unitWithEntity().withRun(key, LineageFailurePolicy.STRICT)

        val outcome = writer(null).write(unit) as PersistOutcome.Failed

        assertThat(outcome.step).isEqualTo(PersistStep.RUN_LINKS)
        assertThat(outcome.leftInStore).containsExactly(PersistStep.ENTITIES, PersistStep.PROPOSITIONS)
    }

    @Test
    fun `E6 under LENIENT a throwing link store still stores the unit and merges relationships`() {
        val unit = unitWithEntity().withRun(key, LineageFailurePolicy.LENIENT)

        val outcome = writer(throwingLinkStore()).write(unit)

        assertThat(outcome).isInstanceOf(PersistOutcome.Stored::class.java)
        assertThat(outcome.lineageFailure).isNotNull
        assertThat(outcome.runLinks).isNull()
        verify(atLeast = 1) { entityRepository.mergeRelationship(any(), any(), any()) }
    }

    @Test
    fun `E7 under LENIENT a failing relationship merge after a failed link carries the lineage failure`() {
        every { entityRepository.mergeRelationship(any(), any(), any()) } throws IllegalStateException("graph down")
        val unit = unitWithEntity().withRun(key, LineageFailurePolicy.LENIENT)

        val outcome = writer(throwingLinkStore()).write(unit) as PersistOutcome.Failed

        assertThat(outcome.step).isEqualTo(PersistStep.STRUCTURAL_RELATIONSHIPS)
        assertThat(outcome.lineageFailure).isNotNull
    }

    @Test
    fun `E8 a unit with no run never calls the link store`() {
        val outcome = writer(RecordingLinkStore { 1 }).write(unitWithEntity())

        assertThat(outcome).isInstanceOf(PersistOutcome.Stored::class.java)
        assertThat(outcome.runLinks).isNull()
        assertThat(calls).noneMatch { it.startsWith("link ") }
    }

    @Test
    fun `E9 a link store that links nothing gives a stored outcome with zero run links`() {
        val outcome = writer(RecordingLinkStore { 0 }).write(unitWithEntity().withRun(key))

        assertThat(outcome).isInstanceOf(PersistOutcome.Stored::class.java)
        assertThat(outcome.runLinks).isEqualTo(0)
    }

    @Test
    fun `E10 relationships are merged against the propositions the repository returned`() {
        val minted = proposition("Alice likes tea", "minted")
        val canonical = proposition("Alice likes tea", "canonical")
        every { propositionRepository.saveAllReturningCanonical(any()) } returns
            PropositionPersistenceResult.of(listOf(minted), listOf(canonical))
        val named = mutableListOf<String>()
        every { entityRepository.mergeRelationship(any(), any(), any()) } answers {
            listOf(firstArg<RetrievableIdentifier>(), secondArg<RetrievableIdentifier>())
                .filter { it.type == "Proposition" }
                .forEach { named += it.id }
        }

        writer(null).write(PersistUnit.of(listOf(minted), emptyList(), emptyList()))

        assertThat(named).isNotEmpty.containsOnly("canonical")
    }

    @Test
    fun `E11 an Error thrown by a repository propagates out of write`() {
        val error = Error("store crashed")
        every { propositionRepository.saveAllReturningCanonical(any()) } throws error

        assertThatThrownBy { writer(null).write(unitWithEntity()) }.isSameAs(error)
    }

    @Test
    fun `E12 both lineage failure messages match the extraction service's messages`() {
        val unbound = writer(null).write(unitWithEntity().withRun(key)) as PersistOutcome.Failed
        val throwing = writer(throwingLinkStore()).write(unitWithEntity().withRun(key)) as PersistOutcome.Failed

        assertEquals(
            "analysis carries extraction run run-1 and no PropositionRunLinkStore is bound, so its " +
                "propositions cannot be attributed; bind one with withRunLineage, or bind " +
                "LineageFailurePolicy.LENIENT to accept the gap",
            unbound.cause.message,
        )
        assertEquals("could not attribute 1 proposition(s) to extraction run run-1", throwing.cause.message)
    }

    @Test
    fun `E13 a link store that returns a negative count fails at RUN_LINKS and merges no relationship`() {
        val outcome = writer(RecordingLinkStore { -1 }).write(unitWithEntity().withRun(key)) as PersistOutcome.Failed

        assertThat(outcome.step).isEqualTo(PersistStep.RUN_LINKS)
        assertThat(outcome.cause).isInstanceOf(IllegalStateException::class.java)
        assertEquals("run link store returned a negative count: -1", outcome.cause.message)
        assertThat(outcome.lineageFailure).isNull()
        assertThat(outcome.runLinks).isNull()
        assertThat(outcome.leftInStore)
            .containsExactly(PersistStep.ENTITIES, PersistStep.PROPOSITIONS, PersistStep.RUN_LINKS)
        verify(exactly = 0) { entityRepository.mergeRelationship(any(), any(), any()) }
    }
}
