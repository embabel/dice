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
import com.embabel.dice.agent.AllowedSource
import com.embabel.dice.agent.ApprovedText
import com.embabel.dice.agent.MemoryField
import com.embabel.dice.agent.ReadPolicyStamp
import com.embabel.dice.agent.RowGrant
import com.embabel.dice.agent.RowPolicy
import com.embabel.dice.agent.RowProjection
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.proposition.store.JsonFilePropositionRepository
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class ScopedPolicyDelegationTest {
    enum class Backend { MEMORY, JSON, JSON_RELOADED }

    @TempDir
    lateinit var directory: Path

    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec = ScopedReadSpec.at(asOf, 0.7)
    private val timeout = Duration.ofSeconds(2).plusNanos(123)

    @Test
    fun `strict calls forward every parameter and return the native delegate result unchanged`() {
        factories().forEach { wrap ->
            val delegate = Recording()
            val reads = wrap(delegate) as StrictPolicyReadCapable
            val restricted = spec.includingOnly(scope, listOf(PropositionKey("A", "a")))
                .excluding(scope, listOf(PropositionKey("B", "hidden")))
                .restrict(scope, PropositionQuery(pinned = true, minConfidence = 0.5))
            val grants = listOf(
                StrictReadRow(PropositionKey("A", "a"), 91, ApprovedSearchText.Literal(" ALPHA literal ")),
                StrictReadRow(PropositionKey("B", "b"), 3, ApprovedSearchText.Omitted),
                StrictReadRow(PropositionKey("B", "stored"), 17, ApprovedSearchText.Stored),
            )
            val tokens = listOf(" ALPHA ", "alpha", "", "literal")
            assertTrue(reads.supportsStrictPolicyReads)
            assertSame(delegate.result, reads.queryByGrantOrder(scope, restricted, grants, 2, timeout))
            assertSame(delegate.result, reads.keywordByApprovedText(scope, restricted, grants, tokens, 1, timeout))
            assertEquals(listOf("query", "keyword"), delegate.calls.map { it.operation })
            delegate.calls.forEach {
                assertSame(scope, it.scope)
                assertSame(restricted, it.spec)
                assertSame(grants, it.rows)
                assertSame(timeout, it.timeout)
                assertEquals(listOf(91, 3, 17), it.rows.map { row -> row.ordinal })
                assertEquals(ApprovedSearchText.Literal(" ALPHA literal "), it.rows.first().text)
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
            assertUnsupported { reads.queryByGrantOrder(scope, spec, emptyList(), 0, timeout) }
            assertUnsupported { reads.keywordByApprovedText(scope, spec, emptyList(), emptyList(), 0, timeout) }
            assertTrue(delegate.calls.isEmpty())
            delegate.supported = true
            assertTrue(reads.supportsStrictPolicyReads)
            assertSame(delegate.result, reads.queryByGrantOrder(scope, spec, emptyList(), 0, timeout))
            delegate.supported = false
            assertUnsupported { reads.keywordByApprovedText(scope, spec, emptyList(), emptyList(), 0, timeout) }
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
                val grants = listOf(grant(row("a", "A"), 0))
                assertUnsupported { reads.queryByGrantOrder(scope, spec, grants, 1, timeout) }
                assertUnsupported { reads.keywordByApprovedText(scope, spec, grants, listOf("alpha"), 1, timeout) }
            }
        }
    }

    @Test
    fun `strict backend errors propagate by identity without partial publication or fallback`() {
        factories().forEach { wrap ->
            val delegate = Recording()
            val reads = wrap(delegate) as StrictPolicyReadCapable
            val grants = listOf(grant(row("a", "A"), 0))
            listOf(ScopedReadException("SCOPED_READ_FAILED"), IllegalArgumentException("Invalid grants"), IllegalStateException("Native failure")).forEach { failure ->
                delegate.failure = failure
                var published: List<Proposition>? = null
                assertSame(failure, assertThrows(failure.javaClass) {
                    published = reads.queryByGrantOrder(scope, spec, grants, 1, timeout)
                })
                assertNull(published)
                assertSame(failure, assertThrows(failure.javaClass) {
                    published = reads.keywordByApprovedText(scope, spec, grants, listOf("alpha"), 1, timeout)
                })
                assertNull(published)
            }
            assertEquals(6, delegate.calls.size)
        }
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `wrapped local reads apply grants and membership before global limit`(backend: Backend) {
        factories().forEachIndexed { index, wrap ->
            val a = row("a", "A", "hidden secret alpha", 1.0)
            val b = row("b", "B", "hidden secret alpha beta", 0.1)
            val hidden = row("hidden", "B", "alpha beta", 1.0)
            val excluded = row("excluded", "B", "alpha beta", 1.0)
            val filtered = row("filtered", "B", "alpha beta", 1.0).copy(pinned = false)
            val notIncluded = row("not-included", "B", "alpha beta", 1.0)
            val forbidden = row("forbidden", "C", "alpha beta", 1.0)
            val store = repository(backend, index, listOf(a, b, hidden, excluded, filtered, notIncluded, forbidden))
            val reads = wrap(store) as StrictPolicyReadCapable
            val grants = listOf(
                grant(a, 91, ApprovedSearchText.Literal("beta")),
                grant(b, 3, ApprovedSearchText.Literal("alpha beta")),
                grant(excluded, 0), grant(filtered, 1), grant(notIncluded, 2),
            )
            val restricted = spec.includingOnly(scope, listOf(a, b, hidden, excluded, filtered).map(::key))
                .excluding(scope, listOf(key(excluded)))
                .restrict(scope, PropositionQuery(pinned = true))
            val before = store.findAll().associateBy { it.id }
            assertEquals(listOf(b), reads.queryByGrantOrder(scope, restricted, grants, 1, timeout))
            assertEquals(listOf(b, a), reads.queryByGrantOrder(scope, restricted, grants, 2, timeout))
            assertEquals(listOf(b), reads.keywordByApprovedText(scope, restricted, grants, listOf("alpha", "beta"), 1, timeout))
            assertTrue(reads.keywordByApprovedText(scope, restricted, grants, listOf("secret"), 5, timeout).isEmpty())
            assertTrue(reads.queryByGrantOrder(scope, restricted.includingOnly(scope, emptyList()), grants, 5, timeout).isEmpty())
            assertTrue(reads.keywordByApprovedText(scope, restricted.includingOnly(scope, emptyList()), grants, listOf("alpha"), 5, timeout).isEmpty())
            assertTrue(reads.queryByGrantOrder(scope, restricted, listOf(StrictReadRow(PropositionKey("A", "b"), 0, ApprovedSearchText.Stored)), 1, timeout).isEmpty())
            assertEquals(before, store.findAll().associateBy { it.id })
            store.save(a.copy(confidence = 0.0, text = "alpha beta secret", importance = 0.0))
            store.save(b.copy(confidence = 1.0, text = "no match", importance = 1.0))
            assertEquals(listOf("b", "a"), reads.queryByGrantOrder(scope, restricted, grants, 2, timeout).map { it.id })
            assertEquals(listOf("b", "a"), reads.keywordByApprovedText(scope, restricted, grants, listOf("alpha", "beta"), 2, timeout).map { it.id })
        }
    }

    @Test
    fun `wrapped local strict operations preserve validation and omitted text`() {
        factories().forEach { wrap ->
            val a = row("a", "A", "secret alpha")
            val store = InMemoryPropositionRepository()
            store.save(a)
            val reads = wrap(store) as StrictPolicyReadCapable
            val omitted = listOf(grant(a, 0, ApprovedSearchText.Omitted))
            assertTrue(reads.keywordByApprovedText(scope, spec, omitted, listOf("secret"), 1, timeout).isEmpty())
            assertEquals(listOf(a), reads.queryByGrantOrder(scope, spec, omitted, 1, timeout))
            val invalid = listOf(omitted + omitted, listOf(grant(row("c", "C"), 0)))
            invalid.forEach { rows ->
                assertThrows(IllegalArgumentException::class.java) { reads.queryByGrantOrder(scope, spec, rows, 1, timeout) }
                assertThrows(IllegalArgumentException::class.java) { reads.keywordByApprovedText(scope, spec, rows, listOf("alpha"), 1, timeout) }
            }
            assertThrows(IllegalArgumentException::class.java) { reads.queryByGrantOrder(scope, spec, omitted, 1, Duration.ZERO) }
            assertThrows(IllegalArgumentException::class.java) { reads.keywordByApprovedText(scope, spec, omitted, listOf("alpha"), 1, Duration.ZERO) }
        }
    }

    @Test
    fun `strict projection dependency validation rejects hidden fields and sources before wrapped calls`() {
        factories().forEach { wrap ->
            val delegate = Recording()
            val reads = wrap(delegate) as StrictPolicyReadCapable
            val projection = RowProjection.text(ApprovedText.Literal("alpha"))
                .withFields(setOf(MemoryField.EFFECTIVE_CONFIDENCE))
            val policy = RowPolicy.of(
                ReadPolicyStamp("principal", "purpose", "policy", "1"),
                listOf(RowGrant.of(PropositionKey("A", "a"), projection, true, false)),
            )
            val filtered = spec.restrict(scope, PropositionQuery(minConfidence = 0.5))
            assertThrows(IllegalArgumentException::class.java) {
                policy.validate(scope, filtered)
                reads.queryByGrantOrder(scope, filtered, emptyList(), 1, timeout)
            }
            assertThrows(IllegalArgumentException::class.java) {
                policy.validate(scope, spec, sourceInputs = listOf(AllowedSource("hidden-source", true, true)))
                reads.keywordByApprovedText(scope, spec, emptyList(), listOf("alpha"), 1, timeout)
            }
            assertThrows(IllegalArgumentException::class.java) {
                policy.validate(scope, spec, scoreInputs = setOf(MemoryField.RAW_CONFIDENCE))
                reads.queryByGrantOrder(scope, spec, emptyList(), 1, timeout)
            }
            assertTrue(delegate.calls.isEmpty())
            policy.validate(scope, spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, keywordSearch = true)
            val grants = listOf(StrictReadRow(policy.grants.single().key, 0, ApprovedSearchText.Literal("alpha")))
            assertSame(delegate.result, reads.keywordByApprovedText(scope, spec, grants, listOf("alpha"), 1, timeout))
            assertEquals(1, delegate.calls.size)
        }
    }

    private fun factories(): List<(PropositionRepository) -> PropositionStore> = listOf(
        { EventEmittingPropositionRepository(it) },
        { EventEmittingPropositionRepository.wrapping(it) },
        { Neo4jRagPropositionRepository(it, untouched()) },
        { Neo4jRagPropositionRepository(EventEmittingPropositionRepository.wrapping(it), untouched()) },
    )

    private fun repository(backend: Backend, index: Int, rows: List<Proposition>): PropositionRepository {
        val file = directory.resolve("$backend-$index.json")
        val store = if (backend == Backend.MEMORY) InMemoryPropositionRepository() else JsonFilePropositionRepository(file)
        store.saveAll(rows.reversed())
        return if (backend == Backend.JSON_RELOADED) JsonFilePropositionRepository(file) else store
    }

    private fun row(id: String, context: String, text: String = "alpha", confidence: Double = 0.8) = Proposition(
        id = id, contextId = ContextId(context), text = text, mentions = emptyList(), confidence = confidence,
        pinned = true, created = asOf, contentRevised = asOf, metadataRevised = asOf, lastAccessed = asOf,
    )

    private fun key(row: Proposition) = PropositionKey(row.contextId.value, row.id)
    private fun grant(row: Proposition, ordinal: Int, text: ApprovedSearchText = ApprovedSearchText.Stored) =
        StrictReadRow(key(row), ordinal, text)

    private fun assertUnsupported(call: () -> Unit) {
        assertEquals("SCOPED_READ_UNSUPPORTED", assertThrows(ScopedReadException::class.java, call).code)
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

        override fun queryByGrantOrder(scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int, timeout: Duration) =
            record(Call("query", scope, spec, rows, null, limit, timeout))

        override fun keywordByApprovedText(scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, tokens: List<String>, limit: Int, timeout: Duration) =
            record(Call("keyword", scope, spec, rows, tokens, limit, timeout))

        override fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> = error("Unexpected ordinary read")
        override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long = error("Unexpected count")
        override fun keywordOverlap(scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int): List<Proposition> = error("Unexpected ordinary keyword read")
    }

    private data class Call(
        val operation: String,
        val scope: ContextReadScope,
        val spec: ScopedReadSpec,
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
