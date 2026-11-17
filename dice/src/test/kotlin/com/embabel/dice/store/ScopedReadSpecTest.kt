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
package com.embabel.dice.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Type
import java.time.Instant
import java.util.EnumSet
import java.util.function.UnaryOperator

/**
 * Tests for [ScopedReadSpec].
 *
 * Contract:
 * E1 A new spec has the scope it was built from and no filter.
 * E2 narrow adds a filter and keeps the filters and the contexts a spec has, and the function form adds the same filter.
 * E3 A filter that carries an instant is rejected.
 * E4 Narrowing to contexts keeps only those contexts and every filter.
 * E5 A context the spec does not allow is refused with a read refusal that names only the contexts the spec allows.
 * E6 Narrowing to no context or to a blank id is rejected.
 * E7 A narrowed spec names no context it dropped, in its scope, its text form or a refusal. Its filters bind only code that passes it on.
 * E8 Narrowing by criteria that set nothing returns the same spec.
 * E9 requireOnly checks every filter and names the first criterion a store does not apply.
 * E10 No scoped type refers to PropositionQuery, in a signature, a generic argument or a field.
 */
class ScopedReadSpecTest {

    private val scope = ContextReadScope.ofValues("A", "B")
    private val confident = ReadCriteria.empty().withMinConfidence(0.8)

    @Test
    fun `E1 a new spec has the scope it was built from and no filter`() {
        val spec = ScopedReadSpec.of(scope)
        assertSame(scope, spec.scope)
        assertTrue(spec.filters.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (spec.scope.contextIdValues as MutableSet<String>).add("C")
        }
    }

    @Test
    fun `E2 narrow adds a filter and keeps the filters and the contexts a spec has`() {
        val first = ScopedReadSpec.of(scope).narrow(confident)
        val second = first.narrow(ReadCriteria.empty().withPinned(true))
        assertEquals(listOf(confident), first.filters)
        assertEquals(first.filters, second.filters.dropLast(1))
        assertEquals(first.scope, second.scope)
        val byFunction = ScopedReadSpec.of(scope).narrow(UnaryOperator<ReadCriteria> { it.withMinConfidence(0.8) })
        assertEquals(first, byFunction)
        assertThrows(UnsupportedOperationException::class.java) {
            (second.filters as MutableList<ReadCriteria>).clear()
        }
    }

    @Test
    fun `E3 a filter that carries an instant is rejected`() {
        val timed = ReadCriteria.empty()
            .withMinEffectiveConfidence(0.3)
            .withEffectiveConfidenceAsOf(Instant.parse("2026-01-01T00:00:00Z"))
        assertThrows(IllegalArgumentException::class.java) { ScopedReadSpec.of(scope).narrow(timed) }
    }

    @Test
    fun `E4 narrowing to contexts keeps only those contexts and every filter`() {
        val onlyA = ScopedReadSpec.of(scope).narrow(confident).narrowToContexts("A")
        assertEquals(setOf("A"), onlyA.scope.contextIdValues)
        assertEquals(listOf(confident), onlyA.filters)
        assertEquals(onlyA, onlyA.narrowToContexts(listOf("A", "A")))
        assertEquals(scope, ScopedReadSpec.of(scope).narrowToContexts("B", "A").scope)
    }

    @Test
    fun `E5 a refusal is a read refusal that names only the contexts the spec allows`() {
        val spec = ScopedReadSpec.of(scope)
        val outside = assertThrows(ReadScopeViolationException::class.java) { spec.narrowToContexts("A", "C") }
        assertEquals("C", outside.contextIdValue)
        assertEquals(setOf("A", "B"), outside.allowedContextIdValues)
        assertTrue(ReadRefusedException::class.java.isInstance(outside))
        assertFalse(IllegalArgumentException::class.java.isInstance(outside))
        val onlyA = spec.narrowToContexts("A")
        val narrowed = assertThrows(ReadScopeViolationException::class.java) { onlyA.narrowToContexts("B") }
        assertEquals("B", narrowed.contextIdValue)
        assertEquals(setOf("A"), narrowed.allowedContextIdValues)
        assertThrows(ReadScopeViolationException::class.java) { spec.narrowToContexts("a") }
    }

