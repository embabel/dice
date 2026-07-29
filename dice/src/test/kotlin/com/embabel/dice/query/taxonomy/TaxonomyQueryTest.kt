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
package com.embabel.dice.query.taxonomy

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.taxonomy.Taxonomy
import com.embabel.dice.taxonomy.TaxonomyNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaxonomyQueryTest {

    private val contextId = ContextId("taxonomy-query-test")
    private val otherContextId = ContextId("other-taxonomy-query-test")

    private val taxonomy = Taxonomy(
        listOf(
            TaxonomyNode(id = "animals", label = "Animals"),
            TaxonomyNode(id = "mammals", label = "Mammals", parentId = "animals"),
            TaxonomyNode(id = "cats", label = "Cats", parentId = "mammals"),
            TaxonomyNode(id = "siamese", label = "Siamese", parentId = "cats"),
            TaxonomyNode(id = "dogs", label = "Dogs", parentId = "mammals"),
            TaxonomyNode(id = "beagle", label = "Beagle", parentId = "dogs"),
            TaxonomyNode(id = "birds", label = "Birds", parentId = "animals"),
            TaxonomyNode(id = "sparrow", label = "Sparrow", parentId = "birds"),
        ),
    )

    @Test
    fun `categories are derived from active context-scoped propositions`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("cat-1", "cat", "siamese"))
        store.save(proposition("cat-2", "cat", "cats"))
        store.save(proposition("cat-duplicate", "cat", "cats"))
        store.save(proposition("inactive", "cat", "mammals", status = PropositionStatus.SUPERSEDED))
        store.save(proposition("bogus", "cat", "not-in-taxonomy"))
        store.save(proposition("other-context", "cat", "animals", contextId = otherContextId))

        val categories = TaxonomyQuery(store, taxonomy, contextId).categoriesOf("cat")

        assertEquals(setOf("siamese", "cats"), categories)
    }

    @Test
    fun `entities sharing an ancestor are bounded by taxonomy levels`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("cat", "cat", "siamese"))
        store.save(proposition("dog", "dog", "beagle"))
        store.save(proposition("bird", "bird", "sparrow"))

        val query = TaxonomyQuery(store, taxonomy, contextId)

        assertEquals(setOf("dog"), query.entitiesSharingAncestor("cat", maxLevels = 2))
        assertTrue(query.entitiesSharingAncestor("cat", maxLevels = 1).isEmpty())
        assertEquals(setOf("dog", "bird"), query.entitiesSharingAncestor("cat", maxLevels = 3))
    }

    @Test
    fun `unknown entities return empty sets`() {
        val query = TaxonomyQuery(InMemoryPropositionRepository(), taxonomy, contextId)

        assertTrue(query.categoriesOf("unknown").isEmpty())
        assertTrue(query.entitiesSharingAncestor("unknown", maxLevels = 2).isEmpty())
    }

    private fun proposition(
        id: String,
        entityId: String,
        taxonomyNodeId: String,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        contextId: ContextId = this.contextId,
    ): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "$entityId is a $taxonomyNodeId",
            mentions = listOf(
                EntityMention(
                    span = entityId,
                    type = "Entity",
                    resolvedId = entityId,
                    role = MentionRole.SUBJECT,
                ),
            ),
            confidence = 0.9,
            status = status,
            metadata = mapOf(DiceMetadataKeys.TAXONOMY_NODE to taxonomyNodeId),
        )
}
