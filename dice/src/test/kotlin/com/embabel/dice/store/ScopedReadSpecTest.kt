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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.function.UnaryOperator

/**
 * Tests for [ScopedReadSpec].
 *
 * Contract:
 * E1 A new spec has the scope it was given and no filter.
 * E2 narrow keeps the filter whole, copies its collections and keeps the scope.
 * E3 narrow adds to the filters a spec has, and the function form adds the same filter as the query form.
 * E4 A filter or a query that names a context outside the scope throws ReadScopeViolationException with the allowed contexts.
 * E5 A filter that carries an ordering, a limit or effectiveConfidenceAsOf throws IllegalArgumentException.
 * E6 requireAllowed passes a query with no context or a context in the scope, ordering and limit included.
 * E7 A spec narrowed to one context refuses a filter or a query for another context of the scope.
 * E8 criteria names no context and sets no criteria.
 * E9 PropositionQuery has the fields the scope check was written against.
 * E10 VectorSearchRequest has the fields the scope check was written against.
 */
class ScopedReadSpecTest {

    private val scope = ContextReadScope.ofValues("A", "B")

    @Test
    fun `E1 a new spec has the scope it was given and no filter`() {
        val spec = ScopedReadSpec.of(scope)
        assertSame(scope, spec.scope)
        assertTrue(spec.filters.isEmpty())
    }

    @Test
    fun `E2 narrow keeps the filter whole and copies its collections`() {
        val entities = mutableListOf("e1")
        val filter = PropositionQuery(
            contextId = ContextId("A"), anyEntityIds = entities, statuses = setOf(PropositionStatus.ACTIVE),
            minConfidence = 0.6, minEffectiveConfidence = 0.3, decayK = 7.0, pinned = false,
        )
        val spec = ScopedReadSpec.of(scope).narrow(filter)
        entities.add("outsider")
        assertEquals(filter.copy(anyEntityIds = listOf("e1")), spec.filters.single())
        assertEquals(scope, spec.scope)
        assertThrows(UnsupportedOperationException::class.java) {
            (spec.filters as MutableList<PropositionQuery>).clear()
        }
    }

    @Test
    fun `E3 narrow adds to the filters a spec has`() {
        val first = ScopedReadSpec.of(scope).narrow(PropositionQuery(minConfidence = 0.8))
        val second = first.narrow(PropositionQuery(contextId = ContextId("B"), decayK = 4.0))
        assertEquals(1, first.filters.size)
        assertEquals(first.filters, second.filters.dropLast(1))
        assertEquals(listOf(null, "B"), second.filters.map { it.contextId?.value })
        assertEquals(scope, second.scope)
        val byFunction = ScopedReadSpec.of(scope).narrow(UnaryOperator<PropositionQuery> { it.withMinConfidence(0.8) })
        assertEquals(first, byFunction)
    }

    @Test
    fun `E4 a filter or a query that names a context outside the scope is refused`() {
        val spec = ScopedReadSpec.of(scope).narrow(PropositionQuery(minConfidence = 0.8))
        val refusal = assertThrows(ReadScopeViolationException::class.java) {
            spec.narrow(PropositionQuery.againstContext("C"))
        }
        assertEquals("C", refusal.contextIdValue)
        assertEquals(setOf("A", "B"), refusal.allowedContextIdValues)
        assertThrows(ReadScopeViolationException::class.java) {
            spec.requireAllowed(PropositionQuery.againstContext("a"))
        }
    }

    @Test
    fun `E5 a filter that carries an ordering, a limit or an instant is rejected`() {
        val spec = ScopedReadSpec.of(scope)
        val asOf = Instant.parse("2026-01-01T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { spec.narrow(PropositionQuery().orderedByCreated()) }
        assertThrows(IllegalArgumentException::class.java) { spec.narrow(PropositionQuery().withLimit(5)) }
        assertThrows(IllegalArgumentException::class.java) {
            spec.narrow(PropositionQuery(minEffectiveConfidence = 0.3, effectiveConfidenceAsOf = asOf))
        }
    }

    @Test
    fun `E6 requireAllowed passes a query in the scope with its ordering and limit`() {
        val spec = ScopedReadSpec.of(scope)
        spec.requireAllowed(PropositionQuery.againstContext("B").orderedByCreated().withLimit(5))
        spec.requireAllowed(PropositionQuery())
        assertTrue(spec.filters.isEmpty())
    }

    @Test
    fun `E7 a spec narrowed to one context refuses another context of the scope`() {
        val onlyA = ScopedReadSpec.of(scope).narrow(PropositionQuery.againstContext("A"))
        onlyA.requireAllowed(PropositionQuery.againstContext("A"))
        assertEquals(2, onlyA.narrow(PropositionQuery.againstContext("A")).filters.size)
        val refusal = assertThrows(ReadScopeViolationException::class.java) {
            onlyA.requireAllowed(PropositionQuery.againstContext("B"))
        }
        assertEquals("B", refusal.contextIdValue)
        assertEquals(setOf("A"), refusal.allowedContextIdValues)
        assertThrows(ReadScopeViolationException::class.java) {
            onlyA.narrow(PropositionQuery.againstContext("B"))
        }
    }

    @Test
    fun `E8 criteria names no context and sets no criteria`() {
        assertEquals(PropositionQuery(), ScopedReadSpec.criteria())
    }

    @Test
    fun `E9 a new query field turns this test red until the scope check covers it`() {
        // requireAllowed reads contextId, the only field here that names a context. A new field
        // that names one must be checked there before it is added to this list.
        val fields = PropositionQuery::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "contextId", "entityId", "anyEntityIds", "allEntityIds", "statuses", "minLevel", "maxLevel",
                "createdAfter", "createdBefore", "revisedAfter", "revisedBefore", "accessedAfter", "accessedBefore",
                "minConfidence", "minEffectiveConfidence", "effectiveConfidenceAsOf", "decayK", "minImportance",
                "minReinforceCount", "minTrustScore", "pinned", "orderBy", "limit", "belowEffectiveConfidence",
            ),
            fields,
        )
    }

    @Test
    fun `E10 a new request field turns this test red until the scope check covers it`() {
        // A scoped similarity search checks the request's filter, the only field here that can
        // name a context.
        val fields = VectorSearchRequest::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(setOf("text", "filter", "topK", "minScore"), fields)
    }
}
