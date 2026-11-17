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

import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.EnumSet

/**
 * Tests for [ReadCriteria] and [ReadCriterion].
 *
 * Contract:
 * E1 Empty criteria set nothing and use the decay rate a query uses.
 * E2 A with step sets one criterion, keeps the others and copies its collection.
 * E3 An empty collection, a blank id, a threshold that is not a finite number and a negative decay rate are rejected.
 * E4 The block and the chain give the same criteria, and the vararg and window steps equal the steps they stand for.
 * E5 The criteria carry every query field except the context, the ordering and the limit, name no context, and have one ReadCriterion each.
 * E6 Every criterion has a step that sets exactly that criterion, and no two steps give equal criteria.
 * E7 requireOnly passes when every set criterion is supported and names the first that is not.
 * E8 Negative zero and zero give equal criteria.
 */
class ReadCriteriaTest {

    private val instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `E1 empty criteria set nothing and use the decay rate a query uses`() {
        val empty = ReadCriteria.empty()
        assertNull(empty.entityId)
        assertNull(empty.statuses)
        assertNull(empty.minConfidence)
        assertNull(empty.effectiveConfidenceAsOf)
        assertEquals(PropositionQuery().decayK, empty.decayK)
        assertTrue(empty.specified.isEmpty())
        assertEquals(ReadCriteria.empty(), empty)
        assertEquals("ReadCriteria()", empty.toString())
    }

    @Test
    fun `E2 a with step sets one criterion, keeps the others and copies its collection`() {
        val ids = mutableListOf("e1")
        val criteria = ReadCriteria.empty()
            .withMinConfidence(0.6)
            .withAnyEntityIds(ids)
            .withStatus(PropositionStatus.ACTIVE)
        ids.add("outsider")
        assertEquals(listOf("e1"), criteria.anyEntityIds)
        assertEquals(setOf(PropositionStatus.ACTIVE), criteria.statuses)
        assertTrue(criteria.minConfidence == 0.6)
        assertTrue(criteria.withMinConfidence(0.9).minConfidence == 0.9)
        assertEquals(listOf("e1"), criteria.withMinConfidence(0.9).anyEntityIds)
        assertNotEquals(criteria, criteria.withMinConfidence(0.9))
        assertEquals(
            EnumSet.of(ReadCriterion.ANY_ENTITY_IDS, ReadCriterion.STATUSES, ReadCriterion.MIN_CONFIDENCE),
            criteria.specified,
        )
        assertEquals("ReadCriteria(ANY_ENTITY_IDS=[e1], STATUSES=[ACTIVE], MIN_CONFIDENCE=0.6)", criteria.toString())
        assertThrows(UnsupportedOperationException::class.java) {
            (criteria.anyEntityIds as MutableList<String>).add("e2")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (criteria.specified as MutableSet<ReadCriterion>).clear()
        }
    }

