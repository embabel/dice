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

import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.RetiredProposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Round-trips the collector trace types through bind-map → property-map — the part that
 * flattens enums and rebuilds them, without needing a database.
 */
class CollectorTraceRowMapperTest {

    @Test
    fun `retired proposition round-trips through the row mapper with valid priorStatus`() {
        val retired = RetiredProposition(
            propositionId = "prop-1",
            priorStatus = PropositionStatus.ACTIVE,
            foldedGrounding = listOf("g1", "g2"),
            foldedProvenanceRefs = listOf("prov-1"),
            foldedSourceIds = listOf("src-1", "src-2"),
            foldedProvenanceEvidenceKeys = listOf("dice-provenance:v1:evidence-1"),
        )

        val bindMap = CollectorDecisionRowMapper.retiredBindMap("run-1", mockDecision(), retired)
        val roundTripped = CollectorDecisionRowMapper.retiredFromRow(bindMap)

        assertEquals("prop-1", roundTripped.propositionId)
        assertEquals(PropositionStatus.ACTIVE, roundTripped.priorStatus)
        assertEquals(listOf("g1", "g2"), roundTripped.foldedGrounding)
        assertEquals(listOf("prov-1"), roundTripped.foldedProvenanceRefs)
        assertEquals(listOf("src-1", "src-2"), roundTripped.foldedSourceIds)
        assertEquals(listOf("dice-provenance:v1:evidence-1"), roundTripped.foldedProvenanceEvidenceKeys)
    }

    @Test
    fun `a retired row written before evidence keys existed stays readable`() {
        val legacyRow = mapOf(
            "propositionId" to "prop-1",
            "priorStatus" to "ACTIVE",
            "foldedGrounding" to emptyList<String>(),
            "foldedProvenanceRefs" to listOf("uri:https://example.com/source"),
            "foldedSourceIds" to emptyList<String>(),
        )

        val retired = CollectorDecisionRowMapper.retiredFromRow(legacyRow)

        assertEquals(listOf("uri:https://example.com/source"), retired.foldedProvenanceRefs)
        assertEquals(emptyList<String>(), retired.foldedProvenanceEvidenceKeys)
    }

    @Test
    fun `an unparseable priorStatus throws IllegalArgumentException instead of defaulting to ACTIVE`() {
        val badRow = mapOf(
            "propositionId" to "prop-1",
            "priorStatus" to "INVALID_STATUS",
            "foldedGrounding" to emptyList<String>(),
            "foldedProvenanceRefs" to emptyList<String>(),
            "foldedSourceIds" to emptyList<String>(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CollectorDecisionRowMapper.retiredFromRow(badRow)
        }
    }

    @Test
    fun `all valid PropositionStatus values round-trip correctly`() {
        PropositionStatus.entries.forEach { status ->
            val retired = RetiredProposition(
                propositionId = "prop-$status",
                priorStatus = status,
                foldedGrounding = emptyList(),
                foldedProvenanceRefs = emptyList(),
                foldedSourceIds = emptyList(),
            )

            val bindMap = CollectorDecisionRowMapper.retiredBindMap("run-1", mockDecision(), retired)
            val roundTripped = CollectorDecisionRowMapper.retiredFromRow(bindMap)

            assertEquals(status, roundTripped.priorStatus)
        }
    }

    private fun mockDecision() = com.embabel.dice.spi.CollectorDecision(
        runId = "run-1",
        componentId = "comp-1",
        survivorId = "survivor-1",
        action = "test-action",
        retired = emptyList(),
    )
}
