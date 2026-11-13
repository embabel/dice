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
package com.embabel.dice.evidence

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.ConflictType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvidenceTest {

    private val alice = EvidenceItem.of("Alice lives in Berlin.", Citation.of("prop-1", "ctx-main"), 0.9)

    private val aliceElsewhere = EvidenceItem.of("Alice lives in Paris.", Citation.of("prop-2", "ctx-main"), 0.8)

    private val searched = Searched.of("ctx-main").withQuery("where does Alice live")

    @Test
    fun `recorded evidence with no items is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Evidence.recorded(emptyList()) }
    }

    @Test
    fun `recorded evidence holds a read-only copy of its items`() {
        val source = mutableListOf(alice)
        val recorded = Evidence.recorded(source)
        source.add(aliceElsewhere)

        assertEquals(listOf(alice), recorded.items)
        assertThrows(UnsupportedOperationException::class.java) {
            (recorded.items as MutableList<EvidenceItem>).add(aliceElsewhere)
        }
    }

    @Test
    fun `derived evidence rejects a blank conclusion, a confidence outside 0 to 1, and missing or blank input ids`() {
        val rule = DerivationRule.ABSTRACTION
        val ids = listOf("prop-1")
        listOf<() -> Unit>(
            { Evidence.derived(" ", 0.5, rule, ids) },
            { Evidence.derived("Alice is a resident.", -0.1, rule, ids) },
            { Evidence.derived("Alice is a resident.", 1.1, rule, ids) },
            { Evidence.derived("Alice is a resident.", Double.NaN, rule, ids) },
            { Evidence.derived("Alice is a resident.", 0.5, rule, emptyList()) },
            { Evidence.derived("Alice is a resident.", 0.5, rule, listOf("prop-1", " ")) },
        ).forEach { call -> assertThrows(IllegalArgumentException::class.java) { call() } }
    }

    @Test
    fun `derived evidence starts with no inputs or citation and takes them through its with methods`() {
        val derived = Evidence.derived("Alice is a resident.", 0.7, DerivationRule.ABSTRACTION, listOf("prop-1", "prop-9"))
        assertTrue(derived.inputs.isEmpty())
        assertNull(derived.citation)

        val citation = Citation.of("prop-50", "ctx-main")
        val completed = derived.withInputs(listOf(alice)).withCitation(citation)

        assertEquals(listOf(alice), completed.inputs)
        assertEquals(citation, completed.citation)
        assertEquals(listOf("prop-1", "prop-9"), completed.inputIds)
    }

    @Test
    fun `derived evidence starts with its confidence as its effective confidence and no status`() {
        val derived = Evidence.derived("Alice is a resident.", 0.7, DerivationRule.ABSTRACTION, listOf("prop-1"))

        assertEquals(0.7, derived.effectiveConfidence)
        assertNull(derived.status)
    }

    @Test
    fun `an effective confidence outside 0 to 1 or NaN is rejected`() {
        val derived = Evidence.derived("Alice is a resident.", 0.7, DerivationRule.ABSTRACTION, listOf("prop-1"))
        listOf(-0.1, 1.1, Double.NaN).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { derived.withEffectiveConfidence(value) }
        }
    }

    @Test
    fun `each with method changes only its own part of derived evidence`() {
        val citation = Citation.of("prop-50", "ctx-main")
        val base = Evidence.derived("Alice is a resident.", 0.7, DerivationRule.ABSTRACTION, listOf("prop-1"))
            .withInputs(listOf(alice))
            .withCitation(citation)
            .withEffectiveConfidence(0.4)
            .withStatus(PropositionStatus.SUPERSEDED)

        val newCitation = Citation.of("prop-51", "ctx-main")
        val withInputs = base.withInputs(listOf(aliceElsewhere))
        assertEquals(listOf(aliceElsewhere), withInputs.inputs)
        assertEquals(citation, withInputs.citation)
        assertEquals(0.4, withInputs.effectiveConfidence)
        assertEquals(PropositionStatus.SUPERSEDED, withInputs.status)

        val withCitation = base.withCitation(newCitation)
        assertEquals(newCitation, withCitation.citation)
        assertEquals(listOf(alice), withCitation.inputs)
        assertEquals(0.4, withCitation.effectiveConfidence)
        assertEquals(PropositionStatus.SUPERSEDED, withCitation.status)

        val withEffective = base.withEffectiveConfidence(0.8)
        assertEquals(0.8, withEffective.effectiveConfidence)
        assertEquals(citation, withEffective.citation)
        assertEquals(listOf(alice), withEffective.inputs)
        assertEquals(PropositionStatus.SUPERSEDED, withEffective.status)

        val withStatus = base.withStatus(PropositionStatus.ACTIVE)
        assertEquals(PropositionStatus.ACTIVE, withStatus.status)
        assertEquals(citation, withStatus.citation)
        assertEquals(listOf(alice), withStatus.inputs)
        assertEquals(0.4, withStatus.effectiveConfidence)
    }

    @Test
    fun `derived evidence differing only in effective confidence or status is not equal`() {
        val ids = listOf("prop-1")
        val base = Evidence.derived("Alice is a resident.", 0.7, DerivationRule.ABSTRACTION, ids)
            .withEffectiveConfidence(0.4)
            .withStatus(PropositionStatus.SUPERSEDED)

        assertNotEquals(base, base.withEffectiveConfidence(0.5))
        assertNotEquals(base.withEffectiveConfidence(0.0), base.withEffectiveConfidence(-0.0))
        assertNotEquals(base, base.withStatus(PropositionStatus.ACTIVE))
    }

    @Test
    fun `derived evidence with every part equal is equal and shares a hash code`() {
        val rule = DerivationRule.of("prolog").withRuleId("resident")
        fun settled() = Evidence.derived("Alice is a resident.", 0.7, rule, listOf("prop-1"))
            .withInputs(listOf(alice))
            .withCitation(Citation.of("prop-50", "ctx-main"))
            .withEffectiveConfidence(0.4)
            .withStatus(PropositionStatus.SUPERSEDED)

        assertEquals(settled(), settled())
        assertEquals(settled().hashCode(), settled().hashCode())
    }

    @Test
    fun `conflicting evidence needs at least two items with different claim ids`() {
        val sameClaim = EvidenceItem.of("Alice lives in Rome.", Citation.of("prop-1", "ctx-other"), 0.4)
        assertThrows(IllegalArgumentException::class.java) {
            Evidence.conflicting(listOf(alice), ConflictType.Contradiction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Evidence.conflicting(listOf(alice, sameClaim), ConflictType.Contradiction)
        }

        val conflicting = Evidence.conflicting(listOf(alice, aliceElsewhere), ConflictType.WorldProgression)

        assertEquals(listOf(alice, aliceElsewhere), conflicting.items)
        assertEquals(ConflictType.WorldProgression, conflicting.conflictType)
    }

    @Test
    fun `of gives missing evidence for no items and recorded evidence otherwise`() {
        val missing = Evidence.of(emptyList(), searched)
        assertTrue(missing is Evidence.Missing)
        assertEquals(searched, (missing as Evidence.Missing).searched)

        val recorded = Evidence.of(listOf(alice), searched)
        assertTrue(recorded is Evidence.Recorded)
        assertEquals(listOf(alice), (recorded as Evidence.Recorded).items)
    }

    @Test
    fun `a when over the four kinds needs no else branch`() {
        val labels = listOf(
            Evidence.recorded(listOf(alice)),
            Evidence.derived("Alice is a resident.", 0.7, DerivationRule.ABSTRACTION, listOf("prop-1")),
            Evidence.missing(searched),
            Evidence.conflicting(listOf(alice, aliceElsewhere), ConflictType.Contradiction),
        ).map(::label)

        assertEquals(4, labels.toSet().size)
    }

    @Test
    fun `values of the same kind built from equal parts are equal`() {
        val rule = DerivationRule.of("prolog").withRuleId("resident").withParameter("maxHops", "3")
        val pairs = listOf(
            Evidence.recorded(listOf(alice)) to Evidence.recorded(listOf(alice)),
            Evidence.derived("Alice is a resident.", 0.7, rule, listOf("prop-1")).withInputs(listOf(alice)) to
                Evidence.derived("Alice is a resident.", 0.7, rule, listOf("prop-1")).withInputs(listOf(alice)),
            Evidence.missing(searched) to Evidence.missing(Searched.of("ctx-main").withQuery("where does Alice live")),
            Evidence.conflicting(listOf(alice, aliceElsewhere), ConflictType.Revision) to
                Evidence.conflicting(listOf(alice, aliceElsewhere), ConflictType.Revision),
        )
        pairs.forEach { (first, second) ->
            assertEquals(first, second)
            assertEquals(first.hashCode(), second.hashCode())
        }
    }

    @Test
    fun `recorded and conflicting evidence holding the same items are not equal`() {
        val items = listOf(alice, aliceElsewhere)
        val recorded: Any = Evidence.recorded(items)
        val conflicting: Any = Evidence.conflicting(items, ConflictType.Contradiction)

        assertNotEquals(recorded, conflicting)
    }

    @Test
    fun `derived evidence with confidence 0 and -0 are not equal`() {
        val ids = listOf("prop-1")
        assertNotEquals(
            Evidence.derived("Alice is a resident.", 0.0, DerivationRule.ABSTRACTION, ids),
            Evidence.derived("Alice is a resident.", -0.0, DerivationRule.ABSTRACTION, ids),
        )
    }

    @Test
    fun `a search rejects a blank context, matches the typed context, and can be unscoped`() {
        assertThrows(IllegalArgumentException::class.java) { Searched.of(" ") }
        assertEquals(Searched.of("a"), Searched.of(ContextId("a")))
        assertTrue(Searched.unscoped().contextIds.isEmpty())
    }

    @Test
    fun `contexts keep the order they were first added in`() {
        val values = Searched.of("a").withContext("b").withContext("a").contextIdValues

        assertEquals(listOf("a", "b"), values.toList())
    }

    @Test
    fun `a search rejects a blank query and keeps filters in order, replacing a repeated name`() {
        assertThrows(IllegalArgumentException::class.java) { Searched.unscoped().withQuery(" ") }

        val filtered = Searched.unscoped()
            .withFilter("minConfidence", "0.5")
            .withFilter("status", "ACTIVE")
            .withFilter("minConfidence", "0.7")

        assertEquals(listOf("minConfidence" to "0.7", "status" to "ACTIVE"), filtered.filters.toList())
        assertThrows(IllegalArgumentException::class.java) { filtered.withFilter(" ", "x") }
        assertEquals("", filtered.withFilter("note", "").filters["note"])
        assertThrows(UnsupportedOperationException::class.java) {
            (filtered.filters as MutableMap<String, String>).put("x", "y")
        }
    }

    @Test
    fun `a derivation rule trims its method and rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) { DerivationRule.of(" ") }
        assertEquals("prolog", DerivationRule.of(" prolog ").method)
        assertThrows(IllegalArgumentException::class.java) { DerivationRule.of("prolog").withRuleId(" ") }
        assertThrows(IllegalArgumentException::class.java) { DerivationRule.of("prolog").withExpression(" ") }
        assertEquals(DerivationRule.ABSTRACTION, DerivationRule.of("abstraction"))
    }

    @Test
    fun `a derivation rule method is lower-cased, so its case does not change equality`() {
        assertEquals(DerivationRule.ABSTRACTION, DerivationRule.of("Abstraction"))
        assertEquals("prolog", DerivationRule.of(" Prolog ").method)
    }

    @Test
    fun `derivation rules with the same parameters added in a different order are equal`() {
        val first = DerivationRule.of("prolog").withParameter("maxHops", "3").withParameter("depth", "2")
        val second = DerivationRule.of("prolog").withParameter("depth", "2").withParameter("maxHops", "3")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `searches over the same contexts added in a different order are equal`() {
        assertEquals(Searched.of("a").withContext("b"), Searched.of("b").withContext("a"))
    }

    @Test
    fun `search text leaves out the query, so a missing result does not print the question`() {
        val search = Searched.of("ctx-main").withQuery("where does Alice live")
        val missing = Evidence.missing(search).toString()

        assertFalse(search.toString().contains("where does Alice live"))
        assertTrue(search.toString().contains("hasQuery=true"))
        assertFalse(missing.contains("where does Alice live"))
        assertTrue(missing.contains("hasQuery=true"))
        assertTrue(Searched.unscoped().toString().contains("hasQuery=false"))
    }

    @Test
    fun `a derivation rule keeps parameters in order, replacing a repeated name`() {
        val rule = DerivationRule.of("prolog")
            .withParameter("maxHops", "3")
            .withParameter("depth", "2")
            .withParameter("maxHops", "5")

        assertEquals(listOf("maxHops" to "5", "depth" to "2"), rule.parameters.toList())
        assertThrows(IllegalArgumentException::class.java) { rule.withParameter(" ", "x") }
        assertEquals("", rule.withParameter("note", "").parameters["note"])
        assertThrows(UnsupportedOperationException::class.java) {
            (rule.parameters as MutableMap<String, String>).put("x", "y")
        }
    }

    @Test
    fun `a provider lambda returns missing evidence that records the query`() {
        val provider = EvidenceProvider<String> { query -> Evidence.missing(Searched.of("ctx").withQuery(query)) }

        val evidence = provider.evidence("who is Ada")

        assertTrue(evidence is Evidence.Missing)
        assertEquals("who is Ada", (evidence as Evidence.Missing).searched.query)
    }

    /**
     * Names the kind of an evidence value with a `when` that has no `else` branch.
     *
     * @param evidence the value to name
     * @return a different label for each of the four kinds
     */
    private fun label(evidence: Evidence): String = when (evidence) {
        is Evidence.Recorded -> "recorded"
        is Evidence.Derived -> "derived"
        is Evidence.Missing -> "missing"
        is Evidence.Conflicting -> "conflicting"
    }
}
