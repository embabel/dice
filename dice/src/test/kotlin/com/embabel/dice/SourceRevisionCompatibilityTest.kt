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
package com.embabel.dice

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.spi.RetiredProposition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the compatibility boundary this slice claims: Kotlin source-level constructor and `copy`
 * calls keep compiling, stored JSON keeps loading, and the old synthetic constructor and `copy`
 * descriptors are gone.
 */
class SourceRevisionCompatibilityTest {

    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    @Test
    fun `legacy Kotlin source constructors and copy calls recompile against the candidate`() {
        val locator = ContentAddressedLocator("legacy-kotlin-source")
        val provenance = ProvenanceEntry(locator).copy(contentHash = "updated")
        assertEquals("updated", provenance.contentHash)
        assertNull(provenance.sourceRevision)

        val context = SourceAnalysisContext(
            schema = DataDictionary.fromClasses("compatibility"),
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("compatibility"),
        ).copy(promptVariables = mapOf("legacy" to true))
        assertEquals(true, context.promptVariables["legacy"])
        assertNull(context.sourceRevision)
    }

    @Test
    fun `new Kotlin source constructors and copy calls carry an opaque revision`() {
        val locator = ContentAddressedLocator("revisioned-kotlin-source")
        val revision = SourceRevisionRef(locator.key(), "opaque::r/1")
        val provenance = ProvenanceEntry(
            locator = locator,
            sourceRevision = revision.sourceRevision,
        ).copy(contentHash = "updated")
        assertEquals("opaque::r/1", provenance.sourceRevision)
        assertEquals("updated", provenance.contentHash)

        val context = SourceAnalysisContext(
            schema = DataDictionary.fromClasses("compatibility"),
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("compatibility"),
            sourceLocator = locator,
            sourceRevision = revision,
        ).copy(promptVariables = mapOf("revisioned" to true))
        assertSame(revision, context.sourceRevision)
        assertSame(locator, context.sourceLocator)
    }

    @Test
    fun `revisionless stored JSON remains readable and revisioned JSON round trips`() {
        val revisionless = mapper.readValue<Proposition>(fixture("proposition-revisionless.json"))
        assertNull(revisionless.provenanceEntries.single().sourceRevision)
        assertEquals(
            revisionless,
            mapper.readValue<Proposition>(mapper.writeValueAsString(revisionless)),
        )

        val revisioned = mapper.readValue<Proposition>(fixture("proposition-revisioned.json"))
        assertEquals(listOf("r1", "null"), revisioned.provenanceEntries.map { it.sourceRevision })
        assertEquals(
            revisioned,
            mapper.readValue<Proposition>(mapper.writeValueAsString(revisioned)),
        )
    }

    @Test
    fun `collector trace JSON keeps readable refs without exposing storage identities`() {
        val retired = RetiredProposition(
            propositionId = "retired",
            priorStatus = PropositionStatus.ACTIVE,
            foldedProvenanceRefs = listOf("uri:https://example.com/source"),
            foldedProvenanceEvidenceKeys = listOf("dice-provenance:v1:opaque"),
        )

        val json = mapper.writeValueAsString(retired)

        assertTrue(json.contains("uri:https://example.com/source"))
        assertFalse(json.contains("foldedProvenanceEvidenceKeys"))
        assertFalse(json.contains("dice-provenance:v1:opaque"))

        // The consequence, stated as an assertion: a trace that goes through JSON comes back with
        // its locator refs and no evidence keys, so it undoes at locator granularity.
        val readBack = mapper.readValue<RetiredProposition>(json)
        assertEquals(listOf("uri:https://example.com/source"), readBack.foldedProvenanceRefs)
        assertEquals(emptyList<String>(), readBack.foldedProvenanceEvidenceKeys)
    }

    @Test
    fun `old Kotlin synthetic constructor and copy descriptors are outside the approved boundary`() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

        assertThrows(NoSuchMethodException::class.java) {
            ProvenanceEntry::class.java.getDeclaredConstructor(
                com.embabel.dice.provenance.SourceLocator::class.java,
                String::class.java,
                Int::class.javaObjectType,
                Int::class.javaObjectType,
                String::class.java,
                Int::class.javaPrimitiveType,
                marker,
            )
        }
        assertThrows(NoSuchMethodException::class.java) {
            ProvenanceEntry::class.java.getDeclaredMethod(
                "copy",
                com.embabel.dice.provenance.SourceLocator::class.java,
                String::class.java,
                Int::class.javaObjectType,
                Int::class.javaObjectType,
                String::class.java,
            )
        }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/provenance/$name")) {
            "Missing provenance fixture: $name"
        }.readText()
}
