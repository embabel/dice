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
package com.embabel.dice.storage.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DrivineObservedSchemaSource] against a fake [org.drivine.manager.PersistenceManager]
 * that returns a canned row set per Cypher statement -- no real Neo4j needed to prove the bookkeeping
 * label exclusion.
 */
class DrivineObservedSchemaSourceTest {

    @Test
    fun `excludes dice's own bookkeeping labels from observed entity types`() {
        val domainLabels = setOf("Person", "Company", "GhostType")
        val persistenceManager = FakePersistenceManager(
            labels = domainLabels + DICE_BOOKKEEPING_LABELS,
            relationshipTypes = setOf("WORKS_AT"),
        )
        val source = DrivineObservedSchemaSource(persistenceManager)

        val observed = source.observe()

        assertThat(observed.entityTypeNames).isEqualTo(domainLabels)
        DICE_BOOKKEEPING_LABELS.forEach { bookkeepingLabel ->
            assertThat(observed.entityTypeNames).doesNotContain(bookkeepingLabel)
        }
    }

    @Test
    fun `relationship types are observed as-is, with no bookkeeping exclusion applied`() {
        val persistenceManager = FakePersistenceManager(
            labels = emptySet(),
            relationshipTypes = setOf("WORKS_AT", "LOCATED_IN"),
        )
        val source = DrivineObservedSchemaSource(persistenceManager)

        val observed = source.observe()

        assertThat(observed.relationshipTypeNames).isEqualTo(setOf("WORKS_AT", "LOCATED_IN"))
    }

    @Test
    fun `an empty graph observes empty entity and relationship sets`() {
        val persistenceManager = FakePersistenceManager(labels = emptySet(), relationshipTypes = emptySet())
        val source = DrivineObservedSchemaSource(persistenceManager)

        val observed = source.observe()

        assertThat(observed.entityTypeNames).isEmpty()
        assertThat(observed.relationshipTypeNames).isEmpty()
    }

    /** Returns [labels] for a `db.labels()` call and [relationshipTypes] for `db.relationshipTypes()`. */
    private class FakePersistenceManager(
        private val labels: Set<String>,
        private val relationshipTypes: Set<String>,
    ) : NoOpPersistenceManager() {

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
            val text = spec.statement!!.text
            val rows = when {
                text.contains("db.labels") -> labels.toList()
                text.contains("db.relationshipTypes") -> relationshipTypes.toList()
                else -> error("Unexpected statement in test fake: $text")
            }
            return rows as List<T>
        }
    }
}
