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
import com.embabel.dice.common.*
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that the pipeline tests every mention against its own proposition before entity
 * resolution, reports the ones that don't belong, and removes them only when asked to.
 */
class PropositionPipelineGroundingTest {

    /** Hands back the same propositions for every chunk, and remembers the mention filter it was given. */
    private class FakeExtractor(private val suggested: SuggestedPropositions) : PropositionExtractor {
        val filtersSeen = mutableListOf<MentionFilter?>()

        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions =
            suggested.copy(chunkId = chunk.id)

        override fun toSuggestedEntities(
            suggestedPropositions: SuggestedPropositions,
            context: SourceAnalysisContext,
            sourceText: String?,
            mentionFilter: MentionFilter?,
        ): SuggestedEntities {
            filtersSeen.add(mentionFilter)
            val entities = suggestedPropositions.propositions.flatMap { it.mentions }.map {
                SuggestedEntity(labels = listOf(it.type), name = it.span, summary = it.span, chunkId = suggestedPropositions.chunkId)
            }
            return SuggestedEntities(suggestedEntities = entities, sourceText = sourceText)
        }

        override fun resolvePropositions(
            suggestedPropositions: SuggestedPropositions,
            resolutions: Resolutions<SuggestedEntityResolution>,
            context: SourceAnalysisContext,
        ): List<Proposition> {
            val nameToId = resolutions.resolutions.mapNotNull { r -> r.recommended?.let { r.suggested.name.lowercase() to it.id } }.toMap()
            return suggestedPropositions.propositions.map { sp ->
                Proposition(
                    contextId = context.contextId,
                    text = sp.text,
                    mentions = sp.mentions.map { m ->
                        EntityMention(span = m.span, type = m.type, role = MentionRole.valueOf(m.role), resolvedId = nameToId[m.span.lowercase()])
                    },
                    confidence = sp.confidence,
                    grounding = listOf(suggestedPropositions.chunkId),
                )
            }
        }
    }

    /** Records the names of every entity it is asked to resolve, then creates them all. */
    private class RecordingResolver : EntityResolver {
        val names = mutableListOf<String>()

        override fun resolve(
            suggestedEntities: SuggestedEntities,
            schema: DataDictionary,
        ): Resolutions<SuggestedEntityResolution> {
            names.addAll(suggestedEntities.suggestedEntities.map { it.name })
            return AlwaysCreateEntityResolver.resolve(suggestedEntities, schema)
        }
    }

    private class AllNewReviser : PropositionReviser {
        override fun revise(newProposition: Proposition, repository: PropositionRepository): RevisionResult =
            RevisionResult.New(newProposition)

        override fun classify(newProposition: Proposition, candidates: List<Proposition>) =
            emptyList<com.embabel.dice.proposition.revision.ClassifiedProposition>()
    }

    private val alice = SuggestedMention(span = "Alice", type = "Person", role = "SUBJECT")
    private val carol = SuggestedMention(span = "Carol", type = "Person", role = "OBJECT")
    private val aliceMetBob = "Alice met Bob"

    private fun suggested(vararg propositions: SuggestedProposition) =
        SuggestedPropositions(chunkId = "ignored", propositions = propositions.toList())

    private fun proposition(text: String, vararg mentions: SuggestedMention) =
        SuggestedProposition(text = text, mentions = mentions.toList(), confidence = 0.9)

    /** One proposition with a grounded mention (Alice) and an ungrounded one (Carol). */
    private fun mixed() = suggested(proposition(aliceMetBob, alice, carol))

    private val resolver = RecordingResolver()
    private val events = RecordingDiceEventListener()

    private fun context() = SourceAnalysisContext(
        schema = DataDictionary.fromClasses("test"),
        entityResolver = resolver,
        contextId = ContextId("grounding-test"),
    )

    private fun chunk(id: String = "chunk-1") = Chunk(id = id, text = "any", metadata = emptyMap(), parentId = "")

    private fun defaultPipeline(extractor: PropositionExtractor) =
        PropositionPipeline.withExtractor(extractor).withEventListener(events)

    private fun dropPipeline(extractor: PropositionExtractor) =
        defaultPipeline(extractor).withUngroundedMentions(UngroundedMentionHandling.DROP)

    @Test
    fun `E1 default pipeline keeps the ungrounded mention and reports it once as KEEP`() {
        val result = defaultPipeline(FakeExtractor(mixed())).processChunk(chunk(), context())
            as ChunkPropositionResult.Success

        assertTrue("Carol" in resolver.names, "resolver should see the ungrounded span: ${resolver.names}")
        assertEquals(listOf("Alice", "Carol"), result.propositions.single().mentions.map { it.span })
        val found = events.eventsOfType<UngroundedMentionsFound>()
        assertEquals(1, found.size)
        assertEquals(UngroundedMentionHandling.KEEP, found.single().handling)
    }

    @Test
    fun `E2 drop pipeline never hands the resolver the ungrounded span`() {
        dropPipeline(FakeExtractor(mixed())).processChunk(chunk(), context())

        assertFalse("Carol" in resolver.names, "resolver saw the ungrounded span: ${resolver.names}")
        assertTrue("Alice" in resolver.names, "resolver should see the grounded span: ${resolver.names}")
    }

    @Test
    fun `E3 drop pipeline leaves only grounded mentions and keeps propositions that lost all of theirs`() {
        val extractor = FakeExtractor(
            suggested(
                proposition(aliceMetBob, alice, carol),
                proposition("It rained all day", SuggestedMention(span = "Dave", type = "Person", role = "OTHER")),
            )
        )
        val result = dropPipeline(extractor).processChunk(chunk(), context()) as ChunkPropositionResult.Success

        assertEquals(listOf(aliceMetBob, "It rained all day"), result.propositions.map { it.text })
        assertEquals(listOf("Alice"), result.propositions[0].mentions.map { it.span })
        assertTrue(result.propositions[1].mentions.isEmpty(), "mentions: ${result.propositions[1].mentions}")
    }