    @Test
    fun `E3 a value that selects nothing definite is rejected`() {
        val empty = ReadCriteria.empty()
        assertThrows(IllegalArgumentException::class.java) { empty.withEntityId(" ") }
        assertThrows(IllegalArgumentException::class.java) { empty.withAnyEntityIds(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { empty.withAllEntityIds(listOf("e1", "")) }
        assertThrows(IllegalArgumentException::class.java) { empty.withAnyEntity("e1", " ") }
        assertThrows(IllegalArgumentException::class.java) { empty.withStatuses(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { empty.withMinConfidence(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { empty.withMinTrustScore(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { empty.withDecayK(-0.1) }
        assertThrows(IllegalArgumentException::class.java) { empty.withDecayK(Double.NaN) }
        assertTrue(empty.withDecayK(0.0).decayK == 0.0)
        assertTrue(empty.withMinLevel(-1).minLevel == -1)
    }

    @Test
    fun `E4 the block, the chain and the short steps give the same criteria`() {
        val chain = ReadCriteria.empty()
            .withEntityId("alice")
            .withAllEntityIds(listOf("e1", "e2"))
            .withStatuses(setOf(PropositionStatus.ACTIVE))
            .withMaxLevel(2)
            .withCreatedAfter(instant)
            .withMinEffectiveConfidence(0.3)
            .withEffectiveConfidenceAsOf(instant)
            .withDecayK(4.0)
            .withPinned(false)
        val block = ReadCriteria {
            entityId = "alice"
            allEntityIds = listOf("e1", "e2")
            statuses = setOf(PropositionStatus.ACTIVE)
            maxLevel = 2
            createdAfter = instant
            minEffectiveConfidence = 0.3
            effectiveConfidenceAsOf = instant
            decayK = 4.0
            pinned = false
        }
        assertEquals(chain, block)
        assertEquals(chain.hashCode(), block.hashCode())
        assertEquals(ReadCriteria.empty(), ReadCriteria { })
        assertThrows(IllegalArgumentException::class.java) { ReadCriteria { anyEntityIds = emptyList() } }

        val empty = ReadCriteria.empty()
        val later = instant.plusSeconds(60)
        assertEquals(empty.withAnyEntityIds(listOf("e1", "e2")), empty.withAnyEntity("e1", "e2"))
        assertEquals(empty.withAllEntityIds(listOf("e1")), empty.withAllEntities("e1"))
        assertEquals(
            empty.withStatuses(setOf(PropositionStatus.ACTIVE, PropositionStatus.PROMOTED)),
            empty.withStatuses(PropositionStatus.ACTIVE, PropositionStatus.PROMOTED),
        )
        assertEquals(empty.withCreatedAfter(instant).withCreatedBefore(later), empty.withCreatedBetween(instant, later))
        assertEquals(empty.withRevisedAfter(instant).withRevisedBefore(later), empty.withRevisedBetween(instant, later))
        assertEquals(empty.withAccessedAfter(instant).withAccessedBefore(later), empty.withAccessedBetween(instant, later))
    }

    @Test
    fun `E5 a new query field turns this test red until the criteria account for it`() {
        // contextId is left out so criteria name no context. ReadQuery carries the order and the
        // limit. A field added to PropositionQuery joins ReadCriteria or this set, with a reason.
        val carriedElsewhere = setOf("contextId", "orderBy", "limit")
        val queryFields = fieldNames(PropositionQuery::class.java)
        val criteriaFields = fieldNames(ReadCriteria::class.java)
        assertTrue(queryFields.containsAll(carriedElsewhere))
        assertEquals(queryFields - carriedElsewhere, criteriaFields)
        assertTrue(criteriaFields.none { it.contains("context", ignoreCase = true) })
        assertEquals(criteriaFields, ReadCriterion.entries.map { propertyName(it) }.toSet())
    }

    @Test
    fun `E6 every criterion has a step that sets exactly that criterion`() {
        val empty = ReadCriteria.empty()
        val steps: Map<ReadCriterion, ReadCriteria> = mapOf(
            ReadCriterion.ENTITY_ID to empty.withEntityId("e1"),
            ReadCriterion.ANY_ENTITY_IDS to empty.withAnyEntityIds(listOf("e1")),
            ReadCriterion.ALL_ENTITY_IDS to empty.withAllEntityIds(listOf("e1")),
            ReadCriterion.STATUSES to empty.withStatus(PropositionStatus.ACTIVE),
            ReadCriterion.MIN_LEVEL to empty.withMinLevel(1),
            ReadCriterion.MAX_LEVEL to empty.withMaxLevel(1),
            ReadCriterion.CREATED_AFTER to empty.withCreatedAfter(instant),
            ReadCriterion.CREATED_BEFORE to empty.withCreatedBefore(instant),
            ReadCriterion.REVISED_AFTER to empty.withRevisedAfter(instant),
            ReadCriterion.REVISED_BEFORE to empty.withRevisedBefore(instant),
            ReadCriterion.ACCESSED_AFTER to empty.withAccessedAfter(instant),
            ReadCriterion.ACCESSED_BEFORE to empty.withAccessedBefore(instant),
            ReadCriterion.MIN_CONFIDENCE to empty.withMinConfidence(0.5),
            ReadCriterion.MIN_EFFECTIVE_CONFIDENCE to empty.withMinEffectiveConfidence(0.5),
            ReadCriterion.BELOW_EFFECTIVE_CONFIDENCE to empty.withBelowEffectiveConfidence(0.5),
            ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF to empty.withEffectiveConfidenceAsOf(instant),
            ReadCriterion.DECAY_K to empty.withDecayK(4.0),
            ReadCriterion.MIN_IMPORTANCE to empty.withMinImportance(0.5),
            ReadCriterion.MIN_REINFORCE_COUNT to empty.withMinReinforceCount(1),
            ReadCriterion.MIN_TRUST_SCORE to empty.withMinTrustScore(0.5),
            ReadCriterion.PINNED to empty.withPinned(true),
        )
        assertEquals(ReadCriterion.entries.toSet(), steps.keys)
        for ((criterion, criteria) in steps) {
            assertEquals(setOf(criterion), criteria.specified)
            assertNotEquals(empty, criteria)
        }
        // A criterion left out of equality or of the text form would make two of these equal.
        assertEquals(steps.size, steps.values.toSet().size)
        assertEquals(steps.size, steps.values.map { it.toString() }.toSet().size)
        assertTrue(empty.withDecayK(ReadCriteria.DEFAULT_DECAY_K).specified.isEmpty())
    }

    @Test
    fun `E7 requireOnly passes when every set criterion is supported and names the first that is not`() {
        val criteria = ReadCriteria.empty().withPinned(true).withEntityId("alice")
        criteria.requireOnly(EnumSet.of(ReadCriterion.ENTITY_ID, ReadCriterion.PINNED), StoreCapability.SCOPED_READS)
        ReadCriteria.empty().requireOnly(emptySet(), StoreCapability.SCOPED_READS)

        val onePassed = assertThrows(UnsupportedCapabilityException::class.java) {
            criteria.requireOnly(setOf(ReadCriterion.ENTITY_ID), StoreCapability.SCOPED_VECTOR_SEARCH)
        }
        assertEquals(StoreCapability.SCOPED_VECTOR_SEARCH, onePassed.capability)
        assertTrue(onePassed.message.orEmpty().contains("PINNED"))

        val nonePassed = assertThrows(UnsupportedCapabilityException::class.java) {
            criteria.requireOnly(emptySet(), StoreCapability.SCOPED_READS)
        }
        assertTrue(nonePassed.message.orEmpty().contains("ENTITY_ID"))
    }

    @Test
    fun `E8 negative zero and zero give equal criteria`() {
        val empty = ReadCriteria.empty()
        assertEquals(empty.withMinConfidence(0.0), empty.withMinConfidence(-0.0))
        assertEquals(empty.withMinConfidence(0.0).hashCode(), empty.withMinConfidence(-0.0).hashCode())
        assertEquals(empty.withDecayK(0.0), empty.withDecayK(-0.0))
    }

    /**
     * The names of the instance fields a class declares.
     *
     * @param type The class to read.
     * @return The field names, without static and compiler-made fields.
     */
    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

    /**
     * The property of [ReadCriteria] a criterion stands for.
     *
     * @param criterion The criterion.
     * @return Its name in camel case, such as `minConfidence` for `MIN_CONFIDENCE`.
     */
    private fun propertyName(criterion: ReadCriterion): String =
        criterion.name.lowercase().split('_')
            .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")
}
