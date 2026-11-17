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
package com.embabel.dice.incremental

import com.embabel.agent.core.ContextId

/**
 * A store written the way a Kotlin store was written before the String form existed: it overrides
 * only clearByContext(ContextId) and records the contexts it was asked to clear.
 */
class KotlinOnlyChunkHistoryStore : ChunkHistoryStore {

    /** The contexts this store was asked to clear, in order. */
    val cleared: MutableList<ContextId> = mutableListOf()

    override fun getLastBookmark(key: BookmarkKey): AnalysisBookmark? = null

    override fun isProcessed(key: HashKey): Boolean = false

    override fun recordProcessed(record: ProcessedChunkRecord) {}

    override fun clearByContext(contextId: ContextId) {
        cleared += contextId
    }

    /** The cleared contexts as plain strings, for Java tests. */
    fun clearedValues(): List<String> = cleared.map { it.value }
}
