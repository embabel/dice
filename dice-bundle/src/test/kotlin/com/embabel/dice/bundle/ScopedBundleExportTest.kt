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
package com.embabel.dice.bundle

import com.embabel.agent.core.ContextId
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleExporter
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopedBundleExportTest {

    private val exporter = JacksonKnowledgeBundleExporter()

    private fun proposition(
        text: String,
        contextIdValue: String = "ctx-a",
        confidence: Double = 0.8,
    ): Proposition =
        Proposition(
            contextId = ContextId(contextIdValue),
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )

    // -------------------------------------------------------------------------
    // Context-scoped export
    // -------------------------------------------------------------------------

    @Test
    fun `exportScoped returns bundle with propositions from the specified context only`() {
        val ctxA = ContextId("ctx-a")
        val ctxB = ContextId("ctx-b")

        val propA1 = proposition("Fact from A 1", "ctx-a")
        val propA2 = proposition("Fact from A 2", "ctx-a")
        val propB1 = proposition("Fact from B 1", "ctx-b")

        val store = InMemoryPropositionRepository()
        store.save(propA1)
        store.save(propA2)
        store.save(propB1)

        // Export only context A
        val bundleA = exporter.exportScoped("ctx-a", store)

        assertEquals(ctxA, bundleA.contextId)
        assertEquals(2, bundleA.propositions.size)
        assertTrue(
            bundleA.propositions.all { it.contextId == ctxA },
            "All propositions in context A bundle should belong to context A",
        )
        assertTrue(
            bundleA.propositions.map { it.id }.containsAll(listOf(propA1.id, propA2.id)),
            "Bundle should contain the expected propositions",
        )
    }

    @Test
    fun `exportScoped excludes propositions from other contexts`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("A fact", "ctx-a"))
        store.save(proposition("B fact", "ctx-b"))
        store.save(proposition("C fact", "ctx-c"))

        val bundleA = exporter.exportScoped("ctx-a", store)

        assertEquals(1, bundleA.propositions.size)
        assertEquals("A fact", bundleA.propositions.first().text)
        assertEquals("ctx-a", bundleA.contextId.value)
    }

    @Test
    fun `exportScoped returns empty but valid bundle for context with no propositions`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("Lonely fact", "ctx-a"))

        // Request a context that has no propositions
        val bundleB = exporter.exportScoped("ctx-b", store)

        assertEquals("ctx-b", bundleB.contextId.value)
        assertEquals(0, bundleB.propositions.size)
        // Verify the empty bundle is still valid and can be serialized
        val json = exporter.exportToString(bundleB)
        assertTrue(json.contains("ctx-b"), "Serialized bundle should contain the context ID")
        assertTrue(json.contains("\"propositions\":[]"), "Serialized bundle should have empty propositions array")
    }

    @Test
    fun `exportScoped with empty repository returns empty bundle`() {
        val store = InMemoryPropositionRepository()
        val bundle = exporter.exportScoped("ctx-x", store)

        assertEquals("ctx-x", bundle.contextId.value)
        assertEquals(0, bundle.propositions.size)
    }

    // -------------------------------------------------------------------------
    // Export all contexts
    // -------------------------------------------------------------------------

    @Test
    fun `exportAllContexts returns one bundle per context present in store`() {
        val store = InMemoryPropositionRepository()

        // Create propositions in three contexts
        store.save(proposition("A1", "ctx-a"))
        store.save(proposition("A2", "ctx-a"))
        store.save(proposition("B1", "ctx-b"))
        store.save(proposition("C1", "ctx-c"))
        store.save(proposition("C2", "ctx-c"))
        store.save(proposition("C3", "ctx-c"))

        val bundles = exporter.exportAllContexts(store)

        assertEquals(3, bundles.size, "Should have one bundle per context")

        // Verify bundle for context A
        val bundleA = bundles.find { it.contextId.value == "ctx-a" }
        assertTrue(bundleA != null, "Should have a bundle for ctx-a")
        assertEquals(2, bundleA!!.propositions.size)
        assertTrue(bundleA.propositions.all { it.contextId.value == "ctx-a" })

        // Verify bundle for context B
        val bundleB = bundles.find { it.contextId.value == "ctx-b" }
        assertTrue(bundleB != null, "Should have a bundle for ctx-b")
        assertEquals(1, bundleB!!.propositions.size)

        // Verify bundle for context C
        val bundleC = bundles.find { it.contextId.value == "ctx-c" }
        assertTrue(bundleC != null, "Should have a bundle for ctx-c")
        assertEquals(3, bundleC!!.propositions.size)
        assertTrue(bundleC.propositions.all { it.contextId.value == "ctx-c" })
    }

    @Test
    fun `exportAllContexts returns empty list when store is empty`() {
        val store = InMemoryPropositionRepository()
        val bundles = exporter.exportAllContexts(store)

        assertEquals(0, bundles.size)
    }

    @Test
    fun `exportAllContexts with single context returns list with one bundle`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("Only fact 1", "ctx-solo"))
        store.save(proposition("Only fact 2", "ctx-solo"))

        val bundles = exporter.exportAllContexts(store)

        assertEquals(1, bundles.size)
        assertEquals("ctx-solo", bundles.first().contextId.value)
        assertEquals(2, bundles.first().propositions.size)
    }

    @Test
    fun `exportAllContexts bundles have correct contextId set on envelope`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("X", "ctx-x"))
        store.save(proposition("Y", "ctx-y"))

        val bundles = exporter.exportAllContexts(store)

        val ctxXBundle = bundles.find { it.contextId.value == "ctx-x" }!!
        val ctxYBundle = bundles.find { it.contextId.value == "ctx-y" }!!

        assertEquals("ctx-x", ctxXBundle.contextId.value)
        assertEquals("ctx-y", ctxYBundle.contextId.value)
        // Verify envelopes match their contents
        assertTrue(ctxXBundle.propositions.all { it.contextId.value == "ctx-x" })
        assertTrue(ctxYBundle.propositions.all { it.contextId.value == "ctx-y" })
    }

    // -------------------------------------------------------------------------
    // Round-trip with scoped export
    // -------------------------------------------------------------------------

    @Test
    fun `exportScoped bundle can be serialized and deserialized`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("Exportable fact", "ctx-rt"))

        val bundle = exporter.exportScoped("ctx-rt", store)
        val json = exporter.exportToString(bundle)

        // Deserialize and verify structure
        assertTrue(json.contains("ctx-rt"))
        assertTrue(json.contains("Exportable fact"))
    }

    @Test
    fun `exportAllContexts bundles preserve proposition data through serialization`() {
        val store = InMemoryPropositionRepository()
        val p1 = proposition("Preservable 1", "ctx-p", 0.95)
        val p2 = proposition("Preservable 2", "ctx-p", 0.85)
        store.save(p1)
        store.save(p2)

        val bundles = exporter.exportAllContexts(store)
        val bundle = bundles.first()

        // Verify propositions are intact before serialization
        assertEquals(2, bundle.propositions.size)
        assertTrue(bundle.propositions.any { it.text == "Preservable 1" && it.confidence == 0.95 })
        assertTrue(bundle.propositions.any { it.text == "Preservable 2" && it.confidence == 0.85 })

        // Verify serialization works
        val json = exporter.exportToString(bundle)
        assertTrue(json.isNotEmpty())
        assertTrue(json.contains("Preservable 1"))
    }

    // -------------------------------------------------------------------------
    // Adversarial: context boundary preservation
    // -------------------------------------------------------------------------

    @Test
    fun `exportScoped never leaks propositions from other contexts`() {
        val store = InMemoryPropositionRepository()
        // Create a scenario with many contexts where one is queried
        repeat(10) { i ->
            store.save(proposition("Fact $i", "ctx-$i"))
        }

        val bundle = exporter.exportScoped("ctx-5", store)

        assertEquals(1, bundle.propositions.size)
        assertEquals("ctx-5", bundle.propositions.first().contextId.value)
        assertEquals("Fact 5", bundle.propositions.first().text)
    }

    @Test
    fun `exportAllContexts maintains strict context boundaries for each bundle`() {
        val store = InMemoryPropositionRepository()
        // Create many propositions across contexts
        listOf("alpha", "beta", "gamma", "delta").forEach { ctx ->
            repeat(5) { i ->
                store.save(proposition("$ctx-$i", ctx))
            }
        }

        val bundles = exporter.exportAllContexts(store)

        assertEquals(4, bundles.size)
        // Verify each bundle contains ONLY propositions from its context
        for (bundle in bundles) {
            val expectedContext = bundle.contextId.value
            assertTrue(
                bundle.propositions.all { it.contextId.value == expectedContext },
                "Bundle for $expectedContext contains propositions from other contexts",
            )
            // Also verify text patterns match expected context
            assertTrue(
                bundle.propositions.all { it.text.startsWith("$expectedContext-") },
                "Bundle for $expectedContext contains unexpected proposition texts",
            )
        }
    }
}
