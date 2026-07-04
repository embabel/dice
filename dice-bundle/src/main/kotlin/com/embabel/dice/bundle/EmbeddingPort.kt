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
package com.embabel.dice.bundle

import java.nio.ByteBuffer
import java.util.Base64

/**
 * One proposition's embedding vector, carried in a bundle as base64 rather than a raw JSON number
 * array — a JSON array of doubles would be several times larger on the wire and would round-trip
 * through double precision for what's really a float32 vector.
 *
 * @property propositionId The proposition this vector belongs to.
 * @property vectorBase64 The vector's float32 values packed back-to-back (4 bytes each, big-endian,
 *   no length prefix) and base64-encoded. The vector's length is the decoded byte count divided by
 *   4. Use [EmbeddingCodec] to produce and read this rather than hand-rolling the encoding.
 */
data class EmbeddingEntry(
    val propositionId: String,
    val vectorBase64: String,
)

/**
 * Packs a float vector into the base64 form [EmbeddingEntry.vectorBase64] documents, and back.
 * Kept as a small standalone codec so both the bundle side and a port implementation (e.g. a
 * storage-backed [EmbeddingImporter]) can share the exact same encoding.
 */
object EmbeddingCodec {

    /** Encode [vector] as big-endian float32 bytes, base64-encoded. */
    fun encode(vector: List<Float>): String {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
        vector.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    /** Decode a base64 string produced by [encode] back into the original float vector. */
    fun decode(vectorBase64: String): List<Float> {
        val bytes = Base64.getDecoder().decode(vectorBase64)
        val buffer = ByteBuffer.wrap(bytes)
        val values = ArrayList<Float>(bytes.size / Float.SIZE_BYTES)
        while (buffer.remaining() >= Float.SIZE_BYTES) {
            values.add(buffer.float)
        }
        return values
    }
}

/**
 * Export-side SPI: the stored vector for a proposition id, or null if there isn't one (never
 * embedded, or the stored value is corrupt/unreadable) — a bundle simply omits that proposition
 * from its embeddings section rather than failing the whole export.
 */
fun interface EmbeddingExporter {
    fun embeddingFor(propositionId: String): List<Float>?
}

/**
 * Import-side SPI: persist a vector for a proposition id, so a re-import doesn't have to pay to
 * re-embed at LLM cost.
 */
fun interface EmbeddingImporter {
    fun importEmbedding(propositionId: String, vector: List<Float>)
}

/** Convenience combination of both directions, for callers that want to wire one object for both. */
interface EmbeddingPort : EmbeddingExporter, EmbeddingImporter

/**
 * Null-object default: no vector known on export, nothing done on import. Lets the embeddings
 * section stay genuinely optional — a consumer that doesn't wire a real embedding store just
 * re-embeds at import time as it always has.
 */
object NoOpEmbeddingPort : EmbeddingPort {
    override fun embeddingFor(propositionId: String): List<Float>? = null
    override fun importEmbedding(propositionId: String, vector: List<Float>) = Unit
}
