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
import com.embabel.dice.proposition.ApprovedSearchText
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadRow
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.tools.ToolProvider

class MemoryReadPolicyTest {
    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val spec = ScopedReadSpec.at(now, 2.0)
    private val key = PropositionKey("A", "private-id")
    private val stamp = ReadPolicyStamp("principal", "purpose", "policy", "v1")
    private fun grant(projection: RowProjection = RowProjection.text(ApprovedText.Omitted), embedding: Boolean = false) =
        RowGrant.of(key, projection, true, embedding)
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
    fun `named projection chain equals direct and Kotlin block values and snapshots every step`() {
        val fields = linkedSetOf(MemoryField.ID)
        val metadata = linkedSetOf("public")
        val handles = linkedSetOf("public-person")
        val sources = arrayListOf(AllowedSource("source", true, false))
        val base = RowProjection.projecting(ApprovedText.Stored)
        val withFields = base.withFields(fields)
        val configured = withFields.withMetadataKeys(metadata).withEntityHandles(handles).withSources(sources)
        val direct = RowProjection.text(ApprovedText.Stored).withFields(fields).withMetadataKeys(metadata)
            .withEntityHandles(handles).withSources(sources)
        val block = RowProjection(ApprovedText.Stored) {
            this.fields = fields; metadataKeys = metadata; entityHandles = handles; this.sources = sources
        }
        fields.clear(); metadata.clear(); handles.clear(); sources.clear()
        assertEquals(direct, block)
        assertEquals(direct, configured.projection())
        assertEquals(RowProjection.text(ApprovedText.Stored), base.projection())
        assertEquals(setOf(MemoryField.ID), withFields.projection().fields)
        assertTrue(withFields.projection().metadataKeys.isEmpty())
        val modified = configured.withFields(setOf(MemoryField.OWNER)).projection()
        assertEquals(setOf(MemoryField.OWNER), modified.fields)
        assertEquals(direct, configured.projection())
        assertThrows<UnsupportedOperationException> { (configured.projection().fields as MutableSet).clear() }
        assertThrows<UnsupportedOperationException> { (configured.projection().metadataKeys as MutableSet).clear() }
        assertThrows<UnsupportedOperationException> { (configured.projection().entityHandles as MutableSet).clear() }
        assertThrows<UnsupportedOperationException> { (configured.projection().sources as MutableList).clear() }
    }