    @Test
    fun `E6 narrowing to no context or to a blank id is rejected`() {
        val spec = ScopedReadSpec.of(scope)
        assertThrows(IllegalArgumentException::class.java) { spec.narrowToContexts(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { spec.narrowToContexts("A", " ") }
    }

    @Test
    fun `E7 a narrowed spec names no context it dropped and its filters bind only code that passes it on`() {
        val wide = ScopedReadSpec.of(ContextReadScope.ofValues("alpha", "bravo", "charlie")).narrow(confident)
        val narrowed = wide.narrowToContexts("alpha")
        val refusal = assertThrows(ReadScopeViolationException::class.java) { narrowed.narrowToContexts("bravo") }
        val told = listOf(
            narrowed.scope.contextIdValues.toString(),
            narrowed.scope.toString(),
            narrowed.toString(),
            refusal.allowedContextIdValues.toString(),
        )
        assertTrue(told.none { it.contains("bravo") || it.contains("charlie") })
        assertFalse(refusal.message.orEmpty().contains("charlie"))

        // The scope and the factory are public, so a holder can build a spec over the same
        // contexts with no filter. The KDoc says so, and this pins it.
        val rebuilt = ScopedReadSpec.of(narrowed.scope)
        assertEquals(narrowed.scope, rebuilt.scope)
        assertTrue(rebuilt.filters.isEmpty())
    }

    @Test
    fun `E8 narrowing by criteria that set nothing returns the same spec`() {
        val spec = ScopedReadSpec.of(scope).narrow(confident)
        assertSame(spec, spec.narrow(ReadCriteria.empty()))
        assertSame(spec, spec.narrow(UnaryOperator<ReadCriteria> { it }))
        assertEquals(2, spec.narrow(ReadCriteria.empty().withDecayK(4.0)).filters.size)
    }

    @Test
    fun `E9 requireOnly checks every filter and names the first criterion a store does not apply`() {
        val spec = ScopedReadSpec.of(scope).narrow(confident).narrow(ReadCriteria.empty().withPinned(true))
        spec.requireOnly(EnumSet.of(ReadCriterion.MIN_CONFIDENCE, ReadCriterion.PINNED), StoreCapability.SCOPED_READS)
        ScopedReadSpec.of(scope).requireOnly(emptySet(), StoreCapability.SCOPED_READS)
        val refusal = assertThrows(UnsupportedCapabilityException::class.java) {
            spec.requireOnly(setOf(ReadCriterion.MIN_CONFIDENCE), StoreCapability.SCOPED_READS)
        }
        assertEquals(StoreCapability.SCOPED_READS, refusal.capability)
        assertTrue(refusal.message.orEmpty().contains("PINNED"))
    }

    @Test
    fun `E10 no scoped type refers to PropositionQuery`() {
        // A PropositionQuery that names no context matches every context in an ordinary read, so
        // no scoped type may accept, return or hold one, erased or as a generic argument.
        val scoped: List<Class<*>> = listOf(
            ScopedReadSpec::class.java, ScopedReadSpec.Companion::class.java,
            ReadCriteria::class.java, ReadCriteria.Companion::class.java, ReadCriteriaBuilder::class.java,
            ReadQuery::class.java, ReadQuery.Companion::class.java, ReadQueryBuilder::class.java,
            ReadKeywordQuery::class.java, ReadKeywordQuery.Companion::class.java, ReadKeywordQueryBuilder::class.java,
            ReadVectorSearchRequest::class.java, ReadVectorSearchRequest.Companion::class.java,
            ReadVectorSearchRequestBuilder::class.java,
            ReadCriterion::class.java, ReadOrder::class.java,
            ScopedReads::class.java, ScopedVectorSearch::class.java,
        )
        val named: List<Type> = scoped.flatMap { type ->
            type.declaredMethods.flatMap { it.genericParameterTypes.toList() + it.genericReturnType } +
                type.declaredConstructors.flatMap { it.genericParameterTypes.toList() } +
                type.declaredFields.map { it.genericType }
        }
        assertTrue(named.size > scoped.size)
        assertTrue(named.none { it.typeName.contains("PropositionQuery") })
    }
}
