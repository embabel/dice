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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionMerged
import com.embabel.dice.common.PropositionReinforced
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.revision.ClassificationItem
import com.embabel.dice.proposition.revision.ClassificationResponse
import com.embabel.dice.proposition.revision.ClassifiedProposition
import com.embabel.dice.proposition.revision.InRunPropositions
import com.embabel.dice.proposition.revision.LlmPropositionReviser
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk

/**
 * What one pipeline run does when several results touch the same stored proposition, and what a
 * replay, a result that changes nothing, still reports.
 */
class InRunRevisionTest {

    private val contextId = ContextId("in-run")
    private val schema = DataDictionary.fromClasses("test", Person::class.java)

    /** Turns every chunk into one proposition with the chunk's text and no mentions. */
    private object OnePropositionPerChunk : PropositionExtractor {
        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions =
            SuggestedPropositions(
                chunkId = chunk.id,
                propositions = chunk.text.split("|").map { SuggestedProposition(text = it, mentions = emptyList(), confidence = 0.9) },
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

    private fun context() = SourceAnalysisContext(
        schema = schema,
        entityResolver = AlwaysCreateEntityResolver,
        contextId = contextId,
    )

    private fun pipeline(repository: InMemoryPropositionRepository, listener: DiceEventListener = DiceEventListener.DEV_NULL) =
        PropositionPipeline
            .withExtractor(OnePropositionPerChunk)
            .withRevision(LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()), repository)
            .withEventListener(listener)

    private fun stored(repository: InMemoryPropositionRepository): Proposition {
        val proposition = Proposition(
            contextId = contextId,
            text = "Alice knows Bob",
            mentions = emptyList(),
            confidence = 0.9,
            grounding = listOf("chunk-0"),
        )
        repository.save(proposition)
        return repository.findById(proposition.id)!!
    }

    @Test
    fun `two chunks in one run that restate a stored proposition both reinforce it`() {
        val repository = InMemoryPropositionRepository()
        val stored = stored(repository)

        val results = pipeline(repository).process(
            listOf(
                Chunk.create(text = "Alice knows Bob", parentId = "doc", id = "chunk-1"),
                Chunk.create(text = "Alice knows Bob", parentId = "doc", id = "chunk-2"),
            ),
            context(),
        )
        results.persist(repository, InMemoryNamedEntityDataRepository(schema))

        val saved = repository.findById(stored.id)!!
        assertEquals(stored.reinforceCount + 2, saved.reinforceCount)
        assertTrue(saved.grounding.containsAll(listOf("chunk-0", "chunk-1", "chunk-2")), "grounding: ${saved.grounding}")
        val chunkIds = saved.provenanceEntries.map { it.chunkId }
        assertTrue(chunkIds.containsAll(listOf("chunk-1", "chunk-2")), "provenance: $chunkIds")
    }

    @Test
    fun `the second chunk revises the copy the first chunk produced`() {
        val repository = InMemoryPropositionRepository()
        stored(repository)

        val results = pipeline(repository).process(
            listOf(
                Chunk.create(text = "Alice knows Bob", parentId = "doc", id = "chunk-1"),
                Chunk.create(text = "Alice knows Bob", parentId = "doc", id = "chunk-2"),
            ),
            context(),
        )

        val (first, second) = results.revisionResults.map { it as RevisionResult.Merged }
        assertEquals(first.revised, second.original)
        assertEquals(listOf(second.revised), results.revisedPropositionsToPersist)
    }

    @Test
    fun `a replay emits no merge or reinforcement event and is not counted`() {
        val repository = InMemoryPropositionRepository()
        val chunk = Chunk.create(text = "Alice knows Bob", parentId = "doc", id = "chunk-0")
        val created = pipeline(repository).processChunk(chunk, context()).revisionResults.single() as RevisionResult.New
        repository.save(created.proposition)
        val events = mutableListOf<DiceEvent>()

        val result = pipeline(repository) { events.add(it) }.processChunk(chunk, context())

        val replay = result.revisionResults.single() as RevisionResult.Merged
        assertEquals(replay.original, replay.revised)
        assertTrue(events.none { it is PropositionMerged || it is PropositionReinforced }, "events: $events")
        assertEquals(0, result.propositionExtractionStats.mergedCount)
        assertEquals(0, result.propositionExtractionStats.total)
    }

    @Test
    fun `a replay after a contradiction does not bring the stored proposition back`() {
        val stored = Proposition(contextId = contextId, text = "Alice knows Bob", mentions = emptyList(), confidence = 0.9)
        val contradicted = stored.copy(confidence = 0.27, status = PropositionStatus.CONTRADICTED)
        val contradicting = Proposition(contextId = contextId, text = "Alice has never met Bob", mentions = emptyList(), confidence = 0.9)

        val write = ChunkPropositionResult.success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
            entityResolutions = Resolutions(setOf("chunk-1"), emptyList()),
            propositions = emptyList(),
            revisionResults = listOf(
                RevisionResult.Contradicted(contradicted, contradicting),
                RevisionResult.Merged(stored, stored),
            ),
        ).revisedPropositionsToPersist

        assertEquals(listOf(contradicted, contradicting), write)
    }

    @Test
    fun `the write set holds one final copy of each stored proposition`() {
        val stored = Proposition(contextId = contextId, text = "Alice knows Bob", mentions = emptyList(), confidence = 0.9)
        val once = stored.copy(grounding = listOf("chunk-1"), reinforceCount = 1)
        val twice = once.copy(grounding = listOf("chunk-1", "chunk-2"), reinforceCount = 2)

        val write = ChunkPropositionResult.success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
            entityResolutions = Resolutions(setOf("chunk-1"), emptyList()),
            propositions = emptyList(),
            revisionResults = listOf(RevisionResult.Merged(stored, once), RevisionResult.Reinforced(once, twice)),
        ).revisedPropositionsToPersist

