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
package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.Chunk

/**
 * What happened when an extraction ran on one chunk. It keeps the chunk, the value the
 * extraction produced, and the exception if it threw. Use it when you need to know which
 * chunk failed and why.
 *
 * A successful outcome can still have a null [value], when the extraction itself returned null.
 *
 * @property chunk the chunk that was extracted
 * @property value the extracted value, or null when the call threw or returned null
 * @property exception the exception the call threw, or null when it did not throw
 */
class ChunkOutcome<T> private constructor(
    val chunk: Chunk,
    val value: T?,
    val exception: Exception?,
) {

    /** True when the extraction finished without throwing. */
    val isSuccess: Boolean get() = exception == null

    /** True when the extraction threw. */
    val isFailure: Boolean get() = exception != null

    override fun toString(): String =
        "ChunkOutcome(chunk=${chunk.id}, value=$value, exception=$exception)"

    companion object {

        /** Makes an outcome for an extraction that finished with [value]. */
        @JvmStatic
        fun <T> success(chunk: Chunk, value: T): ChunkOutcome<T> =
            ChunkOutcome(chunk, value, null)

        /** Makes an outcome for an extraction that threw [exception]. */
        @JvmStatic
        fun <T> failure(chunk: Chunk, exception: Exception): ChunkOutcome<T> =
            ChunkOutcome(chunk, null, exception)
    }
}
