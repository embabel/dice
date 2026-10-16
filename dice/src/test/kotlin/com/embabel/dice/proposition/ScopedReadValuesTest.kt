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

    private fun spec() = ScopedReadSpec.at(instant, 3.0)

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
    fun `string scope factory is callable as Java static method`() {
        val method = ContextReadScope::class.java.getMethod("ofValues", Collection::class.java)
        assertTrue(Modifier.isStatic(method.modifiers))
        val value = method.invoke(null, listOf("A")) as ContextReadScope
        assertEquals(setOf("A"), value.contextIdValues)
    }

    @Test
    fun `null context leaves scope intact and allowed singleton narrows`() {
        val initial = spec()
        val unrestricted = initial.restrict(scope, PropositionQuery())
        val narrowed = unrestricted.restrict(scope, PropositionQuery(contextId = ContextId("A")))
        assertNull(unrestricted.fragments.single().contextId)
        assertEquals(ContextId("A"), narrowed.fragments.last().contextId)
        assertEquals(setOf("A", "B"), scope.contextIdValues)
        assertTrue(initial.fragments.isEmpty())
        assertEquals(instant, narrowed.asOf)
        assertEquals(3.0, narrowed.rankingDecayK)
    }

    @Test
    fun `outsider query rejects before backend invocation`() {
        var calls = 0
        fun read(criteria: PropositionQuery) {
            spec().restrict(scope, criteria)
            calls++
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(PropositionQuery(contextId = ContextId("C")))
        }
        assertEquals(0, calls)
    }

    @Test
    fun `conflicting explicit confidence instant rejects`() {
        assertThrows(IllegalArgumentException::class.java) {
            spec().restrict(scope, PropositionQuery(effectiveConfidenceAsOf = instant.plusSeconds(1)))
        }
        val accepted = spec().restrict(scope, PropositionQuery(effectiveConfidenceAsOf = instant))
        assertEquals(instant, accepted.fragments.single().effectiveConfidenceAsOf)
        assertEquals(instant, spec().restrict(scope, PropositionQuery()).fragments.single().effectiveConfidenceAsOf)
    }

    @Test
    fun `snapshot explicitly preserves every membership field and strips presentation`() {
        val query = PropositionQuery(
            contextId = ContextId("A"), entityId = "entity", anyEntityIds = listOf("any"),
            allEntityIds = listOf("all"), statuses = setOf(PropositionStatus.ACTIVE),
            minLevel = 1, maxLevel = 4,
            createdAfter = instant.minusSeconds(6), createdBefore = instant.minusSeconds(5),
            revisedAfter = instant.minusSeconds(4), revisedBefore = instant.minusSeconds(3),
            accessedAfter = instant.minusSeconds(2), accessedBefore = instant.minusSeconds(1),
            minConfidence = 0.61, minEffectiveConfidence = 0.27, effectiveConfidenceAsOf = instant,
            decayK = 7.0, minImportance = 0.39, minReinforceCount = 5, minTrustScore = 0.43,
            pinned = false, orderBy = PropositionQuery.OrderBy.CREATED_DESC, limit = 2,
            belowEffectiveConfidence = 0.89,
        )
        val fragment = spec().restrict(scope, query).fragments.single()
        assertEquals(query.copy(orderBy = PropositionQuery.OrderBy.NONE, limit = null), fragment)
        assertNotSame(query, fragment)
        // A new field must be consciously mapped and exercised above before this inventory changes.
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
    fun `snapshots copy every nested collection and prohibit mutation through getters`() {
        val any = mutableListOf("any")
        val all = mutableListOf("all")
        val statuses = mutableSetOf(PropositionStatus.ACTIVE)
        val value = spec().restrict(scope, PropositionQuery(anyEntityIds = any, allEntityIds = all, statuses = statuses))
        any.clear()
        all.add("outsider")
        statuses.clear()
        val captured = value.fragments.single()
        assertEquals(listOf("any"), captured.anyEntityIds)
        assertEquals(listOf("all"), captured.allEntityIds)
        assertEquals(setOf(PropositionStatus.ACTIVE), captured.statuses)
        assertThrows(UnsupportedOperationException::class.java) { (captured.anyEntityIds as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (captured.allEntityIds as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (captured.statuses as MutableSet<PropositionStatus>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (value.fragments as MutableList<PropositionQuery>).clear() }
    }

    @Test
    fun `empty any and all entity predicates retain their distinct semantics`() {
        val any = spec().restrict(scope, PropositionQuery(anyEntityIds = emptyList())).fragments.single()
        val all = spec().restrict(scope, PropositionQuery(allEntityIds = emptyList())).fragments.single()
        assertEquals(emptyList<String>(), any.anyEntityIds)
        assertNull(any.allEntityIds)
        assertEquals(emptyList<String>(), all.allEntityIds)
        assertNull(all.anyEntityIds)
        assertFalse(any.anyEntityIds!!.any { it == "entity" })
        assertTrue(all.allEntityIds!!.all { it == "entity" })
    }

    @Test
    fun `clearing or replacement criteria retain prior conjunctions and each decay constant`() {
        val original = spec().restrict(scope, PropositionQuery(
            contextId = ContextId("A"), entityId = "alice", minLevel = 5, minEffectiveConfidence = 0.8, decayK = 9.0,
        ))
        val replaced = original.restrict(scope, PropositionQuery(
            contextId = ContextId("B"), entityId = "bob", maxLevel = 2, belowEffectiveConfidence = 0.3, decayK = 4.0,
        ))
        val cleared = replaced.restrict(scope, PropositionQuery())
        assertEquals(3, cleared.fragments.size)
        assertEquals(original.fragments.single(), cleared.fragments[0])
        assertEquals(replaced.fragments[1], cleared.fragments[1])
        assertEquals(listOf("A", "B", null), cleared.fragments.map { it.contextId?.value })
        assertEquals(listOf("alice", "bob", null), cleared.fragments.map { it.entityId })
        assertEquals(5, cleared.fragments[0].minLevel)
        assertEquals(2, cleared.fragments[1].maxLevel)
        assertEquals(listOf(9.0, 4.0, 2.0), cleared.fragments.map { it.decayK })
        assertEquals(1, original.fragments.size)
    }

    @Test
    fun `row grants start unrestricted intersect and never reset deny all`() {
        val initial = spec()
        assertNull(initial.includedKeys)
        val granted = initial.includingOnly(scope, listOf(a, b))
        val narrowed = granted.includingOnly(scope, listOf(b))
        assertEquals(setOf(a, b), granted.includedKeys)
        assertEquals(setOf(b), narrowed.includedKeys)
        assertEquals(emptySet<PropositionKey>(), narrowed.includingOnly(scope, listOf(a)).includedKeys)
        val denied = initial.includingOnly(scope, emptyList())
        assertEquals(emptySet<PropositionKey>(), denied.includedKeys)
        assertEquals(emptySet<PropositionKey>(), denied.includingOnly(scope, listOf(a, b)).includedKeys)
    }

    @Test
    fun `composite keys distinguish equal proposition IDs across contexts`() {
        assertNotEquals(a, b)
        assertEquals(a, PropositionKey("A", "same-id"))
        assertEquals(2, setOf(a, b).size)
        assertEquals(setOf(a), spec().includingOnly(scope, listOf(a, b)).includingOnly(scope, listOf(a)).includedKeys)
    }

    @Test
    fun `grants and exclusions snapshot collections and remain immutable`() {
        val grants = mutableListOf(a, b)
        val exclusions = mutableListOf(a)
        val value = spec().includingOnly(scope, grants).excluding(scope, exclusions)
        grants.clear()
        exclusions.add(b)
        assertEquals(setOf(a, b), value.includedKeys)
        assertEquals(setOf(a), value.excludedKeys)
        assertThrows(UnsupportedOperationException::class.java) { (value.includedKeys as MutableSet<PropositionKey>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (value.excludedKeys as MutableSet<PropositionKey>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (spec().excludedKeys as MutableSet<PropositionKey>).add(a) }
    }

    @Test
    fun `exclusions accumulate without changing fragments or grants`() {
        val original = spec().restrict(scope, PropositionQuery(entityId = "alice")).includingOnly(scope, listOf(a, b))
        val excluded = original.excluding(scope, listOf(a)).excluding(scope, listOf(b)).excluding(scope, emptyList())
        assertEquals(setOf(a, b), excluded.excludedKeys)
        assertEquals(original.fragments, excluded.fragments)
        assertEquals(original.includedKeys, excluded.includedKeys)
        assertTrue(original.excludedKeys.isEmpty())
        val restricted = excluded.restrict(scope, PropositionQuery()).includingOnly(scope, listOf(b))
        assertEquals(setOf(a, b), restricted.excludedKeys)
        assertEquals(2, restricted.fragments.size)
    }

    @Test
    fun `outsider grant and exclusion keys reject even with empty existing grants`() {
        val denied = spec().includingOnly(scope, emptyList())
        assertThrows(IllegalArgumentException::class.java) { denied.includingOnly(scope, listOf(a, c)) }
        assertThrows(IllegalArgumentException::class.java) { denied.excluding(scope, listOf(a, c)) }
        assertEquals(emptySet<PropositionKey>(), denied.includedKeys)
        assertTrue(denied.excludedKeys.isEmpty())
    }
}
