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

import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaItemSpec

/**
 * One dice-owned Cypher-backed store's schema: the constraints and indexes it needs, and the
 * relationship types it writes for dice's own bookkeeping.
 *
 * An application registers these as beans, and the registered set does two jobs at once. Drivine
 * ensures the DDL from it ([diceStorageCatalog]), and [DiceOwnedSchema] reads the same set to work
 * out which nodes and edges in a shared graph are dice's own, so a whole-graph drift check leaves
 * them alone. Making one list answer both questions is the point: a store whose schema reaches the
 * database has, by construction, reached the ownership rule too.
 *
 * Adding a store is therefore a two-file job in the slice that adds it: declare its schema object
 * beside the store, and register it where the application wires its stores. Nothing in the drift
 * machinery has to hear about it.
 */
interface DiceStorageSchema {

    /** The constraints and indexes this store's writes depend on. */
    fun specs(): List<SchemaItemSpec>

    /**
     * Relationship types this store writes between its own nodes. A whole-graph observation hides
     * them, on the same grounds it hides the node labels: they are dice's bookkeeping, and no
     * declared domain schema is expected to mention them.
     *
     * Empty for a store that writes no edges of its own.
     */
    val bookkeepingRelationshipTypes: Set<String>
        get() = emptySet()
}

/**
 * The Drivine catalog for a set of registered dice storage schemas.
 *
 * Wiring the DDL through this function is what keeps a store's constraints and its ownership
 * exclusion off one list. A host that hand-writes `SchemaCatalog.of(SomeSchema.specs())` gets the
 * constraints while leaving the store out of the exclusion, and its labels then read as domain
 * drift for as long as the graph holds its nodes; `DiceStorageSchemaRegistrationTest` fails when
 * that happens.
 */
fun diceStorageCatalog(schemas: List<DiceStorageSchema>): SchemaCatalog =
    SchemaCatalog.of(schemas.flatMap { schema -> schema.specs() })
