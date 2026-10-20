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
package com.embabel.dice.storage.falkordb.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Unique
import java.time.Instant

/**
 * A content hash already processed in a context. The id is derived from the pair, so saving the same
 * pair again finds the same node.
 */
@NodeFragment(labels = ["DiceChunkHash"])
@Unique(properties = ["contextId", "hash"])
data class ChunkHashNode(
    @NodeId
    @Unique
    val id: String,
    val contextId: String,
    val hash: String,
)

/** How far a source has been processed in a context. The id is derived from the pair, as for [ChunkHashNode]. */
@NodeFragment(labels = ["DiceBookmark"])
@Unique(properties = ["contextId", "sourceId"])
data class BookmarkNode(
    @NodeId
    @Unique
    val id: String,
    val contextId: String,
    val sourceId: String,
    val endIndex: Int,
    val processedAt: Instant,
)