    @Test
    fun `invalid projection keys and sources validate at terminal with direct and block messages`() {
        val base = RowProjection.projecting(ApprovedText.Omitted)
        val duplicate = AllowedSource("source", true, false)
        val invalidChains = listOf(
            base.withMetadataKeys(setOf("")), base.withEntityHandles(setOf(" ")),
            base.withSources(listOf(duplicate, duplicate)),
        )
        val directCalls = listOf<() -> RowProjection>(
            { RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("")) },
            { RowProjection.text(ApprovedText.Omitted).withEntityHandles(setOf(" ")) },
            { RowProjection.text(ApprovedText.Omitted).withSources(listOf(duplicate, duplicate)) },
        )
        val blockCalls = listOf<() -> RowProjection>(
            { RowProjection(ApprovedText.Omitted) { metadataKeys = setOf("") } },
            { RowProjection(ApprovedText.Omitted) { entityHandles = setOf(" ") } },
            { RowProjection(ApprovedText.Omitted) { sources = listOf(duplicate, duplicate) } },
        )
        invalidChains.indices.forEach { index ->
            val direct = assertThrows<IllegalArgumentException> { directCalls[index]() }
            assertEquals(direct.message, assertThrows<IllegalArgumentException> { blockCalls[index]() }.message)
            assertEquals(direct.message, assertThrows<IllegalArgumentException> { invalidChains[index].projection() }.message)
        }
        assertEquals(RowProjection.text(ApprovedText.Omitted), base.projection())
    }

    @Test
    fun `policy snapshots deduplicate identical rows and preserve caller order`() {
        val first = grant()
        val second = RowGrant.of(PropositionKey("B", "same-id"), first.projection, true, false)
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
    fun `source attestation is required even for derived facts`() {
        val derived = proposition(level = 2).copy(sourceIds = listOf("secret-parent"))
        assertEquals(2, derived.level)
        assertThrows<IllegalArgumentException> { RowGrant.of(key, RowProjection.text(ApprovedText.Stored), false, false) }
        assertThrows<IllegalArgumentException> { RowGrant.of(key, RowProjection.text(ApprovedText.Omitted), false, false) }
    }

    @Test
    fun `only full stored text can authorize its stored embedding`() {
        assertThrows<IllegalArgumentException> { grant(RowProjection.text(ApprovedText.Literal("replacement")), true) }
        assertThrows<IllegalArgumentException> { grant(RowProjection.text(ApprovedText.Omitted), true) }
        assertThrows<IllegalArgumentException> { policy(RowProjection.text(ApprovedText.Stored)).validate(scope, spec, vectorSearch = true) }
        RowPolicy.of(stamp, listOf(grant(RowProjection.text(ApprovedText.Stored), true))).validate(scope, spec, vectorSearch = true)
    }

    @Test
    fun `principal purpose policy and version form independent host identities`() {
        val stamps = listOf(stamp, stamp.copy(principal = "other"), stamp.copy(purpose = "other"),
            stamp.copy(policyId = "other"), stamp.copy(version = "other"))
        assertEquals(5, stamps.toSet().size)
        stamps.forEach { identity -> assertEquals(identity, RowPolicy.of(identity, emptyList()).stamp) }
        // Equal stamps must never reuse an earlier policy's more generous content.
        policy(RowProjection.text(ApprovedText.Stored)).validate(scope, spec, keywordSearch = true)
        assertThrows<IllegalArgumentException> { policy().validate(scope, spec, keywordSearch = true) }
        assertThrows<IllegalArgumentException> { stamp.copy(principal = " ") }
        assertThrows<IllegalArgumentException> { stamp.copy(version = "") }
    }

    @Test
    fun `empty grants deny all and outsider grants reject in either influence mode`() {
        assertTrue(RowPolicy.of(stamp, emptyList()).grants.isEmpty())
        RowPolicy.of(stamp, emptyList()).validate(scope, spec, keywordSearch = true, vectorSearch = true)
        val outsider = RowGrant.of(PropositionKey("C", "forbidden"), RowProjection.text(ApprovedText.Stored), true, true)
        HiddenFieldInfluence.entries.forEach { influence ->
            assertThrows<IllegalArgumentException> { RowPolicy.of(stamp, listOf(outsider), influence).validate(scope, spec) }
        }
    }

    @Test
    fun `each filter requires its actual approved dependency`() {
        val base = PropositionQuery()
        val cases = listOf(
            base.copy(entityId = "e") to MemoryField.ENTITY_IDS,
            base.copy(anyEntityIds = emptyList()) to MemoryField.ENTITY_IDS,
            base.copy(allEntityIds = listOf("e")) to MemoryField.ENTITY_IDS,
            base.copy(statuses = setOf(PropositionStatus.ACTIVE)) to MemoryField.STATUS,
            base.copy(minLevel = 0) to MemoryField.LEVEL, base.copy(maxLevel = 2) to MemoryField.LEVEL,
            base.copy(createdAfter = now) to MemoryField.CREATED, base.copy(createdBefore = now) to MemoryField.CREATED,
            base.copy(revisedAfter = now) to MemoryField.CONTENT_REVISED, base.copy(revisedBefore = now) to MemoryField.CONTENT_REVISED,
            base.copy(accessedAfter = now) to MemoryField.LAST_ACCESSED, base.copy(accessedBefore = now) to MemoryField.LAST_ACCESSED,
            base.copy(minConfidence = 0.2) to MemoryField.RAW_CONFIDENCE,
            base.copy(minEffectiveConfidence = 0.2) to MemoryField.EFFECTIVE_CONFIDENCE,
            base.copy(belowEffectiveConfidence = 0.9) to MemoryField.EFFECTIVE_CONFIDENCE,
            base.copy(minImportance = 0.1) to MemoryField.IMPORTANCE,
            base.copy(minReinforceCount = 1) to MemoryField.REINFORCE_COUNT,
            base.copy(minTrustScore = 0.2) to MemoryField.TRUST_SCORE, base.copy(pinned = false) to MemoryField.PINNED,
        )
        cases.forEach { (criteria, field) ->
            val restricted = spec.restrict(scope, criteria)
            assertThrows<IllegalArgumentException>(field.name) { policy().validate(scope, restricted) }
            policy(RowProjection.text(ApprovedText.Omitted).withFields(setOf(field))).validate(scope, restricted)
        }
    }

    @Test
    fun `every ordering requires its approved metric and score is not a bypass`() {
        ReadOrder.entries.filter { it != ReadOrder.NONE }.forEach { order ->
            assertThrows<IllegalArgumentException> { policy().validate(scope, spec, order) }
        }
        val scoreOnly = policy(RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.SCORE)))
        assertThrows<IllegalArgumentException> { scoreOnly.validate(scope, spec, scoreInputs = setOf(MemoryField.IMPORTANCE)) }
        assertThrows<IllegalArgumentException> { scoreOnly.validate(scope, spec, keywordSearch = true) }
        assertThrows<IllegalArgumentException> { scoreOnly.validate(scope, spec, vectorSearch = true) }
    }

    @Test
    fun `provenance dependencies and metadata dependencies are per key and sibling`() {
        val projection = RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("public"))
            .withSources(listOf(AllowedSource("uri:private-location", true, false)))
        val policy = policy(projection)
        policy.validate(scope, spec, metadataInputs = setOf("public"), sourceInputs = listOf(AllowedSource("uri:private-location", true, false)))
        assertThrows<IllegalArgumentException> { policy.validate(scope, spec, metadataInputs = setOf("secret")) }
        assertThrows<IllegalArgumentException> { policy.validate(scope, spec, sourceInputs = listOf(AllowedSource("uri:private-location", false, true))) }
        assertThrows<IllegalArgumentException> { policy.validate(scope, spec, sourceInputs = listOf(AllowedSource("other-source", true, false))) }
    }

    @Test
    fun `display minimization requires explicit opt in while output still omits hidden fields`() {
        val grant = grant()
        val minimized = RowPolicy.of(stamp, listOf(grant), HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
        minimized.validate(scope, spec.restrict(scope, PropositionQuery(minConfidence = 0.1)),
            ReadOrder.IMPORTANCE_DESC, keywordSearch = true, vectorSearch = true)
        val output = ProjectedMemory.from(grant, proposition(), 0.9, now, 2.0)
        assertNull(output.text); assertNull(output.id); assertNull(output.score); assertNull(output.confidence)
    }

    @Test
    fun `serialized deny default omits hidden raw fields and all envelopes`() {
        val output = ProjectedMemory.from(grant(), proposition(mapOf("secret-key" to "secret-value")), 0.9, now, 2.0)
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
        val output = ProjectedMemory.from(grant(projection), proposition(), null, now, 2.0)
        assertEquals("replacement", output.text)
        assertEquals(listOf("public-handle"), output.entityHandles)
        assertEquals(listOf(ProjectedSource("private-descriptor", null)), output.sources)
        val json = ObjectMapper().writeValueAsString(output)
        assertFalse(json.contains("private-location")); assertFalse(json.contains("hidden-entity")); assertFalse(json.contains("locator"))
        val locatorOnly = projection.withSources(listOf(AllowedSource("uri:private-location", false, true)))
        val source = ProjectedMemory.from(grant(locatorOnly), proposition(), null, now, 2.0).sources.single()
        assertNull(source.descriptor); assertEquals("uri:private-location", source.locator)
    }

    @Test
    fun `derived metrics do not authorize raw input fields or generic metadata`() {
        val projection = RowProjection.text(ApprovedText.Omitted)
            .withFields(setOf(MemoryField.EFFECTIVE_CONFIDENCE, MemoryField.TRUST_SCORE))
        val policy = policy(projection)
        policy.validate(scope, spec.restrict(scope, PropositionQuery(minEffectiveConfidence = 0.1, minTrustScore = 0.1)),
            ReadOrder.EFFECTIVE_CONFIDENCE_DESC)
        assertThrows<IllegalArgumentException> { policy.validate(scope, spec.restrict(scope, PropositionQuery(minConfidence = 0.1))) }
        assertThrows<IllegalArgumentException> { policy.validate(scope, spec, ReadOrder.REVISED_DESC) }
        val output = ProjectedMemory.from(grant(projection), proposition(mapOf(DiceMetadataKeys.TRUST_SCORE to 0.7)), null, now, 2.0)
        assertEquals(0.8, output.effectiveConfidence); assertEquals(0.7, output.trustScore)
        assertNull(output.confidence); assertNull(output.contentRevised); assertNull(output.temporal); assertTrue(output.metadata.isEmpty())
    }

    @Test
    fun `all explicitly approved fields have typed projections and temporal snapshots`() {
        val parents = arrayListOf("approved-parent")
        val temporal = TemporalMetadata(observedAt = now, supersedes = parents, contradicts = listOf("approved-contradiction"))
        val projection = RowProjection.text(ApprovedText.Stored).withFields(MemoryField.entries.toSet())
        val output = ProjectedMemory.from(grant(projection), proposition(mapOf(DiceMetadataKeys.TRUST_SCORE to 0.7)).copy(temporal = temporal), 0.9, now, 2.0)
        parents.clear()
        assertEquals("A", output.owner); assertEquals(key.propositionId, output.id)
        assertEquals(PropositionStatus.ACTIVE, output.status); assertEquals(0, output.level)
        assertEquals(now, output.created); assertEquals(now, output.contentRevised); assertEquals(now, output.metadataRevised)
        assertEquals(now, output.lastAccessed); assertEquals(0.5, output.importance); assertEquals(0, output.reinforceCount)
        assertEquals(false, output.pinned); assertEquals(listOf("hidden-entity"), output.entityHandles)
        assertEquals(now, output.temporal!!.observedAt)
        assertFalse(output.temporal.javaClass.methods.any { it.name == "getSupersedes" || it.name == "getContradicts" })
        assertEquals(0.9, output.score)
    }

    @Test
    fun `approved metadata is a deep immutable JSON snapshot`() {
        val nested = arrayListOf<Any?>("public-value", null, linkedMapOf("number" to 3))
        val metadata = linkedMapOf<String, Any>("public" to nested, "secret" to Any())
        val projection = RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("public"))
        val output = ProjectedMemory.from(grant(projection), proposition(metadata), null, now, 2.0)
        nested.clear(); metadata.clear()
        assertEquals(listOf("public-value", null, mapOf("number" to 3)), output.metadata["public"])
        assertThrows<UnsupportedOperationException> { (output.metadata as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (output.metadata["public"] as MutableList<*>).clear() }
        val json = ObjectMapper().writeValueAsString(output)
        assertFalse(json.contains("secret")); assertTrue(json.contains("public-value"))
    }

    @Test
    fun `unsafe nonfinite and cyclic approved metadata reject without toString`() {
        val projection = RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("public"))
        val objectWithDangerousString = object { override fun toString(): String = error("must never call") }
        val cycle = ArrayList<Any>()
        cycle.add(cycle)
        val customNumber = object : java.math.BigDecimal("1") {
            override fun toShort(): Short = 1
            override fun toByte(): Byte = 1
            override fun toString(): String = error("must never serialize arbitrary numeric subclasses")
        }
        listOf(objectWithDangerousString, customNumber, Double.NaN, Double.POSITIVE_INFINITY, mapOf(1 to "not a JSON key"), cycle).forEach { unsafe ->
            assertThrows<IllegalArgumentException> {
                ProjectedMemory.from(grant(projection), proposition(mapOf("public" to unsafe)), null, now, 2.0)
            }
        }
    }

    @Test
    fun `projection checks actual row identity and numeric score`() {
        assertThrows<IllegalArgumentException> { ProjectedMemory.from(grant(), proposition().copy(contextId = ContextId("C")), null, now, 2.0) }
        assertThrows<IllegalArgumentException> {
            ProjectedMemory.from(grant(RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.SCORE))), proposition(), Double.NaN, now, 2.0)
        }
    }

    @Test
    fun `finite policy bounds reject rather than truncate`() {
        assertThrows<IllegalArgumentException> { RowPolicy.of(stamp, List(1001) { grant() }) }
        val text = ApprovedText.Literal("x".repeat(1024 * 1024))
        assertThrows<IllegalArgumentException> { ApprovedText.Literal("é".repeat(524289)) }
        val second = RowGrant.of(PropositionKey("B", "second"), RowProjection.text(ApprovedText.Literal("x")), true, false)
        assertThrows<IllegalArgumentException> { RowPolicy.of(stamp, listOf(grant(RowProjection.text(text)), second)) }
        assertThrows<IllegalArgumentException> { RowProjection.text(ApprovedText.Omitted).withMetadataKeys(setOf("")) }
        assertThrows<IllegalArgumentException> { RowProjection.text(ApprovedText.Omitted).withEntityHandles(setOf(" ")) }
        assertThrows<IllegalArgumentException> { RowGrant.of(PropositionKey("", "id"), RowProjection.text(ApprovedText.Omitted), true, false) }
    }

    @Test
    fun `strict request bounds scope duplicates and timeouts validate`() {
        val row = StrictReadRow(key, 0, ApprovedSearchText.Literal("replacement"))
        StrictReadRow.validateRequest(scope, spec, listOf(row), 1, Duration.ofSeconds(5))
        StrictReadRow.validateRequest(scope, spec, emptyList(), 0, Duration.ofNanos(1))
        listOf(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(5).plusNanos(1)).forEach { timeout ->
            assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(scope, spec, listOf(row), 1, timeout) }
        }
        listOf(-1, 1001, Int.MAX_VALUE).forEach { limit ->
            assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(scope, spec, listOf(row), limit, Duration.ofSeconds(1)) }
        }
        assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(scope, spec, listOf(row, row), 1, Duration.ofSeconds(1)) }
        assertThrows<IllegalArgumentException> { StrictReadRow.validateRequest(scope, spec, listOf(row.copy(key = PropositionKey("C", "forbidden"))), 1, Duration.ofSeconds(1)) }
        assertThrows<IllegalArgumentException> { row.copy(ordinal = -1) }
        assertThrows<IllegalArgumentException> { ApprovedSearchText.Literal("x".repeat(1024 * 1024 + 1)) }
    }

    @Test
    fun `Java callers use immutable configured wither construction`(@TempDir directory: Path) {
        val source = directory.resolve("PolicyClient.java")
        source.toFile().writeText("""
            import com.embabel.dice.agent.*;
            import java.util.Set;
            public class PolicyClient {
                public static RowProjection projection() {
                    return RowProjection.text(ApprovedText.Stored.INSTANCE)
                        .withFields(Set.of(MemoryField.ID)).withMetadataKeys(Set.of("public"))
                        .withEntityHandles(Set.of("public-handle")).withSources(java.util.List.of());
                }
                public static RowProjection invalidFields() {
                    java.util.Set<MemoryField> fields = new java.util.HashSet<>();
                    fields.add(null);
                    return RowProjection.text(ApprovedText.Omitted.INSTANCE).withFields(fields);
                }
                public static RowPolicy policy() {
                    return RowPolicy.of(new ReadPolicyStamp("p", "purpose", "policy", "v1"), java.util.List.of());
                }
            }
        """.trimIndent())
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler)
        val classpath = System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path")
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", directory.toString(), source.toString()))
        java.net.URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val result = loader.loadClass("PolicyClient").getMethod("projection").invoke(null) as RowProjection
            assertEquals(setOf(MemoryField.ID), result.fields)
            assertEquals(ApprovedText.Stored, result.text)
            val rejection = assertThrows<java.lang.reflect.InvocationTargetException> {
                loader.loadClass("PolicyClient").getMethod("invalidFields").invoke(null)
            }
            assertInstanceOf(IllegalArgumentException::class.java, rejection.cause)
        }
    }
}
