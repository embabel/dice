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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.temporal.TemporalMetadata
import java.time.Duration
import java.time.Instant

class PropositionTest {

    private val testContextId = ContextId("test-context")

    @Nested
    inner class ConstructionTests {

        @Test
        fun `proposition with valid confidence is created`() {
            val prop = Proposition(
                contextId = testContextId,
                text = "Alice is an expert in Kubernetes",
                mentions = emptyList(),
                confidence = 0.9,
            )

            assertEquals(0.9, prop.confidence)
            assertEquals(PropositionStatus.ACTIVE, prop.status)
        }

        @Test
        fun `proposition with confidence at boundaries is valid`() {
            val prop0 = Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 0.0)
            val prop1 = Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 1.0)

            assertEquals(0.0, prop0.confidence)
            assertEquals(1.0, prop1.confidence)
        }

        @Test
        fun `proposition with invalid confidence throws`() {
            assertThrows<IllegalArgumentException> {
                Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 1.5)
            }

            assertThrows<IllegalArgumentException> {
                Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = -0.1)
            }
        }

        @Test
        fun `importance defaults to 0_5`() {
            val prop = Proposition(
                contextId = testContextId,
                text = "Test",
                mentions = emptyList(),
                confidence = 0.9,
            )

            assertEquals(0.5, prop.importance)
        }

        @Test
        fun `proposition with valid importance is created`() {
            val prop = Proposition(
                contextId = testContextId,
                text = "Mary is about to have surgery",
                mentions = emptyList(),
                confidence = 0.7,
                importance = 1.0,
            )

            assertEquals(1.0, prop.importance)
        }

        @Test
        fun `proposition with invalid importance throws`() {
            assertThrows<IllegalArgumentException> {
                Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 0.5, importance = 1.5)
            }

            assertThrows<IllegalArgumentException> {
                Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 0.5, importance = -0.1)
            }
        }

        @Test
        fun `proposition with invalid decay throws`() {
            assertThrows<IllegalArgumentException> {
                Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 0.5, decay = 1.5)
            }
        }

        @Test
        fun `proposition generates unique id by default`() {
            val prop1 = Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 0.5)
            val prop2 = Proposition(contextId = testContextId, text = "Test", mentions = emptyList(), confidence = 0.5)

            assertNotEquals(prop1.id, prop2.id)
        }
    }

    @Nested
    inner class ReferencesEntitiesTests {

        private fun createMention(
            span: String,
            role: MentionRole,
            resolvedId: String? = null,
        ) = EntityMention(
            span = span,
            type = "Person",
            role = role,
            resolvedId = resolvedId,
        )

        @Test
        fun `isFullyResolved returns true when all mentions resolved`() {
            val mentions = listOf(
                createMention("Alice", MentionRole.SUBJECT, "alice-id"),
                createMention("Kubernetes", MentionRole.OBJECT, "k8s-id"),
            )

            val prop = Proposition(contextId = testContextId, text = "Alice knows Kubernetes", mentions = mentions, confidence = 0.9)

            assertTrue(prop.isFullyResolved())
        }

        @Test
        fun `isFullyResolved returns false when any mention unresolved`() {
            val mentions = listOf(
                createMention("Alice", MentionRole.SUBJECT, "alice-id"),
                createMention("Kubernetes", MentionRole.OBJECT, null),
            )

            val prop = Proposition(contextId = testContextId, text = "Alice knows Kubernetes", mentions = mentions, confidence = 0.9)

            assertFalse(prop.isFullyResolved())
        }

        @Test
        fun `isFullyResolved returns true for empty mentions`() {
            val prop = Proposition(contextId = testContextId, text = "General statement", mentions = emptyList(), confidence = 0.9)

            assertTrue(prop.isFullyResolved())
        }

        @Test
        fun `resolvedEntityIds returns only resolved ids`() {
            val mentions = listOf(
                createMention("Alice", MentionRole.SUBJECT, "alice-id"),
                createMention("Bob", MentionRole.OBJECT, null),
                createMention("Charlie", MentionRole.OTHER, "charlie-id"),
            )

            val prop = Proposition(contextId = testContextId, text = "Test", mentions = mentions, confidence = 0.9)

            val ids = prop.resolvedEntityIds()

            assertEquals(listOf("alice-id", "charlie-id"), ids)
        }

        @Test
        fun `subjectMention returns subject role mention`() {
            val subject = createMention("Alice", MentionRole.SUBJECT, "alice-id")
            val obj = createMention("Kubernetes", MentionRole.OBJECT, "k8s-id")

            val prop = Proposition(contextId = testContextId, text = "Alice knows Kubernetes", mentions = listOf(subject, obj), confidence = 0.9)

            assertEquals(subject, prop.subjectMention())
        }

        @Test
        fun `subjectMention returns null when no subject`() {
            val obj = createMention("Kubernetes", MentionRole.OBJECT, "k8s-id")

            val prop = Proposition(contextId = testContextId, text = "Test", mentions = listOf(obj), confidence = 0.9)

            assertNull(prop.subjectMention())
        }

        @Test
        fun `objectMention returns object role mention`() {
            val subject = createMention("Alice", MentionRole.SUBJECT, "alice-id")
            val obj = createMention("Kubernetes", MentionRole.OBJECT, "k8s-id")

            val prop = Proposition(contextId = testContextId, text = "Alice knows Kubernetes", mentions = listOf(subject, obj), confidence = 0.9)

            assertEquals(obj, prop.objectMention())
        }

        @Test
        fun `subjectId returns resolved id of subject`() {
            val mentions = listOf(
                createMention("Alice", MentionRole.SUBJECT, "alice-id"),
                createMention("Bob", MentionRole.OBJECT, "bob-id"),
            )

            val prop = Proposition(contextId = testContextId, text = "Test", mentions = mentions, confidence = 0.9)

            assertEquals("alice-id", prop.subjectId())
        }

        @Test
        fun `subjectId returns null when subject unresolved`() {
            val mentions = listOf(
                createMention("Alice", MentionRole.SUBJECT, null),
            )

            val prop = Proposition(contextId = testContextId, text = "Test", mentions = mentions, confidence = 0.9)

            assertNull(prop.subjectId())
        }

        @Test
        fun `objectId returns resolved id of object`() {
            val mentions = listOf(
                createMention("Alice", MentionRole.SUBJECT, "alice-id"),
                createMention("Bob", MentionRole.OBJECT, "bob-id"),
            )

            val prop = Proposition(contextId = testContextId, text = "Test", mentions = mentions, confidence = 0.9)

            assertEquals("bob-id", prop.objectId())
        }
    }

    @Nested
    inner class MutationTests {

        @Test
        fun `withResolvedMentions creates copy with new mentions`() {
            val original = Proposition(
                contextId = testContextId,
                text = "Alice knows Bob",
                mentions = listOf(
                    EntityMention(span = "Alice", type = "Person", role = MentionRole.SUBJECT, resolvedId = null),
                ),
                confidence = 0.9,
            )

            val resolved = original.withResolvedMentions(
                listOf(
                    EntityMention(span = "Alice", type = "Person", role = MentionRole.SUBJECT, resolvedId = "alice-id"),
                )
            )

            assertNull(original.mentions.first().resolvedId)
            assertEquals("alice-id", resolved.mentions.first().resolvedId)
            assertEquals(original.id, resolved.id)
            assertTrue(resolved.revised >= original.revised)
        }

        @Test
        fun `withStatus creates copy with new status`() {
            val original = Proposition(
                contextId = testContextId,
                text = "Test",
                mentions = emptyList(),
                confidence = 0.9,
            )

            val promoted = original.withStatus(PropositionStatus.PROMOTED)

            assertEquals(PropositionStatus.ACTIVE, original.status)
            assertEquals(PropositionStatus.PROMOTED, promoted.status)
        }

        @Test
        fun `withConfidence creates copy with new confidence`() {
            val original = Proposition(
                contextId = testContextId,
                text = "Test",
                mentions = emptyList(),
                confidence = 0.5,
            )

            val updated = original.withConfidence(0.9)

            assertEquals(0.5, original.confidence)
            assertEquals(0.9, updated.confidence)
        }

        @Test
        fun `withConfidence validates new confidence`() {
            val original = Proposition(
                contextId = testContextId,
                text = "Test",
                mentions = emptyList(),
                confidence = 0.5,
            )

            assertThrows<IllegalArgumentException> {
                original.withConfidence(1.5)
            }
        }

        @Test
        fun `withGrounding adds chunk ids`() {
            val original = Proposition(
                contextId = testContextId,
                text = "Test",
                mentions = emptyList(),
                confidence = 0.5,
                grounding = listOf("chunk-1"),
            )

            val updated = original.withGrounding(listOf("chunk-2", "chunk-3"))

            assertEquals(listOf("chunk-1"), original.grounding)
            assertEquals(listOf("chunk-1", "chunk-2", "chunk-3"), updated.grounding)
        }

        @Test
        fun `withGrounding deduplicates chunk ids`() {
            val original = Proposition(
                contextId = testContextId,
                text = "Test",
                mentions = emptyList(),
                confidence = 0.5,
                grounding = listOf("chunk-1", "chunk-2"),
            )

            val updated = original.withGrounding(listOf("chunk-2", "chunk-3"))

            assertEquals(3, updated.grounding.size)
            assertTrue(updated.grounding.containsAll(listOf("chunk-1", "chunk-2", "chunk-3")))
        }
    }

    @Nested
    inner class LifecycleStateTests {

        private val oldInstant = Instant.now().minus(Duration.ofDays(30))

        private fun agedProp(
            confidence: Double = 0.9,
            decay: Double = 0.5,
            status: PropositionStatus = PropositionStatus.ACTIVE,
        ) = Proposition(
            contextId = testContextId,
            text = "Aged proposition",
            mentions = emptyList(),
            confidence = confidence,
            decay = decay,
            status = status,
            contentRevised = oldInstant,
            metadataRevised = oldInstant,
        )

        // --- STALE + pinned ---

        @Test
        fun `STALE status is assignable via withStatus`() {
            val stale = agedProp().withStatus(PropositionStatus.STALE)
            assertEquals(PropositionStatus.STALE, stale.status)
        }

        @Test
        fun `fresh proposition is not pinned by default`() {
            val prop = Proposition(contextId = testContextId, text = "T", mentions = emptyList(), confidence = 0.9)
            assertFalse(prop.pinned)
        }

        @Test
        fun `withPinned sets pinned flag`() {
            val prop = agedProp()
            assertTrue(prop.withPinned(true).pinned)
            assertFalse(prop.withPinned(true).withPinned(false).pinned)
        }

        // --- deprecated revised alias ---

        @Test
        @Suppress("DEPRECATION")
        fun `deprecated revised alias equals contentRevised`() {
            val prop = agedProp()
            assertEquals(prop.contentRevised, prop.revised)
        }

        // --- content withers reset contentRevised, preserve metadataRevised ---

        @Test
        fun `withText resets contentRevised and preserves metadataRevised`() {
            val prop = agedProp()
            val updated = prop.withText("new text")
            assertTrue(updated.contentRevised.isAfter(prop.contentRevised))
            assertEquals(prop.metadataRevised, updated.metadataRevised)
        }

        @Test
        fun `withResolvedMentions resets contentRevised and preserves metadataRevised`() {
            val prop = agedProp()
            val updated = prop.withResolvedMentions(emptyList())
            assertTrue(updated.contentRevised.isAfter(prop.contentRevised))
            assertEquals(prop.metadataRevised, updated.metadataRevised)
        }

        @Test
        fun `withConfidence resets contentRevised and preserves metadataRevised`() {
            val prop = agedProp()
            val updated = prop.withConfidence(0.4)
            assertTrue(updated.contentRevised.isAfter(prop.contentRevised))
            assertEquals(prop.metadataRevised, updated.metadataRevised)
        }

        // --- metadata withers advance metadataRevised, preserve contentRevised (decay clock) ---

        @Test
        fun `withStatus advances metadataRevised and preserves contentRevised`() {
            val prop = agedProp()
            val updated = prop.withStatus(PropositionStatus.STALE)
            assertEquals(prop.contentRevised, updated.contentRevised)
            assertTrue(updated.metadataRevised.isAfter(prop.metadataRevised))
        }

        @Test
        fun `withGrounding advances metadataRevised and preserves contentRevised`() {
            val prop = agedProp()
            val updated = prop.withGrounding(listOf("chunk-x"))
            assertEquals(prop.contentRevised, updated.contentRevised)
            assertTrue(updated.metadataRevised.isAfter(prop.metadataRevised))
        }

        // --- explicit withTemporal + withProvenanceEntries decay preservation ---

        @Test
        fun `withTemporal preserves contentRevised and advances metadataRevised`() {
            val prop = agedProp()
            val temporal = TemporalMetadata(observedAt = Instant.now(), validFrom = Instant.now())
            val updated = prop.withTemporal(temporal)
            assertEquals(prop.contentRevised, updated.contentRevised)
            assertTrue(updated.metadataRevised.isAfter(prop.metadataRevised))
        }

        @Test
        fun `withProvenanceEntries preserves contentRevised and advances metadataRevised`() {
            val prop = agedProp()
            val entry = ProvenanceEntry(
                locator = com.embabel.dice.provenance.UriLocator("https://example.com/doc"),
                chunkId = "c1",
            )
            val updated = prop.withProvenanceEntries(listOf(entry))
            assertEquals(prop.contentRevised, updated.contentRevised)
            assertTrue(updated.metadataRevised.isAfter(prop.metadataRevised))
        }

        // --- lastTouched tracks any update (metadata touches stay queryable) ---

        @Test
        fun `lastTouched advances on a metadata-only touch while contentRevised is preserved`() {
            val prop = agedProp()
            val touched = prop.withStatus(PropositionStatus.STALE)
            // Decay anchor unchanged, but the proposition WAS just touched.
            assertEquals(prop.contentRevised, touched.contentRevised)
            assertTrue(touched.metadataRevised.isAfter(prop.metadataRevised))
            assertEquals(touched.metadataRevised, touched.lastTouched)
            assertTrue(touched.lastTouched.isAfter(prop.lastTouched))
        }

        @Test
        fun `lastTouched is the later of contentRevised and metadataRevised`() {
            val prop = agedProp()
            assertEquals(maxOf(prop.contentRevised, prop.metadataRevised), prop.lastTouched)
            // A content touch advances both the decay anchor and lastTouched.
            val contentTouched = prop.withText("revised text")
            assertEquals(contentTouched.contentRevised, contentTouched.lastTouched)
            assertTrue(contentTouched.lastTouched.isAfter(prop.lastTouched))
        }

        // --- decay anchors on contentRevised, not metadataRevised ---

        @Test
        fun `effectiveConfidenceAt anchors age on contentRevised`() {
            val prop = agedProp(confidence = 0.9, decay = 0.5)
            val before = prop.effectiveConfidenceAt(Instant.now())
            // A metadata wither (status change) must NOT regain confidence:
            // contentRevised (decay clock) is preserved.
            val afterMeta = prop.withStatus(PropositionStatus.STALE).effectiveConfidenceAt(Instant.now())
            assertEquals(before, afterMeta, 1e-9)
            // The aged proposition has decayed substantially below its raw confidence.
            assertTrue(before < 0.9)
        }

        // --- create() seeds both timestamps from legacy revised ---

        @Test
        fun `create with only legacy revised seeds both content and metadata timestamps`() {
            val ts = Instant.now().minus(Duration.ofDays(5))
            val prop = Proposition.create(
                id = "p1",
                contextIdValue = "ctx",
                text = "T",
                mentions = emptyList(),
                confidence = 0.8,
                decay = 0.1,
                reasoning = null,
                grounding = emptyList(),
                created = ts,
                revised = ts,
                status = PropositionStatus.ACTIVE,
            )
            assertEquals(ts, prop.contentRevised)
            assertEquals(ts, prop.metadataRevised)
            assertFalse(prop.pinned)
        }

        @Test
        fun `create honors explicit content and metadata overrides`() {
            val created = Instant.now().minus(Duration.ofDays(10))
            val content = Instant.now().minus(Duration.ofDays(3))
            val metadata = Instant.now().minus(Duration.ofDays(1))
            val prop = Proposition.create(
                id = "p2",
                contextIdValue = "ctx",
                text = "T",
                mentions = emptyList(),
                confidence = 0.8,
                decay = 0.1,
                reasoning = null,
                grounding = emptyList(),
                created = created,
                revised = created,
                status = PropositionStatus.ACTIVE,
                contentRevised = content,
                metadataRevised = metadata,
                pinned = true,
            )
            assertEquals(content, prop.contentRevised)
            assertEquals(metadata, prop.metadataRevised)
            assertTrue(prop.pinned)
        }
    }

    @Nested
    inner class InfoStringTests {

        @Test
        fun `infoString includes text and mentions`() {
            val prop = Proposition(
                contextId = testContextId,
                text = "Alice knows Bob",
                mentions = listOf(
                    EntityMention(span = "Alice", type = "Person", resolvedId = "alice-id", role = MentionRole.SUBJECT),
                ),
                confidence = 0.9,
            )

            val info = prop.infoString(verbose = false)

            assertTrue(info.contains("Alice knows Bob"))
        }

        @Test
        fun `verbose infoString includes more details`() {
            val prop = Proposition(
                contextId = testContextId,
                text = "Alice knows Bob",
                mentions = emptyList(),
                confidence = 0.9,
                status = PropositionStatus.PROMOTED,
            )

            val info = prop.infoString(verbose = true)

            assertTrue(info.contains("conf=0.9"))
            assertTrue(info.contains("PROMOTED"))
        }

        @Test
        fun `verbose infoString includes importance`() {
            val prop = Proposition(
                contextId = testContextId,
                text = "Mary is about to have surgery",
                mentions = emptyList(),
                confidence = 0.7,
                importance = 0.95,
            )

            val info = prop.infoString(verbose = true)

            assertTrue(info.contains("importance=0.95"))
        }
    }
}

