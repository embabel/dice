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
package com.embabel.dice.report

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.taxonomy.Taxonomy
import com.embabel.dice.taxonomy.TaxonomyNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OntologicalSemanticLinkDiscovererTest {

    private val contextId = ContextId("test")
    private val taxonomy = Taxonomy(
        listOf(
            TaxonomyNode(id = "root", label = "Root"),
            TaxonomyNode(id = "vendors", label = "Vendors", parentId = "root"),
            TaxonomyNode(id = "robotics", label = "Robotics", parentId = "vendors"),
            TaxonomyNode(id = "biotech", label = "Biotech", parentId = "vendors"),
        ),
    )

    @Test
    fun `emits a candidate for an adjacency-invisible shared ancestor`() {
        val roboticsEvidence = proposition("robotics-evidence", "Zeta", categoryId = "robotics")
        val biotechEvidence = proposition("biotech-evidence", "Alpha", categoryId = "biotech")

        val link = OntologicalSemanticLinkDiscoverer(taxonomy)
            .discover(listOf(roboticsEvidence, biotechEvidence))
            .single()

        assertEquals("Alpha", link.sourceEntityId)
        assertEquals("Zeta", link.targetEntityId)
        assertEquals(LinkKind.INFERRED, link.kind)
        assertEquals(ReviewStatus.CANDIDATE, link.reviewStatus)
        assertEquals(emptyList<String>(), link.connectingEntityIds)
        assertEquals(listOf("biotech-evidence", "robotics-evidence"), link.sourcePropositionIds)
        assertEquals(0.5, link.confidence)
        assertEquals(
            "shared taxonomy ancestor 'Vendors' (Alpha via biotech, Zeta via robotics)",
            link.rationale,
        )
    }

    @Test
    fun `does not emit a directly co-mentioned pair`() {
        val roboticsEvidence = proposition("robotics-evidence", "A", categoryId = "robotics")
        val biotechEvidence = proposition("biotech-evidence", "B", categoryId = "biotech")
        val direct = proposition("direct", "A", "B")

        val links = OntologicalSemanticLinkDiscoverer(taxonomy)
            .discover(listOf(roboticsEvidence, biotechEvidence, direct))

        assertTrue(links.isEmpty())
    }

    @Test
    fun `does not emit a pair sharing an entity neighbour`() {
        val roboticsEvidence = proposition("robotics-evidence", "A", categoryId = "robotics")
        val biotechEvidence = proposition("biotech-evidence", "B", categoryId = "biotech")
        val aToX = proposition("a-to-x", "A", "X")
        val xToB = proposition("x-to-b", "X", "B")

        val links = OntologicalSemanticLinkDiscoverer(taxonomy)
            .discover(listOf(roboticsEvidence, biotechEvidence, aToX, xToB))

        assertTrue(links.isEmpty())
    }

    @Test
    fun `ignores stale category evidence`() {
        val roboticsEvidence = proposition("robotics-evidence", "A", categoryId = "robotics")
        val staleBiotechEvidence = proposition(
            "biotech-evidence",
            "B",
            categoryId = "biotech",
            status = PropositionStatus.STALE,
        )

        val links = OntologicalSemanticLinkDiscoverer(taxonomy)
            .discover(listOf(roboticsEvidence, staleBiotechEvidence))

        assertTrue(links.isEmpty())
    }

    @Test
    fun `stale adjacency does not suppress a candidate grounded in active evidence`() {
        val roboticsEvidence = proposition("robotics-evidence", "A", categoryId = "robotics")
        val biotechEvidence = proposition("biotech-evidence", "B", categoryId = "biotech")
        val staleDirect = proposition(
            "stale-direct",
            "A",
            "B",
            status = PropositionStatus.STALE,
        )

        val links = OntologicalSemanticLinkDiscoverer(taxonomy)
            .discover(listOf(roboticsEvidence, biotechEvidence, staleDirect))

        assertEquals(1, links.size)
        assertEquals(listOf("biotech-evidence", "robotics-evidence"), links.single().sourcePropositionIds)
    }

    @Test
    fun `does not emit when the shared ancestor is beyond the configured bound`() {
        val roboticsEvidence = proposition("robotics-evidence", "A", categoryId = "robotics")
        val biotechEvidence = proposition("biotech-evidence", "B", categoryId = "biotech")

        val links = OntologicalSemanticLinkDiscoverer(taxonomy, maxAncestorLevels = 0)
            .discover(listOf(roboticsEvidence, biotechEvidence))

        assertTrue(links.isEmpty())
    }

    private fun proposition(
        id: String,
        vararg entityIds: String,
        categoryId: String? = null,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition {
        val proposition = Proposition(
            id = id,
            contextId = contextId,
            text = entityIds.joinToString(" relates to "),
            mentions = entityIds.map {
                EntityMention(
                    span = it,
                    type = "Entity",
                    resolvedId = it,
                    role = MentionRole.SUBJECT,
                )
            },
            confidence = 0.9,
            status = status,
        )
        return if (categoryId == null) {
            proposition
        } else {
            proposition.withMetadataValue(DiceMetadataKeys.TAXONOMY_NODE, categoryId)
        }
    }
}
