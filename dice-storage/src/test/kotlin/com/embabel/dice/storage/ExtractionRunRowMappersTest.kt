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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.extraction.ExtractionInvocationId
import com.embabel.dice.proposition.extraction.ExtractionInvocationRecord
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunCounts
import com.embabel.dice.proposition.extraction.ExtractionRunFingerprint
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Proves the header and invocation fingerprints are functions of the record's content alone — not
 * of a JSON serializer's own behaviour — which is the defect a reviewer found in the raw
 * `objectMapper.writeValueAsString` version: two writes of the same logical state could digest
 * differently if a serializer or a `Map` implementation changed underneath them, turning a correct
 * retry into a rejected conflict.
 */
class ExtractionRunRowMappersTest {

    private val startedAt: Instant = Instant.parse("2026-08-31T10:15:30Z")

    private fun run(counts: Int = 0) = ExtractionRun(
        contextId = ContextId("tenant"),
        lineage = ExtractionRunLineage.root(ExtractionRunRef("run-1")),
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
    )

    @Test
    fun `headerFingerprint does not move when the bind map is rebuilt with a different key order`() {
        val header = ExtractionRunRowMapper.headerBindMap(run())
        // Same entries, a different iteration order — what a HashMap vs a LinkedHashMap, or a
        // future refactor of headerBindMap's own field-adding order, would produce for equal data.
        val reordered = LinkedHashMap(header.entries.sortedBy { it.key }.associate { it.key to it.value })

        assertEquals(
            ExtractionRunRowMapper.headerFingerprint(header),
            ExtractionRunRowMapper.headerFingerprint(reordered),
        )
    }

    @Test
    fun `headerFingerprint is unaffected by the Map implementation carrying the same entries`() {
        val header = ExtractionRunRowMapper.headerBindMap(run())
        val asHashMap = HashMap(header)
        val asTreeMap = header.entries.associateTo(sortedMapOf()) { it.key to it.value }

        val baseline = ExtractionRunRowMapper.headerFingerprint(header)
        assertEquals(baseline, ExtractionRunRowMapper.headerFingerprint(asHashMap))
        assertEquals(baseline, ExtractionRunRowMapper.headerFingerprint(asTreeMap))
    }

    @Test
    fun `headerFingerprint changes when a header field genuinely changes`() {
        val a = ExtractionRunRowMapper.headerFingerprint(ExtractionRunRowMapper.headerBindMap(run()))
        val changed = run().let { base ->
            ExtractionRun(
                contextId = base.contextId,
                lineage = base.lineage,
                status = base.status,
                startedAt = base.startedAt,
                counts = ExtractionRunCounts(propositionsPersisted = 7),
            )
        }
        val b = ExtractionRunRowMapper.headerFingerprint(ExtractionRunRowMapper.headerBindMap(changed))

        assertNotEquals(a, b)
    }

    @Test
    fun `invocation fingerprint does not move when the bind map is rebuilt with a different key order`() {
        val record = ExtractionInvocationRecord(
            id = ExtractionInvocationId.planned(0),
            configuredService = "service-a",
        )
        val bound = ExtractionInvocationRowMapper.bindMap(record)
        val reordered = LinkedHashMap(bound.entries.sortedBy { it.key }.associate { it.key to it.value })

        assertEquals(
            invocationFingerprintOf(bound),
            invocationFingerprintOf(reordered),
        )
    }

    @Test
    fun `invocation fingerprint changes when the record genuinely changes`() {
        val a = ExtractionInvocationRowMapper.fingerprint(
            ExtractionInvocationRecord(id = ExtractionInvocationId.planned(0), configuredService = "service-a"),
        )
        val b = ExtractionInvocationRowMapper.fingerprint(
            ExtractionInvocationRecord(id = ExtractionInvocationId.planned(0), configuredService = "service-b"),
        )

        assertNotEquals(a, b)
    }

    /** Runs a bind map through the same digest [ExtractionInvocationRowMapper.fingerprint] uses. */
    private fun invocationFingerprintOf(bound: Map<String, Any?>): String =
        ExtractionRunFingerprint.ofFields(
            ExtractionRunFingerprint.INVOCATION_VERSION,
            bound.mapValues { (_, value) -> value?.toString() },
        )
}
