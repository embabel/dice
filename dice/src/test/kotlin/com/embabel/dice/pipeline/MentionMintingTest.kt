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
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.common.*
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.text2graph.builder.Animal
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SourceAnalysisContext.mintNewEntities] governs whether a mention the
 * resolver could NOT match to an existing entity becomes a NEW persisted
 * entity. Default is OFF: the proposition still persists, but its unmatched
 * mention carries no resolvedId and no entity node is created — asking about
 * a thing must not mint it.
 */
class MentionMintingTest {

    private val schema = DataDictionary.fromClasses("test", Person::class.java, Animal::class.java)
    private val contextId = ContextId("minting-test")

    /** Extractor emitting one proposition mentioning "Rex" (unknown to the graph). */
    private class SingleMentionExtractor : PropositionExtractor {

        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions =
            SuggestedPropositions(
                chunkId = chunk.id,
                propositions = listOf(
                    SuggestedProposition(
                        text = "Something about ${chunk.text}",
                        mentions = listOf(
                            SuggestedMention(span = chunk.text, type = "Person", role = "SUBJECT"),
                        ),
                        confidence = 0.9,
                    ),
                ),
            )

        override fun toSuggestedEntities(
            suggestedPropositions: SuggestedPropositions,
            context: SourceAnalysisContext,
            sourceText: String?,
            mentionFilter: MentionFilter?,
        ): SuggestedEntities = SuggestedEntities(
            suggestedEntities = suggestedPropositions.propositions
                .flatMap { it.mentions }
                .distinctBy { MentionKey.from(it) }
                .map {
                    SuggestedEntity(
                        labels = listOf(it.type),
                        name = it.span,
                        summary = "Entity mentioned in proposition",
                        chunkId = suggestedPropositions.chunkId,
                    )
                },
            sourceText = sourceText,
        )

        override fun resolvePropositions(
            suggestedPropositions: SuggestedPropositions,
            resolutions: Resolutions<SuggestedEntityResolution>,
            context: SourceAnalysisContext,
        ): List<Proposition> {
            val nameToId = resolutions.resolutions
                .mapNotNull { r -> r.recommended?.let { r.suggested.name.lowercase() to it.id } }
                .toMap()
            return suggestedPropositions.propositions.map { sp ->
                Proposition(
                    contextId = context.contextId,
                    text = sp.text,
                    mentions = sp.mentions.map {
                        EntityMention(
                            span = it.span,
                            type = it.type,
                            role = MentionRole.valueOf(it.role),
                            resolvedId = nameToId[it.span.lowercase()],
                        )
                    },
                    confidence = sp.confidence,
                    grounding = listOf(suggestedPropositions.chunkId),
                )
            }
        }
    }

    private val pipeline = PropositionPipeline.withExtractor(SingleMentionExtractor())

    private fun context(mint: Boolean? = null): SourceAnalysisContext {
        val base = SourceAnalysisContext(
            schema = schema,
            entityResolver = AlwaysCreateEntityResolver,
            contextId = contextId,
        )
        return if (mint != null) base.withMintNewEntities(mint) else base
    }

    @Test
    fun `default does not mint - proposition persists with unresolved mention and no entity node`() {
        val result = pipeline.processOnce("Rex", "source-1", context())!!

        assertTrue(result.newEntities().isEmpty()) {
            "No new entities may be minted by default, got ${result.newEntities()}"
        }
        assertEquals(1, result.propositions.size, "The proposition itself must survive")
        assertNull(
            result.propositions.single().mentions.single().resolvedId,
            "An unminted mention must stay unresolved",
        )

        val entityRepository = InMemoryNamedEntityDataRepository(schema)
        val propositionRepository = InMemoryPropositionRepository()
        result.persist(propositionRepository, entityRepository)
        assertEquals(1, propositionRepository.findByContextIdValue(contextId.value).size)
        assertTrue(entityRepository.findByLabel("Person").isEmpty()) {
            "Persist must write no entity nodes when minting is off"
        }
    }

    @Test
    fun `minting enabled - mention becomes a persisted entity with a resolved id`() {
        val result = pipeline.processOnce("Rex", "source-2", context(mint = true))!!

        assertEquals(1, result.newEntities().size)
        assertEquals("Rex", result.newEntities().single().name)
        val resolvedId = result.propositions.single().mentions.single().resolvedId
        assertNotNull(resolvedId, "A minted mention must carry its entity id")
        assertEquals(result.newEntities().single().id, resolvedId)

        val entityRepository = InMemoryNamedEntityDataRepository(schema)
        val propositionRepository = InMemoryPropositionRepository()
        result.persist(propositionRepository, entityRepository)
        assertEquals(1, entityRepository.findByLabel("Person").size, "The minted entity must be persisted")
    }

    @Test
    fun `minted entities carry the caller's stamped base properties`() {
        val ctx = context(mint = true).withMintedEntityProperties(mapOf("userId" to "owner-1"))
        val result = pipeline.processOnce("Rex", "source-4", ctx)!!

        val minted = result.newEntities().single()
        assertEquals("owner-1", minted.properties["userId"]) {
            "Ownership properties must be stamped onto minted entities"
        }
    }

    @Test
    fun `minting off still resolves mentions to EXISTING entities`() {
        val alice = SimpleNamedEntityData(
            id = "person-alice",
            name = "Alice",
            description = "known person",
            labels = setOf("Person"),
            properties = emptyMap(),
        )
        val ctx = context(mint = false).withKnownEntities(
            KnownEntity.of(alice).withRole("A referenced entity"),
        )

        val result = pipeline.processOnce("Alice", "source-3", ctx)!!

        assertTrue(result.newEntities().isEmpty(), "Nothing new to mint")
        assertEquals(
            "person-alice",
            result.propositions.single().mentions.single().resolvedId,
            "A mention matching an EXISTING entity must still resolve when minting is off",
        )
        assertFalse(result.propositions.single().mentions.single().resolvedId.isNullOrBlank())
    }
}
