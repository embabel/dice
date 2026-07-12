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
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import java.time.Instant

/**
 * Node labels dice writes for its own bookkeeping, never for domain data. A drift check compares
 * the *domain* schema an app declared against what a live graph actually contains, and these labels
 * were never part of any declared domain schema -- counting them would flag dice's own storage
 * machinery as "drift" on every single run. Named here once so anything that needs the exclusion
 * (this source, and any future one) agrees on the same set.
 */
val DICE_BOOKKEEPING_LABELS: Set<String> = setOf(
    "Proposition",
    "Mention",
    "CollectorRecord",
    "CollectorRun",
    "MetamodelVersion",
    "MetamodelDriftReport",
    "ProjectionRecord",
)

/**
 * Drivine/Neo4j-backed [ObservedSchemaSource]. Two distinct observation paths:
 *
 * - **Global** (`contextId == null`): introspects the live graph's distinct node labels and
 *   relationship types via `db.labels()` / `db.relationshipTypes()`, excluding
 *   [DICE_BOOKKEEPING_LABELS] from the entity side. There is no equivalent relationship-type
 *   exclusion: dice's own bookkeeping is all represented as node labels, not relationship types.
 * - **Scoped** (`contextId != null`): `db.labels()` / `db.relationshipTypes()` have no notion of
 *   context, so the scoped path can't use them. Instead it derives both sides from that context's
 *   own data:
 *   - Entity types: the distinct `Mention.type` values reachable from that context's `:Proposition`
 *     nodes via `HAS_MENTION`.
 *   - Relationship types: every edge the graph writer persists carries a `sourcePropositions`
 *     property -- the ids of the propositions that produced it (see
 *     `NamedEntityDataRepositoryGraphRelationshipPersister`). A relationship type belongs to a
 *     context's scoped set if at least one edge of that type has a `sourcePropositions` entry that
 *     is the id of a `:Proposition` in that context. That's a join on that property, not a stored
 *     tag on the edge itself, and it deliberately does **not** collapse: an edge sourced from
 *     propositions in two different contexts appears in *both* contexts' scoped sets. That's
 *     correct, not a bug -- an undeclared relationship type present in your context's data is drift
 *     in your context, regardless of who else also produced it.
 */
class DrivineObservedSchemaSource(
    private val persistenceManager: PersistenceManager,
) : ObservedSchemaSource {

    override fun observe(contextId: ContextId?): ObservedSchema =
        if (contextId == null) observeGlobal() else observeScoped(contextId)

    private fun observeGlobal(): ObservedSchema {
        val labels = queryStrings("CALL db.labels() YIELD label RETURN label")
        val relationshipTypes = queryStrings("CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType")

        return ObservedSchema(
            entityTypeNames = labels - DICE_BOOKKEEPING_LABELS,
            relationshipTypeNames = relationshipTypes,
            capturedAt = Instant.now(),
        )
    }

    private fun observeScoped(contextId: ContextId): ObservedSchema {
        val entityTypeNames = queryStrings(
            cypher = """
                MATCH (:Proposition {contextId: ${'$'}contextId})-[:HAS_MENTION]->(m:Mention)
                RETURN DISTINCT m.type
                """.trimIndent(),
            params = mapOf("contextId" to contextId.value),
        )
        val relationshipTypeNames = queryStrings(
            cypher = """
                MATCH (p:Proposition {contextId: ${'$'}contextId})
                WITH collect(p.id) AS ids
                MATCH ()-[r]->()
                WHERE any(pid IN r.sourcePropositions WHERE pid IN ids)
                RETURN DISTINCT type(r)
                """.trimIndent(),
            params = mapOf("contextId" to contextId.value),
        )
        return ObservedSchema(
            entityTypeNames = entityTypeNames,
            relationshipTypeNames = relationshipTypeNames,
            capturedAt = Instant.now(),
        )
    }

    private fun queryStrings(cypher: String, params: Map<String, Any?> = emptyMap()): Set<String> {
        val spec = QuerySpecification.withStatement(cypher).bind(params).transform(String::class.java)
        return persistenceManager.query(spec).toSet()
    }
}
