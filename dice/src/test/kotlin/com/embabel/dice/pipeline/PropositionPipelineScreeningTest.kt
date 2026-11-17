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
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.UngroundedMentionsFound
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.filter.MentionFixtures
import com.embabel.dice.common.filter.ObservableMentionFilter
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.ClassifiedProposition
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Checks that the pipeline applies the grounding check and the caller's mention filter itself,
 * for any extractor, and lists every rejected mention on the result.
 */
class PropositionPipelineScreeningTest {

    /** Records every entity it is asked to resolve, then creates them all. */
    private class RecordingResolver : EntityResolver {
        val entities: MutableList<SuggestedEntity> = CopyOnWriteArrayList()

        val names: List<String> get() = entities.map { it.name }

        override fun resolve(
            suggestedEntities: SuggestedEntities,
            schema: DataDictionary,
        ): Resolutions<SuggestedEntityResolution> {
            entities.addAll(suggestedEntities.suggestedEntities)
            return AlwaysCreateEntityResolver.resolve(suggestedEntities, schema)
        }
    }

    private class AllNewReviser : PropositionReviser {
        override fun revise(newProposition: Proposition, repository: PropositionRepository): RevisionResult =
            RevisionResult.New(newProposition)

        override fun classify(newProposition: Proposition, candidates: List<Proposition>) =
            emptyList<ClassifiedProposition>()
    }

