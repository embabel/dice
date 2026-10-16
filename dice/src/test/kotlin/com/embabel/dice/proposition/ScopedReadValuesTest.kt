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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant

class ScopedReadValuesTest {

    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val instant = Instant.parse("2026-01-01T00:00:00Z")
    private val a = PropositionKey("A", "same-id")
    private val b = PropositionKey("B", "same-id")
    private val c = PropositionKey("C", "same-id")

    private fun spec() = ScopedReadSpec.of(scope)

    @Test
    fun `finite scope rejects empty and blank IDs`() {
        for (ids in listOf(emptyList(), listOf(""), listOf("A", " "), listOf("\t\n"))) {
            assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(ids) }
        }
    }

    @Test
    fun `scope copies deduplicates and preserves significant whitespace`() {
        val ids = mutableListOf("A", "B", "A", " A ")
        val captured = ContextReadScope.ofValues(ids)
        ids.clear()
        ids.add("C")
        assertEquals(setOf("A", "B", " A "), captured.contextIdValues)
        assertThrows(UnsupportedOperationException::class.java) {
            (captured.contextIdValues as MutableSet<String>).clear()
        }
        assertEquals(setOf("A", "B"), scope.contextIdValues)
    }

    @Test
    fun `scopes over the same contexts are equal whatever their order`() {
        val forward = ContextReadScope.ofValues(listOf("A", "B"))
        val reversed = ContextReadScope.ofValues(listOf("B", "A", "A"))
        assertEquals(forward, reversed)
        assertEquals(forward.hashCode(), reversed.hashCode())
        assertEquals(ContextReadScope.ofValues(listOf("A")), ContextReadScope.of(listOf(ContextId("A"))))
        assertNotEquals(ContextReadScope.ofValues(listOf("A")), ContextReadScope.ofValues(listOf("A ")))
    }

    @Test
    fun `E1 restrict keeps every membership field of the criteria`() {
        val any = mutableListOf("any")
        val query = PropositionQuery(
            contextId = ContextId("A"), entityId = "entity", anyEntityIds = any,
            allEntityIds = listOf("all"), statuses = setOf(PropositionStatus.ACTIVE),
            minLevel = 1, maxLevel = 4,
            createdAfter = instant.minusSeconds(6), createdBefore = instant.minusSeconds(5),
            revisedAfter = instant.minusSeconds(4), revisedBefore = instant.minusSeconds(3),
            accessedAfter = instant.minusSeconds(2), accessedBefore = instant.minusSeconds(1),
            minConfidence = 0.61, minEffectiveConfidence = 0.27,
            decayK = 7.0, minImportance = 0.39, minReinforceCount = 5, minTrustScore = 0.43,
            pinned = false, belowEffectiveConfidence = 0.89,
        )
        val restricted = spec().restrict(query)
        val fragment = restricted.fragments.single()
        assertEquals(query, fragment)
        assertNotSame(query, fragment)
        assertEquals(scope, restricted.scope)
        any.add("outsider")
        assertEquals(listOf("any"), fragment.anyEntityIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (restricted.fragments as MutableList<PropositionQuery>).clear()
        }
    }

    @Test
    fun `E1 field inventory changes only with a conscious decision`() {
        // A new query field must be populated in the test above before this inventory changes.
        val fields = PropositionQuery::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
        assertEquals(setOf(
            "contextId", "entityId", "anyEntityIds", "allEntityIds", "statuses", "minLevel", "maxLevel",
            "createdAfter", "createdBefore", "revisedAfter", "revisedBefore", "accessedAfter", "accessedBefore",
            "minConfidence", "minEffectiveConfidence", "effectiveConfidenceAsOf", "decayK", "minImportance",
            "minReinforceCount", "minTrustScore", "pinned", "orderBy", "limit", "belowEffectiveConfidence",
        ), fields)
    }

    @Test
    fun `E2 restrict rejects order limit instant and an outside context`() {
        for (criteria in listOf(
            PropositionQuery(orderBy = PropositionQuery.OrderBy.CREATED_DESC),
            PropositionQuery(limit = 2),
            PropositionQuery(effectiveConfidenceAsOf = instant),
            PropositionQuery(contextId = ContextId("C")),
        )) {
            assertThrows(IllegalArgumentException::class.java) { spec().restrict(criteria) }
        }
    }

    @Test
    fun `E3 criteria accumulate and keep their own decay rate`() {
        val first = spec().restrict(PropositionQuery(contextId = ContextId("A"), minEffectiveConfidence = 0.8, decayK = 9.0))
        val second = first.restrict(PropositionQuery(contextId = ContextId("B"), belowEffectiveConfidence = 0.3, decayK = 4.0))
        val third = second.restrict(PropositionQuery())
        assertEquals(1, first.fragments.size)
        assertEquals(first.fragments + second.fragments.last(), third.fragments.dropLast(1))
        assertEquals(listOf("A", "B", null), third.fragments.map { it.contextId?.value })
        assertEquals(listOf(9.0, 4.0, 2.0), third.fragments.map { it.decayK })
    }

    @Test
    fun `E4 grants intersect and an empty grant denies every row for good`() {
        val initial = spec()
        assertNull(initial.includedKeys)
        val granted = initial.includingOnly(listOf(a, b))
        assertEquals(setOf(a, b), granted.includedKeys)
        val narrowed = granted.includingOnly(listOf(b))
        assertEquals(setOf(b), narrowed.includedKeys)
        assertEquals(setOf(b), narrowed.includingOnly(listOf(a, b)).includedKeys)
        assertEquals(emptySet<PropositionKey>(), narrowed.includingOnly(listOf(a)).includedKeys)
        val denied = initial.includingOnly(emptyList())
        assertEquals(emptySet<PropositionKey>(), denied.includedKeys)
        assertEquals(emptySet<PropositionKey>(), denied.includingOnly(listOf(a, b)).includedKeys)
    }

    @Test
    fun `E5 exclusions accumulate and leave fragments and grants alone`() {
        val original = spec().restrict(PropositionQuery(entityId = "alice")).includingOnly(listOf(a, b))
        val excluded = original.excluding(listOf(a)).excluding(listOf(b)).excluding(emptyList())
        assertEquals(setOf(a, b), excluded.excludedKeys)
        assertEquals(original.fragments, excluded.fragments)
        assertEquals(original.includedKeys, excluded.includedKeys)
        assertTrue(original.excludedKeys.isEmpty())
    }

    @Test
    fun `E6 grant and exclusion keys outside the scope are rejected`() {
        val denied = spec().includingOnly(emptyList())
        assertThrows(IllegalArgumentException::class.java) { denied.includingOnly(listOf(a, c)) }
        assertThrows(IllegalArgumentException::class.java) { denied.excluding(listOf(a, c)) }
        assertEquals(emptySet<PropositionKey>(), denied.includedKeys)
        assertTrue(denied.excludedKeys.isEmpty())
    }

    @Test
    fun `E7 read clock rejects a decay rate that is not finite or is negative`() {
        for (k in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) { ReadClock(instant, k) }
        }
        assertEquals(0.0, ReadClock(instant, 0.0).rankingDecayK)
    }
}
