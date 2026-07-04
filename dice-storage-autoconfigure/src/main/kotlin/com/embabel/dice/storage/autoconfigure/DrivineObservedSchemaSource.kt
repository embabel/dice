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
 * Drivine/Neo4j-backed [ObservedSchemaSource]: introspects the live graph's distinct node labels
 * and relationship types via `db.labels()` / `db.relationshipTypes()`, excluding
 * [DICE_BOOKKEEPING_LABELS] from the entity side. There is no equivalent relationship-type
 * exclusion: dice's own bookkeeping is all represented as node labels, not relationship types.
 */
class DrivineObservedSchemaSource(
    private val persistenceManager: PersistenceManager,
) : ObservedSchemaSource {

    override fun observe(): ObservedSchema {
        val labels = queryStrings("CALL db.labels() YIELD label RETURN label")
        val relationshipTypes = queryStrings("CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType")

        return ObservedSchema(
            entityTypeNames = labels - DICE_BOOKKEEPING_LABELS,
            relationshipTypeNames = relationshipTypes,
            capturedAt = Instant.now(),
        )
    }

    private fun queryStrings(cypher: String): Set<String> {
        val spec = QuerySpecification.withStatement(cypher).transform(String::class.java)
        return persistenceManager.query(spec).toSet()
    }
}
