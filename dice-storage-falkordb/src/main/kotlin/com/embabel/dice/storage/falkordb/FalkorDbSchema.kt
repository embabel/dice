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
package com.embabel.dice.storage.falkordb

import com.embabel.dice.storage.falkordb.model.BookmarkNode
import com.embabel.dice.storage.falkordb.model.ChunkHashNode
import com.embabel.dice.storage.falkordb.model.FalkorDbPropositionNode
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.VectorDimensionProvider
import org.drivine.schema.VectorIndexSpec

/**
 * The constraints and indexes the FalkorDB stores rely on, read off the model's annotations.
 *
 * Register [catalog] as a bean, or hand it to a Drivine `SchemaManager`, and Drivine ensures it once
 * at startup. The stores never create schema themselves. The starter does this for you, aimed at the
 * datasource it uses.
 */
object FalkorDbSchema {

    /** Every constraint and index: unique ids, the chunk-history pairs, and the range indexes queries use. */
    fun specs(): List<SchemaItemSpec> {
        // Searches score every eligible vector exactly, so the approximate index the embedding's
        // annotation declares is left out: it would cost every write and never be read. The
        // dimension given here is therefore never used.
        val scanned = SchemaCatalog.fromFragments(
            VectorDimensionProvider.fixed(1),
            FalkorDbPropositionNode::class,
            ChunkHashNode::class,
            BookmarkNode::class,
        )
        return scanned.constraints + scanned.indexes.filterNot { it is VectorIndexSpec }
    }

    /** [specs] as a catalog for one datasource, so other databases the application uses are left alone. */
    fun catalog(database: String): SchemaCatalog = SchemaCatalog.of(specs()).forDatabase(database)
}
