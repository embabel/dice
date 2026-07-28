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
package com.embabel.dice.taxonomy

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.embabel.dice.proposition.Proposition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TaxonomyDecorationPassTest {

    private val contextId = ContextId("test")

    @Test
    fun `decorates an unmarked proposition with central taxonomy metadata`() {
        val taxonomy = taxonomy()
        val originalRevision = Instant.parse("2025-01-01T00:00:00Z")
        val proposition = proposition(
            text = "A physics observation",
            metadata = mapOf("existing" to "value"),
            contentRevised = originalRevision,
            metadataRevised = originalRevision,
        )
        val pass = TaxonomyDecorationPass(
            taxonomy = taxonomy,
            classifier = TaxonomyClassifier { _, _ -> "science" },
        )

        val result = pass.run(contextId, listOf(proposition))

        assertThat(result).isInstanceOfSatisfying(ConsolidationPassResult.Changed::class.java) { changed ->
            assertThat(changed.passName).isEqualTo("taxonomy")
            assertThat(changed.propositionsToSave).hasSize(1)
            val decorated = changed.propositionsToSave.single()
            assertThat(decorated.metadata).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "existing" to "value",
                    DiceMetadataKeys.TAXONOMY_NODE to "science",
                    DiceMetadataKeys.TAXONOMY_VERSION to taxonomy.version,
                ),
            )
            assertThat(decorated.contentRevised).isEqualTo(originalRevision)
            assertThat(decorated.metadataRevised).isAfter(originalRevision)
            assertThat(changed.skipped).isZero()
        }
    }

    @Test
    fun `skips current abstained and unknown classifications while decorating valid ones`() {
        val taxonomy = taxonomy()
        val valid = proposition("valid")
        val current = proposition(
            text = "current",
            metadata = mapOf(
                DiceMetadataKeys.TAXONOMY_NODE to "science",
                DiceMetadataKeys.TAXONOMY_VERSION to taxonomy.version,
            ),
        )
        val abstained = proposition("abstained")
        val unknown = proposition("unknown")
        val pass = TaxonomyDecorationPass(
            taxonomy = taxonomy,
            classifier = TaxonomyClassifier { proposition, _ ->
                when (proposition.text) {
                    "valid" -> "science"
                    "current" -> error("An already-current proposition must not be classified")
                    "unknown" -> "missing"
                    else -> null
                }
            },
        )

        val result = pass.run(contextId, listOf(valid, current, abstained, unknown))

        assertThat(result).isInstanceOfSatisfying(ConsolidationPassResult.Changed::class.java) { changed ->
            assertThat(changed.propositionsToSave).extracting<String> {
                it.metadata[DiceMetadataKeys.TAXONOMY_NODE] as String
            }.containsExactly("science")
            assertThat(changed.skipped).isEqualTo(3)
        }
        assertThat(current.metadata).containsEntry(DiceMetadataKeys.TAXONOMY_VERSION, taxonomy.version)
        assertThat(abstained.metadata).doesNotContainKeys(
            DiceMetadataKeys.TAXONOMY_NODE,
            DiceMetadataKeys.TAXONOMY_VERSION,
        )
        assertThat(unknown.metadata).doesNotContainKeys(
            DiceMetadataKeys.TAXONOMY_NODE,
            DiceMetadataKeys.TAXONOMY_VERSION,
        )
    }

    @Test
    fun `redecorates when the taxonomy version changes`() {
        val taxonomy = taxonomy(version = "2")
        val proposition = proposition(
            text = "version bump",
            metadata = mapOf(
                DiceMetadataKeys.TAXONOMY_NODE to "old-node",
                DiceMetadataKeys.TAXONOMY_VERSION to "1",
            ),
        )
        val pass = TaxonomyDecorationPass(
            taxonomy = taxonomy,
            classifier = TaxonomyClassifier { _, _ -> "science" },
        )

        val result = pass.run(contextId, listOf(proposition))

        assertThat(result).isInstanceOfSatisfying(ConsolidationPassResult.Changed::class.java) { changed ->
            assertThat(changed.propositionsToSave).hasSize(1)
            assertThat(changed.propositionsToSave.single().metadata)
                .containsEntry(DiceMetadataKeys.TAXONOMY_NODE, "science")
                .containsEntry(DiceMetadataKeys.TAXONOMY_VERSION, "2")
            assertThat(changed.skipped).isZero()
        }
    }

    @Test
    fun `returns no-op when every proposition is skipped`() {
        val taxonomy = taxonomy()
        val current = proposition(
            text = "current",
            metadata = mapOf(
                DiceMetadataKeys.TAXONOMY_NODE to "science",
                DiceMetadataKeys.TAXONOMY_VERSION to taxonomy.version,
            ),
        )
        val pass = TaxonomyDecorationPass(
            taxonomy = taxonomy,
            classifier = TaxonomyClassifier { _, _ -> "missing" },
        )

        val result = pass.run(contextId, listOf(current, proposition("unknown")))

        assertThat(result).isEqualTo(
            ConsolidationPassResult.NoOp(
                passName = "taxonomy",
                reason = "No taxonomy decorations changed",
            ),
        )
    }

    private fun taxonomy(version: String = "1") = Taxonomy(
        nodes = listOf(
            TaxonomyNode(id = "science", label = "Science"),
        ),
        version = version,
    )

    private fun proposition(
        text: String,
        metadata: Map<String, Any> = emptyMap(),
        contentRevised: Instant = Instant.now(),
        metadataRevised: Instant = contentRevised,
    ) = Proposition(
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = 1.0,
        metadata = metadata,
        contentRevised = contentRevised,
        metadataRevised = metadataRevised,
    )
}
