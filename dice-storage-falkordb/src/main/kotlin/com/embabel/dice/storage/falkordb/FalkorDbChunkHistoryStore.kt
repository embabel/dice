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
import org.drivine.connection.ConnectionProvider
import org.drivine.transaction.TransactionContextHolder
import java.time.Instant

/**
 * Context-isolated content hashes and source bookmarks persisted atomically through Drivine.
 * Construction establishes operational identity constraints; caller transactions are rejected.
 * The application owns the supplied connection provider.
 */
class FalkorDbChunkHistoryStore @JvmOverloads constructor(
    connectionProvider: ConnectionProvider,
    transactionContextHolder: TransactionContextHolder? = null,
) : ChunkHistoryStore {
    private val access = FalkorDbAccess(connectionProvider, transactionContextHolder)

    init {
        access.checkWriteAllowed()
        FalkorDbStorageSchema.chunks(connectionProvider)
    }

    override fun isProcessed(key: HashKey): Boolean = (access.query(
        "MATCH (h:DiceChunkHash {context:\$context, hash:\$hash}) RETURN {value:count(h)} AS result",
        mapOf("context" to key.contextId.value, "hash" to key.contentHash),
    ).single()["value"] as Number).toLong() > 0

    override fun getLastBookmark(key: BookmarkKey): AnalysisBookmark? = access.query(
        "MATCH (b:DiceBookmark {context:\$context, source:\$source}) " +
            "RETURN {endIndex:b.endIndex, seconds:b.seconds, nanos:b.nanos} AS result",
        mapOf("context" to key.contextId.value, "source" to key.sourceId),
    ).singleOrNull()?.let {
        AnalysisBookmark(key.sourceId, (it["endIndex"] as Number).toInt(),
            Instant.ofEpochSecond((it["seconds"] as Number).toLong(), (it["nanos"] as Number).toLong()))
    }

    override fun recordProcessed(record: ProcessedChunkRecord) {
        access.checkWriteAllowed()
        repeat(64) {
            try {
                access.write(
                    "MERGE (:DiceChunkHash {context:\$context, hash:\$hash}) " +
                        "MERGE (b:DiceBookmark {context:\$context, source:\$source}) " +
                        "SET b.endIndex=\$endIndex, b.seconds=\$seconds, b.nanos=\$nanos " +
                        "RETURN {value:1} AS result",
                    mapOf(
                        "context" to record.contextId.value,
                        "hash" to record.contentHash,
                        "source" to record.sourceId,
                        "endIndex" to record.endIndex,
                        "seconds" to record.processedAt.epochSecond,
                        "nanos" to record.processedAt.nano,
                    ),
                )
                return
            } catch (ex: Exception) {
                if (!access.isConstraintViolation(ex)) throw ex
            }
        }
        error("Concurrent changes prevented recording chunk history")
    }

    override fun clearByContext(contextId: ContextId) = clear(contextId.value)
    override fun clearAll() = clear(null)

    private fun clear(context: String?) {
        access.write(
            "MATCH (n) WHERE n:DiceChunkHash OR n:DiceBookmark " +
                "WITH n, (\$context IS NULL OR n.context=\$context) AS accepted " +
                "FOREACH (ignored IN CASE WHEN accepted THEN [1] ELSE [] END | DELETE n) RETURN {value:1} AS result",
            mapOf("context" to context),
        )
    }
}
