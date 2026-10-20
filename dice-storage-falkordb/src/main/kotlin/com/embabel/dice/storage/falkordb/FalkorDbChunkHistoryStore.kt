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

import com.embabel.agent.core.ContextId
import com.embabel.dice.incremental.AnalysisBookmark
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.storage.falkordb.model.BookmarkNode
import com.embabel.dice.storage.falkordb.model.ChunkHashNode
import com.embabel.dice.storage.falkordb.model.deleteAll
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.dsl.query
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Processed content hashes and per-source bookmarks on FalkorDB, kept apart by context.
 *
 * Each hash and each bookmark is a node whose id is derived from its (context, value) pair, so
 * recording the same pair again updates the node it made the first time.
 */
class FalkorDbChunkHistoryStore(
    private val objects: StatelessGraphObjectManager,
) : ChunkHistoryStore {

    override fun isProcessed(key: HashKey): Boolean =
        objects.load(id(HASH, key.contextId.value, key.contentHash), ChunkHashNode::class.java) != null

    override fun getLastBookmark(key: BookmarkKey): AnalysisBookmark? =
        objects.load(id(BOOKMARK, key.contextId.value, key.sourceId), BookmarkNode::class.java)
            ?.let { AnalysisBookmark(it.sourceId, it.endIndex, it.processedAt) }

    /** Both nodes go in one statement, so FalkorDB writes the hash and the bookmark together or not at all. */
    override fun recordProcessed(record: ProcessedChunkRecord) {
        val context = record.contextId.value
        objects.saveAll(
            listOf(
                ChunkHashNode(id(HASH, context, record.contentHash), context, record.contentHash),
                BookmarkNode(
                    id(BOOKMARK, context, record.sourceId), context, record.sourceId, record.endIndex, record.processedAt,
                ),
            ),
        )
    }

    override fun clearByContext(contextId: ContextId) {
        objects.deleteAll<ChunkHashNode> { where { query.contextId eq contextId.value } }
        objects.deleteAll<BookmarkNode> { where { query.contextId eq contextId.value } }
    }

    override fun clearAll() {
        objects.deleteAll(ChunkHashNode::class.java)
        objects.deleteAll(BookmarkNode::class.java)
    }

    private companion object {
        const val HASH = "hash"
        const val BOOKMARK = "bookmark"

        /** A name-based UUID over the length-prefixed parts, so no context or value can run into the next part. */
        fun id(kind: String, context: String, value: String): String {
            val parts = listOf(kind, context, value).map { it.toByteArray(Charsets.UTF_8) }
            val buffer = ByteBuffer.allocate(parts.sumOf { Int.SIZE_BYTES + it.size })
            parts.forEach { buffer.putInt(it.size).put(it) }
            return UUID.nameUUIDFromBytes(buffer.array()).toString()
        }
    }
}