class EntityMentionTest {

    @Test
    fun `entity mention stores all properties`() {
        val mention = EntityMention(
            span = "Alice",
            type = "Person",
            resolvedId = "alice-123",
            role = MentionRole.SUBJECT,
            hints = mapOf("source" to "extraction"),
        )

        assertEquals("Alice", mention.span)
        assertEquals("Person", mention.type)
        assertEquals(MentionRole.SUBJECT, mention.role)
        assertEquals("alice-123", mention.resolvedId)
        assertEquals(mapOf("source" to "extraction"), mention.hints)
    }

    @Test
    fun `entity mention with default hints`() {
        val mention = EntityMention(
            span = "Bob",
            type = "Person",
            resolvedId = null,
            role = MentionRole.OBJECT,
        )

        assertTrue(mention.hints.isEmpty())
        assertNull(mention.resolvedId)
    }

    @Test
    fun `withResolvedId creates copy with id`() {
        val original = EntityMention(
            span = "Alice",
            type = "Person",
            resolvedId = null,
            role = MentionRole.SUBJECT,
        )

        val resolved = original.withResolvedId("alice-id")

        assertNull(original.resolvedId)
        assertEquals("alice-id", resolved.resolvedId)
        assertEquals(original.span, resolved.span)
    }

    @Test
    fun `infoString formats mention correctly`() {
        val mention = EntityMention(
            span = "Alice",
            type = "Person",
            resolvedId = "alice-id",
            role = MentionRole.SUBJECT,
        )

        val info = mention.infoString()

        assertTrue(info.contains("Alice"))
        assertTrue(info.contains("Person"))
    }
}
