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
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

class ScopedPolicyDelegationTest {
    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec = ScopedReadSpec.of(scope)
    private val clock = ReadClock(asOf, 0.7)
    private val timeout = Duration.ofSeconds(2).plusNanos(123)

    @Test
    fun `strict calls forward every parameter and return the native delegate result unchanged`() {
        factories().forEach { wrap ->
            val delegate = Recording()
            val reads = wrap(delegate) as StrictPolicyReadCapable
            val restricted = spec.includingOnly(listOf(PropositionKey("A", "a")))
                .excluding(listOf(PropositionKey("B", "hidden")))
                .restrict(PropositionQuery(pinned = true, minConfidence = 0.5))
            val grants = listOf(
                StrictReadRow(PropositionKey("B", "b"), ApprovedText.Omitted),
                StrictReadRow(PropositionKey("B", "stored"), ApprovedText.Stored),
                StrictReadRow(PropositionKey("A", "a"), ApprovedText.Literal(" ALPHA literal ")),
            )
            val tokens = listOf(" ALPHA ", "alpha", "", "literal")
            assertTrue(reads.supportsStrictPolicyReads)
            assertSame(delegate.result, reads.queryByGrantOrder(restricted, clock, grants, 2, timeout))
            assertSame(delegate.result, reads.keywordByApprovedText(restricted, clock, grants, tokens, 1, timeout))
            assertEquals(listOf("query", "keyword"), delegate.calls.map { it.operation })
            delegate.calls.forEach {
                assertSame(clock, it.clock)
                assertSame(restricted, it.spec)
                assertSame(grants, it.rows)
                assertSame(timeout, it.timeout)
                assertEquals(ApprovedText.Literal(" ALPHA literal "), it.rows.last().text)
            }
            assertEquals(2, delegate.calls.first().limit)
            assertNull(delegate.calls.first().tokens)
            assertEquals(1, delegate.calls.last().limit)
            assertSame(tokens, delegate.calls.last().tokens)
        }
    }

    @Test
    fun `runtime strict support is checked before both calls even on empty requests`() {
        factories().forEach { wrap ->
            val delegate = Recording()
            val reads = wrap(delegate) as StrictPolicyReadCapable
            delegate.supported = false
            assertTrue(reads.supportsScopedReads)
            assertFalse(reads.supportsStrictPolicyReads)
            assertUnsupported { reads.queryByGrantOrder(spec, clock, emptyList(), 0, timeout) }
            assertUnsupported { reads.keywordByApprovedText(spec, clock, emptyList(), emptyList(), 0, timeout) }
            assertTrue(delegate.calls.isEmpty())
            delegate.supported = true
            assertTrue(reads.supportsStrictPolicyReads)
            assertSame(delegate.result, reads.queryByGrantOrder(spec, clock, emptyList(), 0, timeout))
            delegate.supported = false
            assertUnsupported { reads.keywordByApprovedText(spec, clock, emptyList(), emptyList(), 0, timeout) }
            assertEquals(1, delegate.calls.size)
        }
    }

    @Test
    fun `legacy only and scoped only delegates cannot acquire strict capability success`() {
        factories().forEach { wrap ->
            val scoped = object : PropositionRepository by untouched(), ContextSetReadCapable by Recording() {}
            listOf(untouched<PropositionRepository>(), scoped).forEach { delegate ->
                val reads = wrap(delegate) as StrictPolicyReadCapable
                assertFalse(reads.supportsStrictPolicyReads)
                val grants = listOf(grant(row("a", "A")))
                assertUnsupported { reads.queryByGrantOrder(spec, clock, grants, 1, timeout) }
                assertUnsupported { reads.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 1, timeout) }
            }
        }
    }

    @Test
    fun `strict backend errors propagate by identity without partial publication or fallback`() {
        factories().forEach { wrap ->
            val delegate = Recording()
            val reads = wrap(delegate) as StrictPolicyReadCapable
            val grants = listOf(grant(row("a", "A")))
            listOf(ScopedReadException(ScopedReadFailure.SCOPED_READ_FAILED), IllegalArgumentException("Invalid grants"), IllegalStateException("Native failure")).forEach { failure ->
                delegate.failure = failure
                var published: List<Proposition>? = null
                assertSame(failure, assertThrows(failure.javaClass) {
                    published = reads.queryByGrantOrder(spec, clock, grants, 1, timeout)
                })
                assertNull(published)
                assertSame(failure, assertThrows(failure.javaClass) {
                    published = reads.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 1, timeout)
                })
                assertNull(published)
            }
            assertEquals(6, delegate.calls.size)
        }
    }

    private fun factories(): List<(PropositionRepository) -> PropositionStore> = listOf(
        { EventEmittingPropositionRepository(it) },
        { EventEmittingPropositionRepository.wrapping(it) },
        { Neo4jRagPropositionRepository(it, untouched()) },
        { Neo4jRagPropositionRepository(EventEmittingPropositionRepository.wrapping(it), untouched()) },
    )

    private fun row(id: String, context: String) = Proposition(
        id = id, contextId = ContextId(context), text = "alpha", mentions = emptyList(), confidence = 0.8,
        pinned = true, created = asOf, contentRevised = asOf, metadataRevised = asOf, lastAccessed = asOf,
    )

    private fun key(row: Proposition) = PropositionKey(row.contextId.value, row.id)
    private fun grant(row: Proposition, text: ApprovedText = ApprovedText.Stored) =
        StrictReadRow(key(row), text)

    private fun assertUnsupported(call: () -> Unit) {
        assertEquals(ScopedReadFailure.SCOPED_READ_UNSUPPORTED, assertThrows(ScopedReadException::class.java, call).failure)
    }

    private inner class Recording : PropositionRepository by untouched(), StrictPolicyReadCapable {
        var supported = true
        var failure: RuntimeException? = null
        override val supportsStrictPolicyReads get() = supported
        val result = listOf(row("a", "A"))
        val calls = mutableListOf<Call>()

        private fun record(call: Call): List<Proposition> {
            calls += call
            failure?.let { throw it }
            return result
        }

        override fun queryByGrantOrder(spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, limit: Int, timeout: Duration) =
            record(Call("query", spec, clock, rows, null, limit, timeout))

        override fun keywordByApprovedText(spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, tokens: List<String>, limit: Int, timeout: Duration) =
            record(Call("keyword", spec, clock, rows, tokens, limit, timeout))

        override fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition> = error("Unexpected ordinary read")
        override fun count(spec: ScopedReadSpec, clock: ReadClock): Long = error("Unexpected count")
        override fun keywordOverlap(spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int): List<Proposition> = error("Unexpected ordinary keyword read")
    }

    private data class Call(
        val operation: String,
        val spec: ScopedReadSpec,
        val clock: ReadClock,
        val rows: List<StrictReadRow>,
        val tokens: List<String>?,
        val limit: Int,
        val timeout: Duration,
    )

    companion object {
        private inline fun <reified T> untouched(): T = T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                error("Unexpected legacy, entity, lookup or write call: ${method.name}")
            },
        )
    }
}
