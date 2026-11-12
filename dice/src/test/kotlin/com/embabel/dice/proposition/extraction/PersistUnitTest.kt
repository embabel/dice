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
import com.embabel.dice.pipeline.PersistablePropositions
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.revision.RevisionResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException

class PersistUnitTest {

    private val tenant = ContextId("unit-tenant")

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

    private fun persistable(
        props: List<Proposition>,
        new: List<NamedEntityData>,
        updated: List<NamedEntityData>,
    ) = object : PersistablePropositions {
        override val propositions: List<Proposition> = props
        override val revisionResults: List<RevisionResult> = emptyList()
        override fun newEntities(): List<NamedEntityData> = new
        override fun updatedEntities(): List<NamedEntityData> = updated
        override fun referenceOnlyEntities(): List<NamedEntityData> = emptyList()
    }

    private val run = ExtractionRunKey.of("unit-tenant", "run-1")

    @Test
    fun `E1 of(result) holds the propositions the result says to persist, in order`() {
        val props = listOf(
            proposition("Alice likes tea", "p-1"),
            proposition("Alice owns a cat", "p-2"),
            proposition("Alice lives in Oslo", "p-3"),
        )

        val unit = PersistUnit.of(persistable(props, emptyList(), emptyList()))

        assertThat(unit.propositions).containsExactlyElementsOf(props)
    }

    @Test
    fun `E2 both factories drop unreferenced entities and keep referenced ones in input order`() {
        val props = listOf(
            proposition("Alice likes Bob", "p-1", "entity-bob"),
            proposition("Alice likes tea", "p-2", "entity-alice"),
            proposition("Someone likes tea", "p-3", null),
        )
        val new = listOf(entity("entity-alice"), entity("entity-stray"), entity("entity-bob"))
        val updated = listOf(entity("entity-other"), entity("entity-bob"))

        val units = listOf(
            PersistUnit.of(persistable(props, new, updated)),
            PersistUnit.of(props, new, updated),
        )

        units.forEach { unit ->
            assertThat(unit.newEntities.map { it.id }).containsExactly("entity-alice", "entity-bob")
            assertThat(unit.updatedEntities.map { it.id }).containsExactly("entity-bob")
        }
    }

    @Test
    fun `E3 withRun returns a new unit and leaves the receiver without a run`() {
        val unit = PersistUnit.of(listOf(proposition("Alice likes tea", "p-1")), emptyList(), emptyList())

        val withDefault = unit.withRun(run)
        val lenient = unit.withRun(run, LineageFailurePolicy.LENIENT)

        assertThat(unit.run).isNull()
        assertThat(unit.lineagePolicy).isEqualTo(LineageFailurePolicy.DEFAULT)
        assertThat(withDefault).isNotSameAs(unit)
        assertThat(withDefault.run).isEqualTo(run)
        assertThat(withDefault.lineagePolicy).isEqualTo(LineageFailurePolicy.DEFAULT)
        assertThat(lenient.run).isEqualTo(run)
        assertThat(lenient.lineagePolicy).isEqualTo(LineageFailurePolicy.LENIENT)
        assertThat(lenient.propositions).isEqualTo(unit.propositions)
    }

    @Test
    fun `I1 the unit copies its input lists and exposes lists that reject add`() {
        val props = mutableListOf(proposition("Alice likes tea", "p-1"))
        val new = mutableListOf<NamedEntityData>(entity("entity-alice"))
        val updated = mutableListOf<NamedEntityData>(entity("entity-alice"))

        val unit = PersistUnit.of(props, new, updated)
        props.add(proposition("Alice owns a cat", "p-2"))

        assertThat(unit.propositions.map { it.id }).containsExactly("p-1")
        listOf(unit.propositions, unit.newEntities, unit.updatedEntities).forEach { exposed ->
            @Suppress("UNCHECKED_CAST")
            val mutable = exposed as MutableList<Any>
            assertThatThrownBy { mutable.add(entity("entity-extra")) }
                .isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun `E4 persistedOrThrow returns persisted when stored and throws a RuntimeException cause unchanged`() {
        val persisted = PropositionPersistenceResult.EMPTY
        val cause = IllegalStateException("store went away")

        val stored = PersistOutcome.stored(persisted)
        val failed = PersistOutcome.failed(PersistStep.PROPOSITIONS, cause, setOf(PersistStep.ENTITIES))

        assertThat(stored.persistedOrThrow()).isSameAs(persisted)
        assertThatThrownBy { failed.persistedOrThrow() }.isSameAs(cause)
    }

    @Test
    fun `E5 persistedOrThrow wraps a checked cause in PersistUnitFailedException with the step and the cause`() {
        val cause = IOException("store went away")
        val failed = PersistOutcome.failed(PersistStep.PROPOSITIONS, cause, setOf(PersistStep.ENTITIES))

        assertThatThrownBy { failed.persistedOrThrow() }
            .isInstanceOfSatisfying(PersistUnitFailedException::class.java) { thrown ->
                assertThat(thrown.step).isEqualTo(PersistStep.PROPOSITIONS)
                assertThat(thrown.cause).isSameAs(cause)
                assertThat(thrown).hasMessage("persist unit failed at PROPOSITIONS")
            }
    }

    @Test
    fun `R1 stored and failed reject a negative runLinks`() {
        val cause = IllegalStateException("boom")

        assertThatIllegalArgumentException().isThrownBy {
            PersistOutcome.stored(PropositionPersistenceResult.EMPTY, -1, null)
        }
        assertThatIllegalArgumentException().isThrownBy {
            PersistOutcome.failed(PersistStep.RUN_LINKS, cause, emptySet(), -1, null)
        }
    }
}
