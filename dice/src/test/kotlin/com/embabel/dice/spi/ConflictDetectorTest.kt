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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

private val testContextId = ContextId("test-context")

class ConflictDetectorTest {

    @Test
    fun `always detector returns contradiction`() {
        val incoming = createProposition("Alice works at Acme")
        val existing = createProposition("Alice works at Globex")

        val result = AlwaysContradictionDetector.detect(incoming, existing)

        assertEquals(ConflictType.Contradiction, result)
    }

    @Test
    fun `temporal detector returns world progression for evolving predicate when incoming is newer`() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")

        val existing = createProposition(
            "Alice works at Globex",
            predicate = "employer",
            observedAt = older,
        )
        val incoming = createProposition(
            "Alice works at Acme",
            predicate = "employer",
            observedAt = newer,
        )

        val result = TemporalConflictDetector().detect(incoming, existing)

        assertEquals(ConflictType.WorldProgression, result)
    }

    @Test
    fun `temporal detector returns contradiction for stable predicate even when incoming is newer`() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")

        val existing = createProposition(
            "Alice was born in Paris",
            predicate = "birthplace",
            observedAt = older,
        )
        val incoming = createProposition(
            "Alice was born in London",
            predicate = "birthplace",
            observedAt = newer,
        )

        val result = TemporalConflictDetector().detect(incoming, existing)

        assertEquals(ConflictType.Contradiction, result)
    }

    @Test
    fun `temporal detector falls back to contradiction when predicate metadata is absent`() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")

        val existing = createProposition("Alice works at Globex", observedAt = older)
        val incoming = createProposition("Alice works at Acme", observedAt = newer)

        val result = TemporalConflictDetector().detect(incoming, existing)

        assertEquals(ConflictType.Contradiction, result)
    }

    @Test
    fun `temporal detector returns contradiction for evolving predicate when incoming is not newer`() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")

        // Incoming is OLDER than existing, so this is not world progression.
        val existing = createProposition(
            "Alice works at Acme",
            predicate = "employer",
            observedAt = newer,
        )
        val incoming = createProposition(
            "Alice works at Globex",
            predicate = "employer",
            observedAt = older,
        )

        val result = TemporalConflictDetector().detect(incoming, existing)

        assertEquals(ConflictType.Contradiction, result)
    }

    @Test
    fun `temporal detector reads evolving predicate from existing when incoming lacks one`() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")

        // The stored/existing proposition carries the enriched predicate; the freshly
        // extracted incoming one does not yet have a cached predicate.
        val existing = createProposition(
            "Alice works at Globex",
            predicate = "employer",
            observedAt = older,
        )
        val incoming = createProposition("Alice works at Acme", observedAt = newer)

        val result = TemporalConflictDetector().detect(incoming, existing)

        assertEquals(ConflictType.WorldProgression, result)
    }

    @Test
    fun `temporal detector treats equal timestamps as world progression for evolving predicate`() {
        val sameInstant = Instant.parse("2024-01-01T00:00:00Z")

        val existing = createProposition(
            "Alice works at Globex",
            predicate = "employer",
            observedAt = sameInstant,
        )
        val incoming = createProposition(
            "Alice works at Acme",
            predicate = "employer",
            observedAt = sameInstant,
        )

        val result = TemporalConflictDetector().detect(incoming, existing)

        // Equal timestamps are deliberately NOT a temporal contradiction.
        assertEquals(ConflictType.WorldProgression, result)
    }

    private fun createProposition(
        text: String,
        predicate: String? = null,
        observedAt: Instant? = null,
    ): Proposition {
        val metadata: Map<String, Any> =
            if (predicate != null) mapOf(Proposition.PREDICATE to predicate) else emptyMap()
        val temporal = observedAt?.let {
            TemporalMetadata(observedAt = it, validFrom = it)
        }
        return Proposition(
            contextId = testContextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.8,
            metadata = metadata,
            temporal = temporal,
        )
    }
}
