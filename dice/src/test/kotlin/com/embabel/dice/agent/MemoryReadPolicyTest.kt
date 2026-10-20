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
package com.embabel.dice.agent

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.ApprovedText
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadRow
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class MemoryReadPolicyTest {
    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val spec = ScopedReadSpec.of(scope)
    private val clock = ReadClock(now, 2.0)
    private val key = PropositionKey("A", "private-id")
    private val stamp = ReadPolicyStamp("principal", "purpose", "policy", "v1")
    private fun grant(projection: RowProjection = RowProjection.text(ApprovedText.Omitted), embedding: Boolean = false) =
        RowGrant.of(key, projection, embedding)
    private fun policy(projection: RowProjection = RowProjection.text(ApprovedText.Omitted)) =
        RowPolicy.of(stamp, listOf(grant(projection)))
    private fun proposition(metadata: Map<String, Any> = emptyMap(), level: Int = 0) = Proposition(
        id = key.propositionId, contextId = ContextId("A"), text = "hidden stored text",
        mentions = listOf(EntityMention("hidden name", "Person", "hidden-entity")), confidence = 0.8,
        created = now, contentRevised = now, metadataRevised = now, lastAccessed = now,
        metadata = metadata, level = level, sourceIds = if (level > 0) listOf("secret-parent") else emptyList(),
        provenanceEntries = listOf(ProvenanceEntry(UriLocator("private-location", "private-descriptor"))),
    )

    @Test
    fun `configured construction and withers snapshot all inputs`() {
        val fields = linkedSetOf(MemoryField.ID)
        val metadata = linkedSetOf("public")
        val handles = linkedSetOf("public-person")
        val sources = arrayListOf(AllowedSource("uri:private-location", true, false))
        lateinit var config: RowProjection.Configuration
        val projection = RowProjection(ApprovedText.Literal("public text")) {
            config = this
            this.fields = fields
            metadataKeys = metadata
            entityHandles = handles
            this.sources = sources
        }
        fields.clear(); metadata.clear(); handles.clear(); sources.clear()
        config.fields = setOf(MemoryField.OWNER)
        assertEquals(setOf(MemoryField.ID), projection.fields)
        assertEquals(setOf("public"), projection.metadataKeys)
        assertEquals(setOf("public-person"), projection.entityHandles)
        assertEquals(1, projection.sources.size)
        assertThrows<UnsupportedOperationException> { (projection.fields as MutableSet).clear() }
        assertThrows<UnsupportedOperationException> { (projection.sources as MutableList).clear() }
        val additional = linkedSetOf(MemoryField.SCORE)
        val modified = projection.withFields(additional)
        additional.clear()
        assertEquals(setOf(MemoryField.SCORE), modified.fields)
        assertEquals(setOf(MemoryField.ID), projection.fields)
    }

    @Test
    fun `policy snapshots deduplicate identical rows and preserve caller order`() {
        val first = grant()
        val second = RowGrant.of(PropositionKey("B", "same-id"), first.projection, false)
        val input = arrayListOf(first, second, grant())
        val policy = RowPolicy.of(stamp, input)
        input.clear()
        assertEquals(listOf(first, second), policy.grants)
        assertThrows<UnsupportedOperationException> { (policy.grants as MutableList).clear() }
        assertThrows<IllegalArgumentException> {
            RowPolicy.of(stamp, listOf(first, grant(RowProjection.text(ApprovedText.Stored))))
        }
    }

    @Test
    fun `only full stored text can authorize its stored embedding`() {
        assertThrows<IllegalArgumentException> { grant(RowProjection.text(ApprovedText.Literal("replacement")), true) }
        assertThrows<IllegalArgumentException> { grant(RowProjection.text(ApprovedText.Omitted), true) }
        assertThrows<IllegalArgumentException> { policy(RowProjection.text(ApprovedText.Stored)).validate(spec, vectorSearch = true) }
        RowPolicy.of(stamp, listOf(grant(RowProjection.text(ApprovedText.Stored), true))).validate(spec, vectorSearch = true)
    }

    @Test
    fun `empty grants deny all and outsider grants reject in either influence mode`() {
        assertTrue(RowPolicy.of(stamp, emptyList()).grants.isEmpty())
        RowPolicy.of(stamp, emptyList()).validate(spec, keywordSearch = true, vectorSearch = true)
        val outsider = RowGrant.of(PropositionKey("C", "forbidden"), RowProjection.text(ApprovedText.Stored), true)
        HiddenFieldInfluence.entries.forEach { influence ->
            assertThrows<IllegalArgumentException> { RowPolicy.of(stamp, listOf(outsider), influence).validate(spec) }
        }
    }

    @Test
    fun `validate over a spec checks grants against the spec's own scope`() {
        val outsider = RowGrant.of(PropositionKey("C", "outside"), RowProjection.text(ApprovedText.Stored), true)
        val withoutC = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A", "B")))
        val withC = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A", "B", "C")))
        HiddenFieldInfluence.entries.forEach { influence ->
            val policy = RowPolicy.of(stamp, listOf(grant(), outsider), influence)
            // E1: the grant in C is rejected because the spec's own scope stops at A and B.
            assertThrows<IllegalArgumentException>(influence.name) { policy.validate(withoutC) }
            // E2: the same grant passes once the spec's scope includes C.
            policy.validate(withC)
        }
    }

    @Test
    fun `each filter requires its actual approved dependency`() {
        val base = PropositionQuery()
        val cases = listOf(
            base.copy(contextId = ContextId("A")) to MemoryField.OWNER,
            base.copy(entityId = "e") to MemoryField.ENTITY_IDS,
            base.copy(anyEntityIds = emptyList()) to MemoryField.ENTITY_IDS,
            base.copy(allEntityIds = listOf("e")) to MemoryField.ENTITY_IDS,
            base.copy(statuses = setOf(PropositionStatus.ACTIVE)) to MemoryField.STATUS,
            base.copy(minLevel = 0) to MemoryField.LEVEL, base.copy(maxLevel = 2) to MemoryField.LEVEL,
            base.copy(createdAfter = now) to MemoryField.CREATED, base.copy(createdBefore = now) to MemoryField.CREATED,
            base.copy(accessedAfter = now) to MemoryField.LAST_ACCESSED, base.copy(accessedBefore = now) to MemoryField.LAST_ACCESSED,
            base.copy(minConfidence = 0.2) to MemoryField.RAW_CONFIDENCE,
            base.copy(minEffectiveConfidence = 0.2) to MemoryField.EFFECTIVE_CONFIDENCE,
            base.copy(belowEffectiveConfidence = 0.9) to MemoryField.EFFECTIVE_CONFIDENCE,
            base.copy(minImportance = 0.1) to MemoryField.IMPORTANCE,
            base.copy(minReinforceCount = 1) to MemoryField.REINFORCE_COUNT,
            base.copy(minTrustScore = 0.2) to MemoryField.TRUST_SCORE, base.copy(pinned = false) to MemoryField.PINNED,
        )
        cases.forEach { (criteria, field) ->
            val restricted = spec.restrict(criteria)
            assertThrows<IllegalArgumentException>(field.name) { policy().validate(restricted) }
            policy(RowProjection.text(ApprovedText.Omitted).withFields(setOf(field))).validate(restricted)
        }
    }

    @Test
    fun `revision filtering and ordering require both last touched inputs under strict policy`() {
        val revisionFields = setOf(MemoryField.CONTENT_REVISED, MemoryField.METADATA_REVISED)
        val dependencies = listOf(
            spec.restrict(PropositionQuery(revisedAfter = now)) to PropositionQuery.OrderBy.NONE,
            spec.restrict(PropositionQuery(revisedBefore = now)) to PropositionQuery.OrderBy.NONE,
            spec.restrict(PropositionQuery(revisedAfter = now, revisedBefore = now)) to PropositionQuery.OrderBy.NONE,
            spec to PropositionQuery.OrderBy.REVISED_DESC,
        )
        dependencies.forEach { (criteria, order) ->
            revisionFields.forEach { omitted ->
                val projection = RowProjection.text(ApprovedText.Literal("approved"))
                    .withFields(revisionFields - omitted)
                assertThrows<IllegalArgumentException>("$omitted required for $order") {
                    policy(projection).validate(criteria, order)
                }
            }
            policy(RowProjection.text(ApprovedText.Literal("approved")).withFields(revisionFields))
                .validate(criteria, order)
            RowPolicy.of(stamp, listOf(grant()), HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
                .validate(criteria, order)
        }
    }

    @Test
    fun `every ordering requires its approved metric and score is not a bypass`() {
        PropositionQuery.OrderBy.entries.filter { it != PropositionQuery.OrderBy.NONE }.forEach { order ->
            assertThrows<IllegalArgumentException> { policy().validate(spec, order) }
        }
        val scoreOnly = policy(RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.SCORE)))
        assertThrows<IllegalArgumentException> { scoreOnly.validate(spec, scoreInputs = setOf(MemoryField.IMPORTANCE)) }
        assertThrows<IllegalArgumentException> { scoreOnly.validate(spec, keywordSearch = true) }
        assertThrows<IllegalArgumentException> { scoreOnly.validate(spec, vectorSearch = true) }
    }

    @Test
    fun `provenance dependencies and metadata dependencies are per key and sibling`() {
        val projection = RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("public"))
            .withSources(listOf(AllowedSource("uri:private-location", true, false)))
        val policy = policy(projection)
        policy.validate(spec, metadataInputs = setOf("public"), sourceInputs = listOf(AllowedSource("uri:private-location", true, false)))
        assertThrows<IllegalArgumentException> { policy.validate(spec, metadataInputs = setOf("secret")) }
        assertThrows<IllegalArgumentException> { policy.validate(spec, sourceInputs = listOf(AllowedSource("uri:private-location", false, true))) }
        assertThrows<IllegalArgumentException> { policy.validate(spec, sourceInputs = listOf(AllowedSource("other-source", true, false))) }
    }

    @Test
    fun `display minimization requires explicit opt in while output still omits hidden fields`() {
        val grant = grant()
        val minimized = RowPolicy.of(stamp, listOf(grant), HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
        minimized.validate(spec.restrict(PropositionQuery(minConfidence = 0.1)),
            PropositionQuery.OrderBy.IMPORTANCE_DESC, keywordSearch = true, vectorSearch = true)
        val output = ProjectedMemory.from(grant, proposition(), 0.9, clock)
        assertNull(output.text); assertNull(output.id); assertNull(output.score); assertNull(output.confidence)
    }

    @Test
    fun `serialized deny default omits hidden raw fields and all envelopes`() {
        val output = ProjectedMemory.from(grant(), proposition(mapOf("secret-key" to "secret-value")), 0.9, clock)
        val json = ObjectMapper().writeValueAsString(output)
        assertEquals(setOf("entityHandles", "sources", "metadata"), ObjectMapper().readTree(json).fieldNames().asSequence().toSet())
        listOf("hidden", "private", "secret", "principal", "policy", "contextId", "key").forEach { assertFalse(json.contains(it), json) }
        assertFalse(output.toString().contains("hidden"))
    }

    @Test
    fun `literal handles and provenance descriptor never expose hidden sibling data`() {
        val projection = RowProjection.text(ApprovedText.Literal("replacement"))
            .withEntityHandles(setOf("public-handle"))
            .withSources(listOf(AllowedSource("uri:private-location", true, false)))
        val output = ProjectedMemory.from(grant(projection), proposition(), null, clock)
        assertEquals("replacement", output.text)
        assertEquals(listOf("public-handle"), output.entityHandles)
        assertEquals(listOf(ProjectedSource("private-descriptor", null)), output.sources)
        val json = ObjectMapper().writeValueAsString(output)
        assertFalse(json.contains("private-location")); assertFalse(json.contains("hidden-entity")); assertFalse(json.contains("locator"))
        val locatorOnly = projection.withSources(listOf(AllowedSource("uri:private-location", false, true)))
        val source = ProjectedMemory.from(grant(locatorOnly), proposition(), null, clock).sources.single()
        assertNull(source.descriptor); assertEquals("uri:private-location", source.locator)
    }

    @Test
    fun `derived metrics do not authorize raw input fields or generic metadata`() {
        val projection = RowProjection.text(ApprovedText.Omitted)
            .withFields(setOf(MemoryField.EFFECTIVE_CONFIDENCE, MemoryField.TRUST_SCORE))
        val policy = policy(projection)
        policy.validate(spec.restrict(PropositionQuery(minEffectiveConfidence = 0.1, minTrustScore = 0.1)),
            PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC)
        assertThrows<IllegalArgumentException> { policy.validate(spec.restrict(PropositionQuery(minConfidence = 0.1))) }
        assertThrows<IllegalArgumentException> { policy.validate(spec, PropositionQuery.OrderBy.REVISED_DESC) }
        val output = ProjectedMemory.from(grant(projection), proposition(mapOf(DiceMetadataKeys.TRUST_SCORE to 0.7)), null, clock)
        assertEquals(0.8, output.effectiveConfidence); assertEquals(0.7, output.trustScore)
        assertNull(output.confidence); assertNull(output.contentRevised); assertNull(output.temporal); assertTrue(output.metadata.isEmpty())
    }

    @Test
    fun `a non-finite cached trust score projects as unscored instead of failing the row`() {
        val projection = RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.TRUST_SCORE))
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { cached ->
            val output = ProjectedMemory.from(grant(projection), proposition(mapOf(DiceMetadataKeys.TRUST_SCORE to cached)), null, clock)
            assertNull(output.trustScore, "cached trust score $cached")
        }
    }

    @Test
    fun `all explicitly approved fields have typed projections and temporal snapshots`() {
        val parents = arrayListOf("approved-parent")
        val temporal = TemporalMetadata(observedAt = now, supersedes = parents, contradicts = listOf("approved-contradiction"))
        val projection = RowProjection.text(ApprovedText.Stored).withFields(MemoryField.entries.toSet())
        val output = ProjectedMemory.from(grant(projection), proposition(mapOf(DiceMetadataKeys.TRUST_SCORE to 0.7)).copy(temporal = temporal), 0.9, clock)
        parents.clear()
        assertEquals("A", output.owner); assertEquals(key.propositionId, output.id)
        assertEquals(PropositionStatus.ACTIVE, output.status); assertEquals(0, output.level)
        assertEquals(now, output.created); assertEquals(now, output.contentRevised); assertEquals(now, output.metadataRevised)
        assertEquals(now, output.lastAccessed); assertEquals(0.5, output.importance); assertEquals(0, output.reinforceCount)
        assertEquals(false, output.pinned); assertTrue(output.entityHandles.isEmpty())
        assertEquals(listOf("hidden-entity"), output.entityIds)
        assertEquals(now, output.temporal!!.observedAt)
        assertFalse(output.temporal.javaClass.methods.any { it.name == "getSupersedes" || it.name == "getContradicts" })
        assertEquals(0.9, output.score)
    }

    @Test
    fun `resolved entity IDs show only in entityIds and only when granted`() {
        val idsOnly = RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.ENTITY_IDS))
        // E1: ENTITY_IDS puts the resolved ID in entityIds and leaves the grant's handles alone.
        val granted = ProjectedMemory.from(grant(idsOnly), proposition(), null, clock)
        assertTrue(granted.entityHandles.isEmpty())
        assertEquals(listOf("hidden-entity"), granted.entityIds)
        val denied = ProjectedMemory.from(grant(), proposition(), null, clock)
        assertTrue(denied.entityHandles.isEmpty())
        assertNull(denied.entityIds)
    }

    @Test
    fun `approved metadata keeps granted JSON values and drops the rest`() {
        val nested = listOf("public-value", null, mapOf("number" to 3))
        val metadata = linkedMapOf<String, Any>(
            "public" to nested, "count" to 7, "object" to Any(), "nan" to Double.NaN,
            "infinity" to Double.POSITIVE_INFINITY, "secret" to "secret-value",
        )
        val projection = RowProjection.text(ApprovedText.Stored)
            .withMetadataKeys(setOf("public", "count", "object", "nan", "infinity"))
        val output = ProjectedMemory.from(grant(projection), proposition(metadata), null, clock)
        assertEquals("hidden stored text", output.text)
        assertEquals(mapOf("public" to nested, "count" to 7), output.metadata)
        metadata.clear()
        assertEquals(mapOf("public" to nested, "count" to 7), output.metadata)
        assertThrows<UnsupportedOperationException> { (output.metadata as MutableMap).clear() }
        val json = ObjectMapper().writeValueAsString(output)
        assertFalse(json.contains("secret")); assertTrue(json.contains("public-value"))
    }

    @Test
    fun `projection checks actual row identity and numeric score`() {
        assertThrows<IllegalArgumentException> { ProjectedMemory.from(grant(), proposition().copy(contextId = ContextId("C")), null, clock) }
        assertThrows<IllegalArgumentException> {
            ProjectedMemory.from(grant(RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.SCORE))), proposition(), Double.NaN, clock)
        }
    }

    @Test
    fun `finite policy bounds reject rather than truncate`() {
        assertThrows<IllegalArgumentException> { RowPolicy.of(stamp, List(1001) { grant() }) }
        val text = ApprovedText.Literal("x".repeat(1024 * 1024))
        assertThrows<IllegalArgumentException> { ApprovedText.Literal("é".repeat(524289)) }
        val second = RowGrant.of(PropositionKey("B", "second"), RowProjection.text(ApprovedText.Literal("x")), false)
        assertThrows<IllegalArgumentException> { RowPolicy.of(stamp, listOf(grant(RowProjection.text(text)), second)) }
        assertThrows<IllegalArgumentException> { RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("")) }
        assertThrows<IllegalArgumentException> { RowProjection.text(ApprovedText.Omitted).withEntityHandles(setOf(" ")) }
        assertThrows<IllegalArgumentException> { RowGrant.of(PropositionKey("", "id"), RowProjection.text(ApprovedText.Omitted), false) }
    }

    @Test
    fun `strict request bounds scope duplicates and timeouts validate`() {
        val strict = ScopedReadSpec.of(scope)
        val row = StrictReadRow(key, ApprovedText.Literal("replacement"))
        StrictReadRow.validateRequest(strict, listOf(row), 1, Duration.ofSeconds(5))
        StrictReadRow.validateRequest(strict, emptyList(), 0, Duration.ofNanos(1))
        listOf(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(5).plusNanos(1)).forEach { timeout ->
            assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(strict, listOf(row), 1, timeout) }
        }
        listOf(-1, 1001, Int.MAX_VALUE).forEach { limit ->
            assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(strict, listOf(row), limit, Duration.ofSeconds(1)) }
        }
        // E1: a key may appear once; distinct keys pass in either order, since order is just list position.
        val sameKey = StrictReadRow(key, ApprovedText.Omitted)
        assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(strict, listOf(row, sameKey), 1, Duration.ofSeconds(1)) }
        val other = StrictReadRow(PropositionKey("B", "other-id"), ApprovedText.Stored)
        StrictReadRow.validateRequest(strict, listOf(row, other), 2, Duration.ofSeconds(1))
        StrictReadRow.validateRequest(strict, listOf(other, row), 2, Duration.ofSeconds(1))
        assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(strict, listOf(row.copy(key = PropositionKey("C", "forbidden"))), 1, Duration.ofSeconds(1)) }
        assertThrows<IllegalArgumentException> { ApprovedText.Literal("x".repeat(1024 * 1024 + 1)) }
    }
}
