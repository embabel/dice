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
package com.embabel.dice.projection.graph

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.dice.spi.AuthorityTier
import com.embabel.dice.spi.FixedAuthorityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The projected graph edge should carry the authority of the source behind it, so downstream queries
 * can tell a strongly-grounded relationship apart from a weak structural one — and the persister
 * should write that authority onto the edge.
 */
class EdgeAuthorityProjectionTest {

    private val contextId = ContextId("test")
    private val emptySchema = DataDictionary.fromDomainTypes("empty", emptyList())
    private val relations = Relations.empty().withProcedural("likes")

    private fun likesProposition(): Proposition = Proposition(
        contextId = contextId,
        text = "Alice likes jazz",
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "alice-1", role = MentionRole.SUBJECT),
            EntityMention(span = "jazz", type = "MusicGenre", resolvedId = "genre-jazz", role = MentionRole.OBJECT),
        ),
        confidence = 0.9,
    )

    @Test
    fun `projector stamps the resolved authority onto the edge`() {
        val projector = RelationBasedGraphProjector(relations)
            .withAuthorityResolver(FixedAuthorityResolver(AuthorityTier.PRIMARY))

        val result = projector.project(likesProposition(), emptySchema)

        assertTrue(result is ProjectionSuccess)
        assertEquals(AuthorityTier.PRIMARY, (result as ProjectionSuccess).projected.authority)
    }

    @Test
    fun `default resolver derives authority from the proposition's provenance`() {
        val projector = RelationBasedGraphProjector(relations)

        // A connector-backed source is first-party → PRIMARY; content-addressed material is derived.
        val fromConnector = likesProposition()
            .withProvenanceEntries(listOf(ProvenanceEntry(ConnectorRef("gmail", "msg-1"))))
        val fromDerived = likesProposition()
            .withProvenanceEntries(listOf(ProvenanceEntry(ContentAddressedLocator("deadbeef"))))

        assertEquals(
            AuthorityTier.PRIMARY,
            (projector.project(fromConnector, emptySchema) as ProjectionSuccess).projected.authority,
        )
        assertEquals(
            AuthorityTier.DERIVED,
            (projector.project(fromDerived, emptySchema) as ProjectionSuccess).projected.authority,
        )
    }

    @Test
    fun `persister writes authority as an edge property, and omits it when absent`() {
        val repo = mockk<NamedEntityDataRepository>()
        val source = mockk<NamedEntityData>().also { every { it.labels() } returns setOf("Person") }
        val target = mockk<NamedEntityData>().also { every { it.labels() } returns setOf("MusicGenre") }
        every { repo.findById("alice-1") } returns source
        every { repo.findById("genre-jazz") } returns target
        every { repo.save(any()) } answers { firstArg() }
        val captured = slot<RelationshipData>()
        every { repo.mergeRelationship(any(), any(), capture(captured)) } just Runs

        val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

        persister.persistRelationship(
            ProjectedRelationship(
                sourceId = "alice-1", targetId = "genre-jazz", type = "LIKES",
                confidence = 0.9, sourcePropositionIds = listOf("prop-1"),
                authority = AuthorityTier.SECONDARY,
            ),
        )
        assertEquals("SECONDARY", captured.captured.properties["authority"])

        persister.persistRelationship(
            ProjectedRelationship(
                sourceId = "alice-1", targetId = "genre-jazz", type = "LIKES",
                confidence = 0.9, sourcePropositionIds = listOf("prop-1"),
            ),
        )
        assertNull(captured.captured.properties["authority"])
    }
}
