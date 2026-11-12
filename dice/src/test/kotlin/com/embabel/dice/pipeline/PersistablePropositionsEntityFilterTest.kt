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
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Both persist paths save and update only the entities a saved proposition mentions.
 */
class PersistablePropositionsEntityFilterTest {

    private val tenant = ContextId("unit-tenant")

    private val alice = entity("entity-alice")

    private val unused = entity("entity-unused")

    @Test
    fun `persist saves and updates only the referenced entity`() {
        val repo = entityRepository()

        persistable().persist(InMemoryPropositionRepository(), repo)

        verifySavedAndUpdatedOnlyAlice(repo)
    }

    @Test
    fun `persistCanonicalPropositions saves and updates only the referenced entity`() {
        val repo = entityRepository()

        persistable().persistCanonicalPropositions(InMemoryPropositionRepository(), repo)

        verifySavedAndUpdatedOnlyAlice(repo)
    }

    /**
     * Checks that the repository saw one save and one update, both for Alice.
     *
     * @param repo The mocked entity repository the persist call wrote to.
     */
    private fun verifySavedAndUpdatedOnlyAlice(repo: NamedEntityDataRepository) {
        verify(exactly = 1) { repo.save(any()) }
        verify(exactly = 1) { repo.save(alice) }
        verify(exactly = 1) { repo.update(any()) }
        verify(exactly = 1) { repo.update(alice) }
    }

    /**
     * A relaxed entity repository whose save and update hand back the entity they were given.
     *
     * @return The mock.
     */
    private fun entityRepository(): NamedEntityDataRepository =
        mockk<NamedEntityDataRepository>(relaxed = true).also { repo ->
            every { repo.save(any()) } answers { firstArg() }
            every { repo.update(any()) } answers { firstArg() }
        }

    /**
     * One proposition about Alice, with Alice and an unreferenced entity both new and both updated.
     *
     * @return The result to persist.
     */
    private fun persistable(): PersistablePropositions =
        persistable(
            props = listOf(proposition("Alice likes tea", "prop-1")),
            new = listOf(alice, unused),
            updated = listOf(alice, unused),
        )

    /**
     * A persistable result with no revision, built from the given lists.
     *
     * @param props The extracted propositions.
     * @param new The entities reported as new.
     * @param updated The entities reported as updated.
     * @return The result.
     */
    private fun persistable(
        props: List<Proposition>,
        new: List<NamedEntityData>,
        updated: List<NamedEntityData>,
    ): PersistablePropositions =
        object : PersistablePropositions {
            override val propositions: List<Proposition> = props
            override val revisionResults: List<RevisionResult> = emptyList()
            override fun newEntities(): List<NamedEntityData> = new
            override fun updatedEntities(): List<NamedEntityData> = updated
            override fun referenceOnlyEntities(): List<NamedEntityData> = emptyList()
        }

    /**
     * A proposition with one subject mention resolved to [entityId].
     *
     * @param text The proposition text.
     * @param id The proposition id.
     * @param entityId The id the mention resolves to.
     * @return The proposition.
     */
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

    /**
     * A person entity whose name is its id.
     *
     * @param id The entity id.
     * @return The entity.
     */
    private fun entity(id: String) = SimpleNamedEntityData(
        id = id,
        name = id,
        description = "test",
        labels = setOf("Person"),
        properties = emptyMap(),
    )
}
