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

import com.embabel.agent.core.ContextId
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

    // ---- Context-scoped observation ----

    @Test
    fun `a scoped observation queries distinct mention types for that context, not db-labels`() {
        val recording = RecordingPersistenceManager(mentionTypes = setOf("Person", "GhostType"))
        val source = DrivineObservedSchemaSource(recording)

        val observed = source.observe(ContextId("tenant-a"))

        assertThat(observed.entityTypeNames).isEqualTo(setOf("Person", "GhostType"))
        val mentionCall = recording.calls.single { it.statement.contains("HAS_MENTION") }
        assertThat(mentionCall.statement).doesNotContain("db.labels")
        assertThat(mentionCall.params["contextId"]).isEqualTo("tenant-a")
    }

    @Test
    fun `a scoped observation joins relationship types through sourcePropositions, parameterized only on contextId`() {
        val recording = RecordingPersistenceManager(
            mentionTypes = setOf("Person"),
            relationshipTypes = setOf("WORKS_AT", "LOCATED_IN"),
        )
        val source = DrivineObservedSchemaSource(recording)

        val observed = source.observe(ContextId("tenant-a"))

        assertThat(observed.relationshipTypeNames).isEqualTo(setOf("WORKS_AT", "LOCATED_IN"))
        val relCall = recording.calls.single { it.statement.contains("sourcePropositions") }
        assertThat(relCall.statement).contains("Proposition")
        assertThat(relCall.statement).contains("type(r)")
        // Parameterized only on contextId -- no interpolation of anything else into the statement.
        assertThat(relCall.params.keys).containsExactly("contextId")
        assertThat(relCall.params["contextId"]).isEqualTo("tenant-a")
    }

    @Test
    fun `an explicit null contextId still takes the global db-labels path`() {
        val persistenceManager = FakePersistenceManager(
            labels = setOf("Person"),
            relationshipTypes = setOf("WORKS_AT"),
        )
        val source = DrivineObservedSchemaSource(persistenceManager)

        val observed = source.observe(null)

        assertThat(observed.entityTypeNames).isEqualTo(setOf("Person"))
        assertThat(observed.relationshipTypeNames).isEqualTo(setOf("WORKS_AT"))
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

    /** One recorded call: the Cypher statement text and the parameters it was bound with. */
    private data class RecordedCall(val statement: String, val params: Map<String, Any?>)

    /**
     * Answers the scoped `HAS_MENTION` query with [mentionTypes] and the scoped
     * `sourcePropositions` join with [relationshipTypes], and records every statement/params pair
     * it saw, so a test can assert the scoped path's query shape (and the context value it was
     * actually parameterized with) for either query.
     */
    private class RecordingPersistenceManager(
        private val mentionTypes: Set<String>,
        private val relationshipTypes: Set<String> = emptySet(),
    ) : NoOpPersistenceManager() {

        val calls = mutableListOf<RecordedCall>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
            val statement = spec.statement!!.text
            calls.add(RecordedCall(statement, spec.parameters))
            val rows = when {
                statement.contains("HAS_MENTION") -> mentionTypes.toList()
                statement.contains("sourcePropositions") -> relationshipTypes.toList()
                else -> error("Unexpected statement in test fake: $statement")
            }
            return rows as List<T>
        }
    }
}
