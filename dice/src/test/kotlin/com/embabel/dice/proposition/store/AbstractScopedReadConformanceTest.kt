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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionQuery.OrderBy
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Arrays

/**
 * The scoped read rules every store has to agree on, written once. Each store gets a subclass that
 * hands back a store holding the given rows, so the in-memory store and the graph stores are held to
 * the same expectations and cannot quietly drift apart.
 *
 * Fixture texts are all different, because some stores merge propositions with the same text in one
 * context.
 */
abstract class AbstractScopedReadConformanceTest {

    /** A store holding exactly [rows] and nothing else. */
    protected abstract fun store(rows: List<Proposition>): ContextSetReadCapable

    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val old = asOf.minusSeconds(10 * 86400L)
    private val clock = ReadClock(asOf, 0.7)

    // Worked out here from the code points themselves, so the suite never borrows the rule it checks.
    private val codePointOrder = Comparator<String> { a, b -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()) }
    private val keyOrder = compareBy(codePointOrder) { p: Proposition -> p.contextId.value }.thenBy(codePointOrder) { it.id }

    private fun row(id: String, context: String, confidence: Double = 0.8, text: String = "alpha $id", level: Int = 1) =
        Proposition(
            id = id, contextId = ContextId(context), text = text, mentions = emptyList(),
            confidence = confidence, decay = 0.1, level = level,
            sourceIds = if (level > 0) listOf("source") else emptyList(), importance = 0.5, reinforceCount = 1,
            created = old, contentRevised = old, metadataRevised = old, lastAccessed = old,
        )

    private fun ids(rows: List<Proposition>) = rows.map { it.id }

    @Test
    fun `ties on every order break by context then id in code point order`() {
        // U+FF61 sorts before U+10000 by code point, but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        val tied = listOf(
            row("x𐀀", "｡"), row("x｡", "｡"), row("c1", "𐀀"), row("c2", "ctx-a"), row("c3", "ctx-a"),
        )
        // Higher on every order, so it leads every ranked read and shows the tie break sits below the rank.
        val leader = row("leader", "𐀀", confidence = 0.95).copy(
            created = asOf, contentRevised = asOf, metadataRevised = asOf, lastAccessed = asOf,
            importance = 0.9, reinforceCount = 5,
        )
        val reads = store(tied + leader)
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("𐀀", "ctx-a", "｡")))
        val byKey = ids(tied.sortedWith(keyOrder))
        // The premise: plain UTF-16 order disagrees, or this test would prove nothing.
        assertNotEquals(byKey, tied.sortedWith(compareBy<Proposition> { it.contextId.value }.thenBy { it.id }).map { it.id })
        assertEquals(listOf("c2", "c3", "x｡", "x𐀀", "c1"), byKey)

        for (order in OrderBy.entries) {
            val expected = if (order == OrderBy.NONE) ids((tied + leader).sortedWith(keyOrder)) else listOf("leader") + byKey
            for (limit in listOf(1, 2, 4, 10)) {
                assertEquals(expected.take(limit), ids(reads.query(spec, clock, order, limit)), "$order limit=$limit")
            }
        }
        for (limit in listOf(1, 3, 10)) {
            assertEquals((listOf("leader") + byKey).take(limit), ids(reads.keywordOverlap(spec, clock, listOf("alpha"), limit)), "limit=$limit")
        }
        assertEquals(6L, reads.count(spec, clock))

        val granted = spec.includingOnly(listOf(PropositionKey("𐀀", "c1"), PropositionKey("｡", "x𐀀"), PropositionKey("｡", "x｡")))
        assertEquals(listOf("x｡", "x𐀀", "c1"), ids(reads.query(granted, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 10)))
        assertEquals(listOf("x｡", "x𐀀"), ids(reads.keywordOverlap(granted, clock, listOf("alpha"), 2)))
    }

    @Test
    fun `filters and grants apply before the limit and the count`() {
        // The decoys outrank every eligible row, so a store that filtered after its limit would return them.
        val rows = listOf(
            row("a-top", "A", 0.6), row("b-mid", "B", 0.5), row("a-low", "A", 0.4),
            row("c-out", "C", 0.99), row("a-excluded", "A", 0.98), row("b-level", "B", 0.97, level = 0),
            row("b-ungranted", "B", 0.96),
        )
        val reads = store(rows)
        val filtered = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A", "B")))
            .restrict(PropositionQuery(minLevel = 1))
            .excluding(listOf(PropositionKey("A", "a-excluded")))
        assertEquals(listOf("b-ungranted", "a-top"), ids(reads.query(filtered, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 2)))
        assertEquals(listOf("b-ungranted", "a-top"), ids(reads.keywordOverlap(filtered, clock, listOf("alpha"), 2)))
        assertEquals(4L, reads.count(filtered, clock))

        // Granting decoys does not let them in; the grant only narrows what the filters already allow.
        val granted = filtered.includingOnly(
            rows.filter { it.contextId.value != "C" && it.id != "b-ungranted" }.map { PropositionKey(it.contextId.value, it.id) },
        )
        assertEquals(listOf("a-top", "b-mid"), ids(reads.query(granted, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 2)))
        assertEquals(listOf("a-low", "a-top"), ids(reads.query(granted, clock, OrderBy.NONE, 2)))
        assertEquals(listOf("a-top", "b-mid"), ids(reads.keywordOverlap(granted, clock, listOf("alpha"), 2)))
        assertEquals(3L, reads.count(granted, clock))

        val nothing = granted.includingOnly(emptyList())
        assertEquals(0L, reads.count(nothing, clock))
        assertTrue(reads.query(nothing, clock, OrderBy.NONE, 10).isEmpty())
        assertTrue(reads.keywordOverlap(nothing, clock, listOf("alpha"), 10).isEmpty())
    }

    @Test
    fun `blank padded upper-case and repeated keyword tokens read like their normalised form`() {
        val rows = listOf(
            row("both", "A", 0.5, text = "Alpha and BETA both"),
            row("beta", "A", 0.9, text = "beta only here"),
            row("alpha", "A", 0.8, text = "ALPHA only here"),
            row("none", "A", 1.0, text = "nothing relevant"),
        )
        val reads = store(rows)
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A")))
        val normalised = listOf("alpha", "beta")
        // Counting the repeats would give alpha three hits and put it above beta.
        val raw = listOf(" ALPHA ", "alpha", "ALPHA", "beta", "", "   ")
        val expected = listOf("both", "beta", "alpha")
        for (limit in listOf(2, 10)) {
            assertEquals(expected.take(limit), ids(reads.keywordOverlap(spec, clock, normalised, limit)), "normalised limit=$limit")
            assertEquals(expected.take(limit), ids(reads.keywordOverlap(spec, clock, raw, limit)), "raw limit=$limit")
        }
        assertEquals(ids(reads.keywordOverlap(spec, clock, listOf("alpha"), 10)), ids(reads.keywordOverlap(spec, clock, listOf("  ALPHA\t"), 10)))
        assertEquals(listOf("alpha", "both"), ids(reads.keywordOverlap(spec, clock, listOf("ALPHA", "Alpha"), 10)))
    }

    @Test
    fun `a zero limit and empty or blank tokens read nothing`() {
        val reads = store(listOf(row("a", "A"), row("b", "A", 0.9)))
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A")))
        // The premise: the same spec reads rows when asked for some.
        assertEquals(listOf("b", "a"), ids(reads.query(spec, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 10)))
        assertEquals(listOf("b", "a"), ids(reads.keywordOverlap(spec, clock, listOf("alpha"), 10)))
        for (order in OrderBy.entries) {
            assertTrue(reads.query(spec, clock, order, 0).isEmpty(), order.name)
        }
        assertTrue(reads.keywordOverlap(spec, clock, listOf("alpha"), 0).isEmpty())
        assertTrue(reads.keywordOverlap(spec, clock, emptyList(), 10).isEmpty())
        assertTrue(reads.keywordOverlap(spec, clock, listOf("", "   "), 10).isEmpty())
        assertEquals(2L, reads.count(spec, clock))
    }
}
