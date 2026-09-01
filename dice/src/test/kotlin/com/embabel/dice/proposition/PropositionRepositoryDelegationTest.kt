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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards against the mutual-recursion bug between [findByContextId] and [findByContextIdValue].
 * Neither default override is provided, so calls go through the decorator's `by delegate`
 * forwarding into the interface defaults. [findByContextIdValue] must filter [findAll] directly
 * rather than delegating back to [findByContextId], which previously caused a [StackOverflowError].
 */
class PropositionRepositoryDelegationTest {

    private val contextId = ContextId("ctx-a")
    private val otherContextId = ContextId("ctx-b")

    private fun proposition(ctx: ContextId, text: String): Proposition =
        Proposition(
            contextId = ctx,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    /**
     * Overrides only the abstract members — deliberately leaves findByContextId and
     * findByContextIdValue to their default implementations, which is exactly the
     * recursion site under test.
     */
    private open inner class MinimalRepository : PropositionRepository {
        private val store = mutableMapOf<String, Proposition>()
        fun seed(p: Proposition) { store[p.id] = p }

        override val luceneSyntaxNotes: String = "test"
        override fun save(proposition: Proposition): Proposition { store[proposition.id] = proposition; return proposition }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findSimilarWithScores(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    /**
     * A backend that opts in to [SourceRevisionQueryCapable] and implements only the plain-String
     * finders. That is the implementation point a Java backend can actually reach, since the
     * ContextId-typed methods compile to mangled JVM names.
     */
    private inner class StringSourceOverrideRepository : MinimalRepository(), SourceRevisionQueryCapable {
        val sourceKeyResult = listOf(proposition(contextId, "source-key"))
        val sourceRevisionResult = listOf(proposition(contextId, "source-revision"))
        val revisionlessResult = listOf(proposition(contextId, "revisionless"))

        var sourceKeyCalls = 0
        var sourceRevisionCalls = 0
        var revisionlessCalls = 0
        val receivedContexts = mutableListOf<String>()

        override fun findBySourceKey(contextIdValue: String, sourceKey: String): List<Proposition> {
            sourceKeyCalls++
            receivedContexts += contextIdValue
            return sourceKeyResult
        }

        override fun findBySourceRevision(
            contextIdValue: String,
            ref: SourceRevisionRef,
        ): List<Proposition> {
            sourceRevisionCalls++
            receivedContexts += contextIdValue
            return sourceRevisionResult
        }

        override fun findRevisionlessBySourceLocator(
            contextIdValue: String,
            locator: SourceLocator,
        ): List<Proposition> {
            revisionlessCalls++
            receivedContexts += contextIdValue
            return revisionlessResult
        }
    }

    @Test
    fun `findByContextId does not StackOverflow through the decorator`() {
        val delegate = MinimalRepository().apply {
            seed(proposition(contextId, "a1"))
            seed(proposition(contextId, "a2"))
            seed(proposition(otherContextId, "b1"))
        }
        val repo = EventEmittingPropositionRepository(delegate, DiceEventListener.DEV_NULL)

        val result = assertDoesNotThrow<List<Proposition>> {
            repo.findByContextId(contextId)
        }
        val expected = delegate.findAll().filter { it.contextId.value == contextId.value }
        assertEquals(expected.toSet(), result.toSet())
        assertEquals(2, result.size)
    }

    @Test
    fun `findByContextIdValue does not StackOverflow through the decorator`() {
        val delegate = MinimalRepository().apply {
            seed(proposition(contextId, "a1"))
            seed(proposition(otherContextId, "b1"))
        }
        val repo = EventEmittingPropositionRepository(delegate, DiceEventListener.DEV_NULL)

        val result = assertDoesNotThrow<List<Proposition>> {
            repo.findByContextIdValue(contextId.value)
        }
        val expected = delegate.findAll().filter { it.contextId.value == contextId.value }
        assertEquals(expected.toSet(), result.toSet())
        assertEquals(1, result.size)
    }

    /**
     * Calls the typed entry points against a backend that overrode only the String variants. A
     * failure here means a typed call site skips the override — the shape that would make a Java
     * backend's pushdown unreachable from Kotlin.
     */
    @Test
    fun `typed source finders dispatch once through the implementable string variant`() {
        val repository = StringSourceOverrideRepository()
        val locator = UriLocator("https://example.com/source")
        val ref = SourceRevisionRef(sourceKey = locator.key(), sourceRevision = "rev-1")

        assertEquals(
            repository.sourceKeyResult,
            repository.findBySourceKey(contextId, locator.key()),
        )
        assertEquals(
            repository.sourceRevisionResult,
            repository.findBySourceRevision(contextId, ref),
        )
        assertEquals(
            repository.revisionlessResult,
            repository.findRevisionlessBySourceLocator(contextId, locator),
        )

        assertEquals(1, repository.sourceKeyCalls)
        assertEquals(1, repository.sourceRevisionCalls)
        assertEquals(1, repository.revisionlessCalls)
        assertEquals(
            listOf(contextId.value, contextId.value, contextId.value),
            repository.receivedContexts,
        )
    }

    /**
     * The capability split, checked at the type level. A store that implements only
     * [PropositionRepository] has no source-revision surface at all, so a call site asks for the
     * capability with `as?` and gets null. That null is the honest answer "this backend cannot look",
     * which an empty result list could never have expressed.
     */
    @Test
    fun `a plain repository does not satisfy a source-revision call site`() {
        val plain: PropositionRepository = MinimalRepository().apply {
            seed(proposition(contextId, "a1"))
        }

        assertNull(
            plain as? SourceRevisionQueryCapable,
            "a store that never promised to answer must report absence where an empty list would lie",
        )
        assertNotNull(
            StringSourceOverrideRepository() as? SourceRevisionQueryCapable,
            "a store that did promise is reachable through the capability type",
        )
    }

    /**
     * The event-emitting decorator wraps a plain [PropositionRepository], so whether it can answer
     * depends on the delegate it was handed. It carries the capability type either way, which makes
     * [SourceRevisionQueryCapable.supportsSourceRevisionQueries] the runtime truth, and a call that
     * gets past the flag names the delegate that cannot answer.
     */
    @Test
    fun `the decorator reports and refuses what its delegate cannot answer`() {
        val capableDelegate = StringSourceOverrideRepository()
        val capableDecorator = EventEmittingPropositionRepository(capableDelegate, DiceEventListener.DEV_NULL)
        assertEquals(true, capableDecorator.supportsSourceRevisionQueries)
        assertEquals(
            capableDelegate.sourceKeyResult,
            capableDecorator.findBySourceKey(contextId, "uri:https://example.com/source"),
        )

        val plainDecorator = EventEmittingPropositionRepository(MinimalRepository(), DiceEventListener.DEV_NULL)
        assertEquals(false, plainDecorator.supportsSourceRevisionQueries)
        val failure = assertThrows(UnsupportedOperationException::class.java) {
            plainDecorator.findBySourceKey(contextId, "uri:https://example.com/source")
        }
        assertTrue(
            failure.message!!.contains("MinimalRepository"),
            "the refusal names the delegate that cannot answer: ${failure.message}",
        )
    }
}