    @Test
    fun `E4 drop pipeline mints no entity for the ungrounded span`() {
        val result = dropPipeline(FakeExtractor(mixed()))
            .processChunk(chunk(), context().withMintNewEntities(true)) as ChunkPropositionResult.Success

        val minted = result.newEntities().map { it.name }
        assertFalse("Carol" in minted, "minted: $minted")
        assertTrue("Alice" in minted, "minted: $minted")
    }

    @Test
    fun `E5 drop pipeline reports each rejected mention with its proposition and reason`() {
        val blank = SuggestedMention(span = " ​ ", type = "Person", role = "OTHER")
        val extractor = FakeExtractor(suggested(proposition(aliceMetBob, alice, carol, blank)))

        dropPipeline(extractor).processChunk(chunk(), context())

        val event = events.eventsOfType<UngroundedMentionsFound>().single()
        assertEquals(ContextId("grounding-test"), event.contextId)
        assertEquals("chunk-1", event.chunkId)
        assertEquals(UngroundedMentionHandling.DROP, event.handling)
        assertEquals(
            listOf(
                UngroundedMention(aliceMetBob, carol, UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION),
                UngroundedMention(aliceMetBob, blank, UngroundedMention.Reason.BLANK_SPAN),
            ),
            event.mentions,
        )
    }

    @Test
    fun `E6 no event in either mode when every mention is grounded`() {
        val clean = suggested(proposition(aliceMetBob, alice, SuggestedMention(span = "Bob", type = "Person", role = "OBJECT")))

        defaultPipeline(FakeExtractor(clean)).processChunk(chunk(), context())
        dropPipeline(FakeExtractor(clean)).processChunk(chunk(), context())

        assertTrue(events.eventsOfType<UngroundedMentionsFound>().isEmpty(), "events: ${events.events}")
        assertEquals(listOf("Alice", "Bob", "Alice", "Bob"), resolver.names)
    }

    @Test
    fun `E7 drop pipeline drops an ungrounded mention even when it carries a suggested id`() {
        val withId = SuggestedMention(span = "Carol", type = "Person", suggestedId = "person-carol", role = "OBJECT")
        val result = dropPipeline(FakeExtractor(suggested(proposition(aliceMetBob, alice, withId))))
            .processChunk(chunk(), context()) as ChunkPropositionResult.Success

        assertFalse("Carol" in resolver.names, "resolver saw the ungrounded span: ${resolver.names}")
        assertEquals(listOf("Alice"), result.propositions.single().mentions.map { it.span })
    }

    @Test
    fun `E8 process emits one event per chunk with an ungrounded mention, in chunk order`() {
        dropPipeline(FakeExtractor(mixed())).process(listOf(chunk("chunk-a"), chunk("chunk-b")), context())

        assertEquals(
            listOf("chunk-a", "chunk-b"),
            events.eventsOfType<UngroundedMentionsFound>().map { it.chunkId },
        )
    }

    @Test
    fun `E9 copy-builders keep the mention filter and the DROP handling`() {
        val filter = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true
        }
        val filtered = FakeExtractor(mixed())
        defaultPipeline(filtered).withMentionFilter(filter).processChunk(chunk(), context())
        assertSame(filter, filtered.filtersSeen.single())

        val dropped: List<(PropositionPipeline) -> PropositionPipeline> = listOf(
            { it.withMentionFilter(filter) },
            { it.withEventListener(events) },
            { it.withExecutionStrategy(SerialExtractionStrategy) },
            { it.withRevision(AllNewReviser(), inMemoryRepo()) },
        )
        dropped.forEachIndexed { i, configure ->
            val probe = RecordingResolver()
            val result = configure(dropPipeline(FakeExtractor(mixed())))
                .processChunk(chunk(), context().copy(entityResolver = probe)) as ChunkPropositionResult.Success
            assertFalse("Carol" in probe.names, "builder $i lost DROP: ${probe.names}")
            assertEquals(listOf("Alice"), result.propositions.single().mentions.map { it.span }, "builder $i")
        }
    }

    @Test
    fun `E10 the event survives a JSON round trip`() {
        val event = UngroundedMentionsFound(
            contextId = ContextId("grounding-test"),
            chunkId = "chunk-1",
            mentions = listOf(UngroundedMention(aliceMetBob, carol, UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION)),
            handling = UngroundedMentionHandling.DROP,
        )
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val read = mapper.readValue(mapper.writeValueAsString(event as DiceEvent), DiceEvent::class.java)

        assertTrue(read is UngroundedMentionsFound, "read back as ${read::class}")
        read as UngroundedMentionsFound
        assertEquals(event.chunkId, read.chunkId)
        assertEquals(event.handling, read.handling)
        assertEquals(event.mentions, read.mentions)
    }

    private fun inMemoryRepo(): PropositionRepository = object : PropositionRepository {
        private val store = mutableMapOf<String, Proposition>()
        override val luceneSyntaxNotes: String = "test"
        override fun save(proposition: Proposition): Proposition { store[proposition.id] = proposition; return proposition }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: com.embabel.agent.rag.service.RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findSimilarWithScores(textSimilaritySearchRequest: com.embabel.common.core.types.TextSimilaritySearchRequest) = emptyList<com.embabel.common.core.types.SimilarityResult<Proposition>>()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }
}
