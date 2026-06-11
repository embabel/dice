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
package com.embabel.dice.proposition.store

import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable, out-of-the-box [PropositionRepository] backed by a single JSON file. A consumer can
 * persist propositions and reload them across a process restart without hand-writing storage:
 * constructing a new instance over the same file recovers everything previously saved.
 *
 * Storage is an in-memory [ConcurrentHashMap] index (mirroring [InMemoryPropositionRepository])
 * that is loaded from the file on construction and re-snapshotted to disk on every mutation.
 * The snapshot is crash-safe: each flush writes the full store to a temporary file in the same
 * directory and then atomically replaces the target with
 * [Files.move] using [StandardCopyOption.ATOMIC_MOVE], so the target is never partially written.
 * (A JSONL append log would scale better for very large stores and is a possible future
 * optimization, but is intentionally not built here.)
 *
 * The embedding service is optional. When supplied, embeddings are computed and cached in memory
 * (and recomputed on load) so cosine similarity search works after a reload. When absent (the
 * default), vector similarity degrades gracefully to empty results, while plain storage and
 * lookups keep working.
 *
 * The store [Path] is treated as trusted caller-supplied input; guarding it against path
 * traversal or untrusted locations is the consumer's responsibility. The JSON is deserialized
 * with a plain Kotlin mapper (no polymorphic default typing), and every loaded [Proposition] is
 * re-validated by its own constructor invariants.
 *
 * A graph-backed durable store (e.g. Drivine/Neo4j) is a consumer/downstream extension point:
 * those backends are not on this library's classpath, and a DB-backed implementation should
 * override [findByContextId] (not [findByContextIdValue]) to push per-context filtering down to
 * the store.
 *
 * @param path the JSON file the store is persisted to and loaded from.
 * @param embeddingService optional embedder; when null, vector paths return empty results.
 */
class JsonFilePropositionRepository @JvmOverloads constructor(
    private val path: Path,
    private val embeddingService: EmbeddingService? = null,
) : PropositionRepository {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    private val propositions = ConcurrentHashMap<String, Proposition>()
    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    // Serializes the mutate+flush critical section so concurrent writers cannot persist a
    // torn/stale snapshot and so in-memory state can be rolled back if the disk flush fails.
    private val writeLock = Any()

    init {
        if (Files.exists(path)) {
            val loaded: List<Proposition> = mapper.readValue(path.toFile())
            loaded.forEach { prop ->
                propositions[prop.id] = prop
                embeddingService?.let { embeddings[prop.id] = it.embed(prop.text) }
            }
        }
    }

    override val luceneSyntaxNotes: String
        get() = "no lucene support"

    override fun save(proposition: Proposition): Proposition = synchronized(writeLock) {
        val previous = propositions.put(proposition.id, proposition)
        val previousEmbedding = embeddingService?.let { embedder ->
            embeddings.put(proposition.id, embedder.embed(proposition.text))
        }
        try {
            flush()
        } catch (e: Exception) {
            // Keep in-memory state consistent with disk: undo the mutation on flush failure.
            if (previous == null) propositions.remove(proposition.id) else propositions[proposition.id] = previous
            if (embeddingService != null) {
                if (previousEmbedding == null) embeddings.remove(proposition.id)
                else embeddings[proposition.id] = previousEmbedding
            }
            throw e
        }
        proposition
    }

    override fun findById(id: String): Proposition? = propositions[id]

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        propositions.values.filter { proposition ->
            proposition.mentions.any { it.resolvedId == entityIdentifier.id }
        }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> {
        if (propositions.isEmpty()) return emptyList()

        val embedder = embeddingService ?: return emptyList()
        val queryEmbedding = embedder.embed(textSimilaritySearchRequest.query)
        val minSimilarity = textSimilaritySearchRequest.similarityThreshold

        return propositions.values
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) SimilarityResult(match = prop, score = similarity) else null
            }
            .sortedByDescending { it.score }
            .take(textSimilaritySearchRequest.topK)
    }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        if (propositions.isEmpty()) return emptyList()

        val embedder = embeddingService ?: return emptyList()
        val queryEmbedding = embedder.embed(textSimilaritySearchRequest.query)
        val minSimilarity = textSimilaritySearchRequest.similarityThreshold

        val candidates = query(query)

        return candidates
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) SimilarityResult(match = prop, score = similarity) else null
            }
            .sortedByDescending { it.score }
            .take(textSimilaritySearchRequest.topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vectors must have the same dimension" }

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        propositions.values.filter { it.status == status }

    override fun findByGrounding(chunkId: String): List<Proposition> =
        propositions.values.filter { chunkId in it.grounding }

    override fun findByMinLevel(minLevel: Int): List<Proposition> =
        propositions.values.filter { it.level >= minLevel }

    override fun findAll(): List<Proposition> = propositions.values.toList()

    override fun delete(id: String): Boolean = synchronized(writeLock) {
        val previous = propositions.remove(id) ?: return@synchronized false
        val previousEmbedding = embeddings.remove(id)
        try {
            flush()
        } catch (e: Exception) {
            // Keep in-memory state consistent with disk: restore the removed entry on flush failure.
            propositions[id] = previous
            if (previousEmbedding != null) embeddings[id] = previousEmbedding
            throw e
        }
        true
    }

    override fun count(): Int = propositions.size

    /**
     * Snapshot the full store to disk crash-safely: write to a temp file in the target's parent
     * directory, then atomically rename it over the target. The parent directory is created if
     * absent.
     */
    private fun flush() {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, "props", ".json")
        try {
            mapper.writeValue(tmp.toFile(), propositions.values.toList())
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Avoid accumulating orphaned temp files when the write or atomic move fails.
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}
