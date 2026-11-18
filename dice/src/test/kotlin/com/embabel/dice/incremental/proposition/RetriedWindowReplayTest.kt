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
package com.embabel.dice.incremental.proposition

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.incremental.IncrementalSourceFormatter
import com.embabel.dice.incremental.WindowConfig
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.LlmPropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A window whose write failed part way is analysed again, and the claims the failed write already
 * stored come back as replays. The stored claim keeps its id, confidence and reinforce count, and
 * the claim the failed write never reached is stored once.
 */
class RetriedWindowReplayTest {

    private val contextId = ContextId("retried-window")
    private val schema = DataDictionary.fromClasses("retried-window", Person::class.java)

    /** Turns each `|`-separated part of a chunk into one claim grounded in that chunk. */
    private object OneClaimPerPart : PropositionExtractor {
        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions =
            SuggestedPropositions(
                chunkId = chunk.id,
                propositions = chunk.text.split("|").map {
                    SuggestedProposition(text = it, mentions = emptyList(), confidence = 0.9)
                },
            )

        override fun toSuggestedEntities(
            suggestedPropositions: SuggestedPropositions,
            context: SourceAnalysisContext,
            sourceText: String?,
            mentionFilter: MentionFilter?,
        ): SuggestedEntities = SuggestedEntities(suggestedEntities = emptyList(), sourceText = sourceText)

        override fun resolvePropositions(
            suggestedPropositions: SuggestedPropositions,
            resolutions: Resolutions<SuggestedEntityResolution>,
            context: SourceAnalysisContext,
        ): List<Proposition> = suggestedPropositions.propositions.map {
            Proposition(
                contextId = context.contextId,
                text = it.text,
                mentions = emptyList(),
                confidence = it.confidence,
                grounding = listOf(suggestedPropositions.chunkId),
            )
        }
    }

    /**
     * An in-memory repository whose save number [failOn], counted from one, throws once. Saves
     * before it go through, so the write fails part way.
     */
    private class FailsOnceRepository(
        private val failOn: Int,
        private val delegate: InMemoryPropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {
        private var saves = 0

        override fun save(proposition: Proposition): Proposition {
            saves++
            if (saves == failOn) throw IllegalStateException("store is down")
            return delegate.save(proposition)
        }

        override fun saveAll(propositions: Collection<Proposition>) {
            propositions.forEach { save(it) }
        }
    }

    private class ListSource(override val id: String, val items: List<String>) : IncrementalSource<String> {
        override val size: Int get() = items.size
        override fun getItems(start: Int, end: Int): List<String> = items.subList(start, end)
    }

    @Test
    fun `a window retried after a write that failed part way replays the claim already stored`() {
        val repository = FailsOnceRepository(failOn = 2)
        val pipeline = PropositionPipeline
            .withExtractor(OneClaimPerPart)
            .withRevision(LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()), repository)
        val analyzer = PropositionIncrementalAnalyzer(
            pipeline = pipeline,
            historyStore = InMemoryChunkHistoryStore(),
            formatter = object : IncrementalSourceFormatter<String> {
                override fun format(items: List<String>): String = items.joinToString("|")
            },
            config = WindowConfig(windowSize = 2, overlapSize = 0, triggerInterval = 2),
        )
        val context = SourceAnalysisContext(
            schema = schema,
            entityResolver = AlwaysCreateEntityResolver,
            contextId = contextId,
        )
        val source = ListSource("conversation", listOf("Alice knows Bob", "Carol knows Dan"))
        val entities = InMemoryNamedEntityDataRepository(schema)

        assertThrows(IllegalStateException::class.java) {
            analyzer.analyzeThenRecord(source, context) { it.persist(repository, entities) }
        }
        val firstAttempt = repository.findAll().single()

        val retried = analyzer.analyzeThenRecord(source, context) { it.persist(repository, entities) }!!

        val alice = retried.revisionResults.single { it is RevisionResult.Merged }
        assertTrue(alice.isReplay, "the claim the failed write stored is a replay: $alice")
        assertEquals(firstAttempt, repository.findById(firstAttempt.id))
        assertEquals(
            listOf("Alice knows Bob", "Carol knows Dan"),
            repository.findAll().map { it.text }.sorted(),
        )
    }
}