    /** Rejects every mention named Carol. */
    private val rejectCarol = object : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = mention.span != "Carol"
        override fun rejectionReason(mention: SuggestedMention): String? = "no Carols"
    }

    /**
     * Builds a grounding check that rejects the mentions the predicate picks, with reason "custom".
     *
     * @param rejects picks the mentions to reject
     * @return the check
     */
    private fun customCheck(rejects: (SuggestedMention) -> Boolean) = object : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = !rejects(mention)
        override fun rejectionReason(mention: SuggestedMention): String? = "custom"
    }

    private val alice = SuggestedMention(span = "Alice", type = "Person", role = "SUBJECT")
    private val bob = SuggestedMention(span = "Bob", type = "Person", role = "OBJECT")
    private val carol = SuggestedMention(span = "Carol", type = "Person", role = "OBJECT")
    private val dan = SuggestedMention(span = "Dan", type = "Person", role = "SUBJECT")
    private val aliceMetBob = "Alice met Bob"
    private val aliceMetCarol = "Alice met Carol"

    private fun proposition(text: String, vararg mentions: SuggestedMention) =
        SuggestedProposition(text = text, mentions = mentions.toList(), confidence = 0.9)

    /** One proposition with a grounded mention (Alice) and an ungrounded one (Carol). */
    private fun mixed() = proposition(aliceMetBob, alice, carol)

    /** One proposition whose two mentions are both grounded. */
    private fun bothGrounded() = proposition(aliceMetCarol, alice, carol)

    private val resolver = RecordingResolver()
    private val events = RecordingDiceEventListener()

    private fun context() = SourceAnalysisContext(
        schema = DataDictionary.fromClasses("test"),
        entityResolver = resolver,
        contextId = ContextId("screening-test"),
    )

    private fun chunk(id: String = "chunk-1") = Chunk(id = id, text = "any", metadata = emptyMap(), parentId = "")

    private fun pipeline(extractor: PropositionExtractor) =
        PropositionPipeline.withExtractor(extractor).withEventListener(events)

    private fun dropPipeline(extractor: PropositionExtractor) =
        pipeline(extractor).withUngroundedMentions(UngroundedMentionHandling.DROP)

    private fun ChunkPropositionResult.success() = this as ChunkPropositionResult.Success

    @Test
    fun `E1 a filter is applied for an extractor that ignores its filter argument`() {
        val result = pipeline(SuggestingPropositionExtractor.of(bothGrounded()))
            .withMentionFilter(rejectCarol)
            .processChunk(chunk(), context().withMintNewEntities(true))

        assertFalse("Carol" in resolver.names, "resolver saw Carol: ${resolver.names}")
        val mentions = result.propositions.single().mentions
        assertEquals(listOf("Alice", "Carol"), mentions.map { it.span })
        assertNotNull(mentions[0].resolvedId)
        assertNull(mentions[1].resolvedId)
    }

    @Test
    fun `E2 the extractor is handed no filter and never the caller's filter`() {
        val extractor = SuggestingPropositionExtractor.of(bothGrounded())
        pipeline(extractor).withMentionFilter(rejectCarol).processChunk(chunk(), context())
        pipeline(extractor).withMentionFilter(rejectCarol, RejectedMentionHandling.DROP).processChunk(chunk(), context())
        pipeline(extractor).withMentionFilter(rejectCarol, RejectedMentionHandling.KEEP)
            .process(listOf(chunk("a"), chunk("b")), context())

        assertEquals(List<MentionFilter?>(4) { null }, extractor.filtersSeen)
    }

    @Test
    fun `E3 DROP takes the rejected mention off and KEEP resolves and reports it`() {
        val dropped = pipeline(SuggestingPropositionExtractor.of(bothGrounded()))
            .withMentionFilter(rejectCarol, RejectedMentionHandling.DROP)
            .processChunk(chunk(), context())

        assertEquals(listOf("Alice"), dropped.propositions.single().mentions.map { it.span })
        assertFalse("Carol" in resolver.names, "resolver saw Carol: ${resolver.names}")

        val kept = pipeline(SuggestingPropositionExtractor.of(bothGrounded()))
            .withMentionFilter(rejectCarol, RejectedMentionHandling.KEEP)
            .processChunk(chunk(), context())

        assertTrue("Carol" in resolver.names, "resolver should see Carol: ${resolver.names}")
        assertEquals(listOf("Alice", "Carol"), kept.propositions.single().mentions.map { it.span })
        val rejection = kept.rejectedMentions.single()
        assertEquals("Carol", rejection.mention.span)
        assertEquals(RejectedMention.Check.FILTER, rejection.check)
        assertEquals(RejectedMentionHandling.KEEP, rejection.handling)
    }

    @Test
    fun `E4 the result lists grounding and filter rejections with their proposition and chunk`() {
        val danMetCarol = "Dan met Carol"
        val extractor = SuggestingPropositionExtractor.of(mixed(), proposition(danMetCarol, dan, carol))
        val configured = dropPipeline(extractor).withMentionFilter(rejectCarol)

        val result = configured.processChunk(chunk("chunk-a"), context())

        assertEquals(
            listOf(
                RejectedMention(
                    "chunk-a", 0, aliceMetBob, carol, "SPAN_NOT_IN_PROPOSITION",
                    RejectedMention.Check.GROUNDING, RejectedMentionHandling.DROP,
                ),
                RejectedMention(
                    "chunk-a", 1, danMetCarol, carol, "no Carols",
                    RejectedMention.Check.FILTER, RejectedMentionHandling.LEAVE_UNRESOLVED,
                ),
            ),
            result.rejectedMentions,
        )

        val run = configured.process(listOf(chunk("chunk-a"), chunk("chunk-b")), context())

        assertEquals(listOf("chunk-a", "chunk-a", "chunk-b", "chunk-b"), run.rejectedMentions.map { it.chunkId })
        assertEquals(
            listOf(RejectedMention.Check.GROUNDING, RejectedMention.Check.FILTER, RejectedMention.Check.GROUNDING, RejectedMention.Check.FILTER),
            run.rejectedMentions.map { it.check },
        )
    }

    @Test
    fun `E5 a mention that names a known entity by another name is kept and resolved to it`() {
        val context = MentionFixtures.context(MentionFixtures.knownPerson("user-1", "Thomas", "Tom"))
        val extractor = SuggestingPropositionExtractor.of(
            MentionFixtures.proposition("Tom moved to Paris", MentionFixtures.mention("Thomas"))
        )

        val result = dropPipeline(extractor).processChunk(chunk(), context)

        val mention = result.propositions.single().mentions.single()
        assertEquals("Thomas", mention.span)
        assertEquals("user-1", mention.resolvedId)
        assertTrue(result.rejectedMentions.isEmpty(), "rejected: ${result.rejectedMentions}")
        assertTrue(events.eventsOfType<UngroundedMentionsFound>().isEmpty(), "events: ${events.events}")
    }

    @Test
    fun `E7 withMentionGrounding replaces the grounding check`() {
        val rejectAll = customCheck { true }
        val result = dropPipeline(SuggestingPropositionExtractor.of(mixed()))
            .withMentionGrounding(rejectAll)
            .processChunk(chunk(), context())

        assertTrue(result.propositions.single().mentions.isEmpty(), "mentions: ${result.propositions.single().mentions}")
        val event = events.eventsOfType<UngroundedMentionsFound>().single()
        assertEquals(
            listOf(UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION, UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION),
            event.mentions.map { it.reason },
        )
        assertEquals(listOf("custom", "custom"), result.rejectedMentions.map { it.reason })

        events.clear()
        pipeline(SuggestingPropositionExtractor.of(mixed()))
            .withMentionGrounding(customCheck { false })
            .processChunk(chunk(), context())

        assertTrue(events.eventsOfType<UngroundedMentionsFound>().isEmpty(), "events: ${events.events}")
    }

    @Test
    fun `E8 several chunks at once each report their own rejection and events arrive in chunk order`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val chunks = (0..3).map { chunk("chunk-$it") }
            val run = dropPipeline(SuggestingPropositionExtractor.of(mixed()))
                .withExecutionStrategy(ParallelExtractionStrategy(executor))
                .process(chunks, context())

            assertEquals(chunks.size, run.chunkResults.size)
            run.chunkResults.zip(chunks).forEach { (result, chunk) ->
                assertTrue(result is ChunkPropositionResult.Success, "chunk ${chunk.id}: $result")
                assertEquals(listOf(chunk.id), result.rejectedMentions.map { it.chunkId })
            }
            assertEquals(chunks.map { it.id }, events.eventsOfType<UngroundedMentionsFound>().map { it.chunkId })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `E9 copy-builders keep the grounding check, the filter and its handling`() {
        // The check rejects Alice and accepts the ungrounded Carol, so only a replaced check gives this outcome.
        val check = customCheck { it.span == "Alice" }
        val builders: List<(PropositionPipeline) -> PropositionPipeline> = listOf(
            { it },
            { it.withEventListener(events) },
            { it.withExecutionStrategy(SerialExtractionStrategy) },
            { it.withRevision(AllNewReviser(), inMemoryRepo()) },
            { it.withUngroundedMentions(UngroundedMentionHandling.DROP) },
        )
        builders.forEachIndexed { i, configure ->
            val probe = RecordingResolver()
            val base = dropPipeline(SuggestingPropositionExtractor.of(proposition(aliceMetBob, alice, carol, bob)))
                .withMentionGrounding(check)
                .withMentionFilter(rejectCarol, RejectedMentionHandling.DROP)
            val result = configure(base).processChunk(chunk(), context().copy(entityResolver = probe))

            assertEquals(listOf("Bob"), probe.names, "builder $i")
            assertEquals(listOf("Bob"), result.propositions.single().mentions.map { it.span }, "builder $i")
            assertEquals(
                listOf("Alice" to "custom", "Carol" to "no Carols"),
                result.rejectedMentions.map { it.mention.span to it.reason },
                "builder $i",
            )
            assertEquals(
                listOf(RejectedMentionHandling.DROP, RejectedMentionHandling.DROP),
                result.rejectedMentions.map { it.handling },
                "builder $i",
            )
        }
    }

    @Test
    fun `E10 a filter that returns no results for a proposition with mentions fails the chunk`() {
        val broken = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true
            override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
                emptyList()
        }
        val configured = pipeline(SuggestingPropositionExtractor.of(bothGrounded())).withMentionFilter(broken)

        assertThrows(IllegalStateException::class.java) { configured.processChunk(chunk(), context()) }

        val run = configured.process(listOf(chunk("a"), chunk("b")), context())
        assertEquals(2, run.chunkResults.size)
        assertTrue(run.chunkResults.all { it is ChunkPropositionResult.Failed }, "results: ${run.chunkResults}")
    }

    @Test
    fun `E11 the default pipeline returns the extracted propositions and reports the ungrounded mention as KEEP`() {
        val extractor = SuggestingPropositionExtractor.of(mixed())

        val result = pipeline(extractor).processChunk(chunk(), context()).success()

        assertSame(extractor.extracted.single(), result.suggestedPropositions)
        val rejection = result.rejectedMentions.single()
        assertEquals(RejectedMentionHandling.KEEP, rejection.handling)
        assertEquals("Carol", rejection.mention.span)
    }

    @Test
    fun `E12 an observable filter counts each mention once`() {
        val registry = SimpleMeterRegistry()

        pipeline(SuggestingPropositionExtractor.of(bothGrounded()))
            .withMentionFilter(ObservableMentionFilter(rejectCarol, registry))
            .processChunk(chunk(), context())

        val total = registry.counter("dice.mentions.validated", "entity.type", "Person", "valid", "true").count() +
            registry.counter("dice.mentions.validated", "entity.type", "Person", "valid", "false").count()
        assertEquals(2.0, total)
    }

    @Test
    fun `E13 rejected mentions carry the id of the chunk the pipeline processed`() {
        val inner = SuggestingPropositionExtractor.of(mixed())
        val renaming = object : PropositionExtractor by inner {
            override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions =
                inner.extract(chunk, context).copy(chunkId = "other")
        }

        val result = pipeline(renaming).withMentionFilter(rejectCarol).processChunk(chunk("c1"), context())

        assertEquals(2, result.rejectedMentions.size, "rejected: ${result.rejectedMentions}")
        assertTrue(result.rejectedMentions.all { it.chunkId == "c1" }, "rejected: ${result.rejectedMentions}")
    }

    @Test
    fun `E14 a mention left unresolved gets no id from an accepted mention with the same span`() {
        val secondAliceRejected = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true
            override fun rejectionReason(mention: SuggestedMention): String? = null
            override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext) =
                proposition.mentions.mapIndexed { index, _ ->
                    if (index == 1) ValidationResult.Invalid("second") else ValidationResult.Valid
                }
        }
        val twoAlices = proposition(aliceMetBob, alice, alice)

        val result = pipeline(SuggestingPropositionExtractor.of(twoAlices))
            .withMentionFilter(secondAliceRejected)
            .processChunk(chunk(), context().withMintNewEntities(true))

        val mentions = result.propositions.single().mentions
        assertEquals(listOf("Alice", "Alice"), mentions.map { it.span })
        assertNotNull(mentions[0].resolvedId)
        assertNull(mentions[1].resolvedId)
    }

    @Test
    fun `E15 a mention left unresolved in one proposition gets no id from the same mention accepted in another`() {
        val carolOnlyWithBob = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean =
                !(mention.span == "Carol" && propositionText == aliceMetCarol)
            override fun rejectionReason(mention: SuggestedMention): String? = "not here"
        }

        val result = pipeline(SuggestingPropositionExtractor.of(bothGrounded(), proposition("Carol met Bob", carol, bob)))
            .withMentionFilter(carolOnlyWithBob)
            .processChunk(chunk(), context().withMintNewEntities(true))

        val (first, second) = result.propositions
        assertNull(first.mentions.single { it.span == "Carol" }.resolvedId)
        assertNotNull(second.mentions.single { it.span == "Carol" }.resolvedId)
    }

    @Test
    fun `E16 an extractor that reorders its output never leaves a rejected mention with an id`() {
        val inner = SuggestingPropositionExtractor.of(bothGrounded(), proposition("Carol met Bob", carol, bob))
        val reversing = reshaping(inner) { resolved -> resolved.reversed().map { it.copy(mentions = it.mentions.reversed()) } }
        val carolOnlyWithBob = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean =
                !(mention.span == "Carol" && propositionText == aliceMetCarol)
            override fun rejectionReason(mention: SuggestedMention): String? = "not here"
        }

        val logged = warnings {
            val result = pipeline(reversing)
                .withMentionFilter(carolOnlyWithBob)
                .processChunk(chunk(), context().withMintNewEntities(true))

            val withAlice = result.propositions.single { it.text == aliceMetCarol }
            assertNull(withAlice.mentions.single { it.span == "Carol" }.resolvedId)
            assertNotNull(withAlice.mentions.single { it.span == "Alice" }.resolvedId)
            assertNotNull(result.propositions.single { it.text == "Carol met Bob" }.mentions.single { it.span == "Bob" }.resolvedId)
        }
        assertEquals(1, logged.count { it.formattedMessage.contains("out of line") }, "logged: ${logged.map { it.formattedMessage }}")
    }

    @Test
    fun `E18 propositions with the same text keep their own verdicts`() {
        val objectAlice = alice.copy(role = "OBJECT")
        val rejectsObjectAlice = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean =
                !(mention.span == "Alice" && mention.role == "OBJECT")
            override fun rejectionReason(mention: SuggestedMention): String? = "object"
        }

        val result = pipeline(SuggestingPropositionExtractor.of(proposition(aliceMetBob, alice), proposition(aliceMetBob, objectAlice)))
            .withMentionFilter(rejectsObjectAlice)
            .processChunk(chunk(), context().withMintNewEntities(true))

        val (first, second) = result.propositions
        assertNotNull(first.mentions.single().resolvedId)
        assertNull(second.mentions.single().resolvedId)
    }

    @Test
    fun `E19 an extractor that reverses propositions with the same text never leaves a rejected mention with an id`() {
        val objectAlice = alice.copy(role = "OBJECT")
        val inner = SuggestingPropositionExtractor.of(proposition(aliceMetBob, alice, bob), proposition(aliceMetBob, bob, objectAlice))
        val reversing = reshaping(inner) { resolved -> resolved.reversed() }
        val rejectsObjectAlice = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean =
                !(mention.span == "Alice" && mention.role == "OBJECT")
            override fun rejectionReason(mention: SuggestedMention): String? = "object"
        }

        val result = pipeline(reversing)
            .withMentionFilter(rejectsObjectAlice)
            .processChunk(chunk(), context().withMintNewEntities(true))

        val objectAlices = result.propositions.flatMap { it.mentions }.filter { it.span == "Alice" && it.role.name == "OBJECT" }
        assertEquals(1, objectAlices.size)
        assertNull(objectAlices.single().resolvedId)
    }

    @Test
    fun `E20 a mention grounded only by its id is ungrounded, with or without a wrapped check`() {
        val known = SourceAnalysisContext(
            schema = DataDictionary.fromClasses("test"),
            entityResolver = resolver,
            contextId = ContextId("screening-test"),
            knownEntities = listOf(
                MentionFixtures.knownPerson("p-smith", "William Smith", "Bill"),
                MentionFixtures.knownPerson("p-jones", "William Jones", "Will"),
            ),
        )
        val smith = SuggestedMention(span = "William", type = "Person", role = "SUBJECT", suggestedId = "p-smith")
        val jones = SuggestedMention(span = "William", type = "Person", role = "SUBJECT", suggestedId = "p-jones")

        val extractor = SuggestingPropositionExtractor.of(proposition("Bill waved", smith), proposition("Will sang", jones))
        val wrapped = com.embabel.dice.common.filter.GroundedMentionFilter()
            .let { ObservableMentionFilter(it, SimpleMeterRegistry()) }

        for (pipeline in listOf(dropPipeline(extractor), dropPipeline(extractor).withMentionGrounding(wrapped))) {
            val result = pipeline.processChunk(chunk(), known)

            assertEquals(2, result.rejectedMentions.count { it.check == RejectedMention.Check.GROUNDING })
            assertTrue(result.propositions.all { it.mentions.isEmpty() }, "mentions: ${result.propositions.map { it.mentions }}")
        }
    }

    @Test
    fun `E21 a mention with a role resolution does not know is left unresolved all the same`() {
        val unknownRole = alice.copy(role = "UNKNOWN")
        val secondRejected = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true
            override fun rejectionReason(mention: SuggestedMention): String? = null
            override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext) =
                proposition.mentions.mapIndexed { index, _ ->
                    if (index == 1) ValidationResult.Invalid("second") else ValidationResult.Valid
                }
        }

        val result = pipeline(SuggestingPropositionExtractor.of(proposition(aliceMetBob, unknownRole, unknownRole)))
            .withMentionFilter(secondRejected)
            .processChunk(chunk(), context().withMintNewEntities(true))

        val mentions = result.propositions.single().mentions
        assertNotNull(mentions[0].resolvedId)
        assertNull(mentions[1].resolvedId)
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

    /** Rejects the second mention of every proposition. */
    private val secondRejected = object : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true
        override fun rejectionReason(mention: SuggestedMention): String? = null
        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext) =
            proposition.mentions.mapIndexed { index, _ ->
                if (index == 1) ValidationResult.Invalid("second") else ValidationResult.Valid
            }
    }

    /**
     * Wraps an extractor so that its resolved propositions pass through a change.
     *
     * @param inner the extractor to wrap
     * @param change what to do to the resolved propositions
     * @return the wrapped extractor
     */
    private fun reshaping(inner: PropositionExtractor, change: (List<Proposition>) -> List<Proposition>) =
        object : PropositionExtractor by inner {
            override fun resolvePropositions(
                suggestedPropositions: SuggestedPropositions,
                resolutions: com.embabel.dice.common.Resolutions<SuggestedEntityResolution>,
                context: SourceAnalysisContext,
            ): List<Proposition> = change(inner.resolvePropositions(suggestedPropositions, resolutions, context))
        }

    /**
     * Runs a block and collects what the pipeline logged at warning level or above.
     *
     * @param block the code to run
     * @return the logged events
     */
    private fun warnings(block: () -> Unit): List<ch.qos.logback.classic.spi.ILoggingEvent> {
        val logger = org.slf4j.LoggerFactory.getLogger(PropositionPipeline::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN) }
    }

    @Test
    fun `L1 a mention left unresolved stays unresolved when the extractor rewrites the text`() {
        val extractor = reshaping(SuggestingPropositionExtractor.of(proposition(aliceMetBob, alice, alice))) { resolved ->
            resolved.map { it.copy(text = it.text.uppercase()) }
        }

        val result = pipeline(extractor).withMentionFilter(secondRejected).processChunk(chunk(), context().withMintNewEntities(true))

        val mentions = result.propositions.single().mentions
        assertNotNull(mentions[0].resolvedId)
        assertNull(mentions[1].resolvedId)
    }

    @Test
    fun `L1 a mention left unresolved stays unresolved and a warning is logged when the extractor merges propositions`() {
        val extractor = reshaping(
            SuggestingPropositionExtractor.of(proposition(aliceMetCarol, alice, carol), proposition("Carol met Bob", carol, bob)),
        ) { resolved -> listOf(resolved[0].copy(mentions = resolved[0].mentions + resolved[1].mentions)) }

        val logged = warnings {
            val result = pipeline(extractor).withMentionFilter(secondRejected).processChunk(chunk("c-merge"), context().withMintNewEntities(true))
            val carols = result.propositions.single().mentions.filter { it.span == "Carol" }
            assertTrue(carols.all { it.resolvedId == null }, "carols: $carols")
        }
        assertTrue(
            logged.any { it.formattedMessage.contains("c-merge") && it.formattedMessage.contains("out of line") },
            "logged: ${logged.map { it.formattedMessage }}",
        )
        assertTrue(logged.none { it.formattedMessage.contains("Carol") }, "logged: ${logged.map { it.formattedMessage }}")
    }
}