        assertEquals(listOf(twice), write)
    }

    @Test
    fun `the write set keeps the last copy in the chain even when results come back out of order`() {
        val stored = Proposition(contextId = contextId, text = "Alice knows Bob", mentions = emptyList(), confidence = 0.9)
        val once = stored.copy(grounding = listOf("chunk-1"), reinforceCount = 1)
        val twice = once.copy(grounding = listOf("chunk-1", "chunk-2"), reinforceCount = 2)

        val write = ChunkPropositionResult.success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
            entityResolutions = Resolutions(setOf("chunk-1"), emptyList()),
            propositions = emptyList(),
            revisionResults = listOf(RevisionResult.Reinforced(once, twice), RevisionResult.Merged(stored, once)),
        ).revisedPropositionsToPersist

        assertEquals(listOf(twice), write)
    }

    @Test
    fun `a chunk that fails after revising leaves nothing behind for later chunks`() {
        val repository = InMemoryPropositionRepository()
        val stored = stored(repository)
        // Reinforces the stored proposition with each proposition's grounding, and fails on "boom".
        val reviser = object : PropositionReviser {
            override fun revise(newProposition: Proposition, repository: PropositionRepository): RevisionResult {
                check(newProposition.text != "boom") { "classification failed" }
                val original = repository.findById(stored.id)!!
                return RevisionResult.Reinforced(original, original.absorbEvidence(newProposition))
            }

            override fun classify(newProposition: Proposition, candidates: List<Proposition>) = emptyList<ClassifiedProposition>()
        }

        val results = PropositionPipeline.withExtractor(OnePropositionPerChunk).withRevision(reviser, repository).process(
            listOf(
                Chunk.create(text = "Alice knows Bob|boom", parentId = "doc", id = "chunk-1"),
                Chunk.create(text = "Alice knows Bob", parentId = "doc", id = "chunk-2"),
            ),
            context(),
        )

        assertEquals(listOf("chunk-1"), results.failedChunkIds)
        val saved = results.revisedPropositionsToPersist.single()
        assertEquals(stored.reinforceCount + 1, saved.reinforceCount)
        assertEquals(listOf("chunk-0", "chunk-2"), saved.grounding)
    }

    @Test
    fun `a chunk whose results come back out of order hands its final copy to the next chunk`() {
        val repository = InMemoryPropositionRepository()
        val stored = stored(repository)
        // Like LlmPropositionReviser, applies the second proposition before the first but returns
        // results in input order. Each proposition's text is its source id, so each one is new evidence.
        val reviser = object : PropositionReviser {
            override fun revise(newProposition: Proposition, repository: PropositionRepository): RevisionResult {
                val original = repository.findById(stored.id)!!
                return RevisionResult.Reinforced(
                    original,
                    original.absorbEvidence(newProposition.copy(sourceIds = listOf(newProposition.text))),
                )
            }

            override fun reviseAll(propositions: List<Proposition>, repository: PropositionRepository): List<RevisionResult> {
                val view = InRunPropositions.over(repository)
                return propositions.reversed().map { revise(it, view).also(view::record) }.reversed()
            }

            override fun classify(newProposition: Proposition, candidates: List<Proposition>) = emptyList<ClassifiedProposition>()
        }

        val results = PropositionPipeline.withExtractor(OnePropositionPerChunk).withRevision(reviser, repository).process(
            listOf(
                Chunk.create(text = "first|second", parentId = "doc", id = "chunk-1"),
                Chunk.create(text = "third", parentId = "doc", id = "chunk-2"),
            ),
            context(),
        )

        val saved = results.revisedPropositionsToPersist.single()
        assertEquals(stored.reinforceCount + 3, saved.reinforceCount)
        assertEquals(setOf("first", "second", "third"), saved.sourceIds.toSet())
    }

    @Test
    fun `a contradiction classified after a fast path merge of the same stored proposition is what gets saved`() {
        val repository = InMemoryPropositionRepository()
        val stored = stored(repository)
        // Every active proposition in the context comes back as a mid-range vector match, so the
        // contradicting text goes to the model while the restated text merges on the canonical fast path.
        val searchable = object : PropositionRepository by repository {
            override fun findSimilarWithScores(
                textSimilaritySearchRequest: TextSimilaritySearchRequest,
                query: PropositionQuery,
            ): List<SimilarityResult<Proposition>> = repository.query(query).map { SimilarityResult(match = it, score = 0.7) }
        }
        val ai = mockk<Ai>()
        val runner = mockk<PromptRunner>()
        val creating = mockk<PromptRunner.Creating<ClassificationResponse>>()
        every { ai.withLlm(any<LlmOptions>()) } returns runner
        every { runner.withId(any()) } returns runner
        every { runner.creating(ClassificationResponse::class.java) } returns creating
        every { creating.fromTemplate(any(), any()) } returns
            ClassificationResponse(listOf(ClassificationItem("0", "CONTRADICTORY", 0.9, "opposite")))
        val pipeline = PropositionPipeline
            .withExtractor(OnePropositionPerChunk)
            .withRevision(LlmPropositionReviser(llmOptions = LlmOptions(), ai = ai), searchable)

        val result = pipeline.processChunk(
            Chunk.create(text = "Alice has never met Bob|Alice knows Bob", parentId = "doc", id = "chunk-1"),
            context(),
        )
        result.persist(repository, InMemoryNamedEntityDataRepository(schema))

        val saved = repository.findById(stored.id)!!
        assertEquals(PropositionStatus.CONTRADICTED, saved.status)
        assertTrue("chunk-1" in saved.grounding, "grounding: ${saved.grounding}")
    }
}
