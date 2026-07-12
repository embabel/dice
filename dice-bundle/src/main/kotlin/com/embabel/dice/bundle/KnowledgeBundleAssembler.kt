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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory

/**
 * Assembles [KnowledgeBundle]s from a [PropositionRepository], decoupled from serialization.
 *
 * This class is responsible for querying propositions by context and assembling them into
 * [KnowledgeBundle] envelopes. The [KnowledgeBundleExporter] SPI remains pure serialization;
 * callers compose by assembling a bundle here, then serializing it with an exporter instance.
 *
 * The assembler uses the repository's [PropositionRepository.findByContextId] method,
 * which is the override point for backend-pushed filtering — DB-backed repositories specialize
 * this method for efficient backend filtering, and in-memory repositories override it to search
 * memory efficiently. This ensures context-scoped reads are never degraded to full-store scans.
 *
 * All propositions for a context (or all contexts) are loaded into memory per call —
 * there is no streaming support. Callers working with very large contexts should batch.
 *
 * @param repository Source of propositions to assemble into bundles.
 * @param entitySnapshotExporter Optional collaborator that supplies entity snapshots for the
 *   resolvedIds a bundle's propositions mention (see [EntitySnapshotPort]). Defaults to
 *   [NoOpEntitySnapshotPort], which contributes nothing — the bundle's `entities` section stays
 *   empty and the bundle remains fully valid. Wire a real one only if this consumer owns entity
 *   data dice doesn't.
 * @param embeddingExporter Optional collaborator that supplies each proposition's stored embedding
 *   vector (see [EmbeddingPort]). Defaults to [NoOpEmbeddingPort], which contributes nothing —
 *   the bundle's `embeddings` section stays empty and a later import simply re-embeds as before.
 */
class KnowledgeBundleAssembler(
    private val repository: PropositionRepository,
    private val entitySnapshotExporter: EntitySnapshotExporter = NoOpEntitySnapshotPort,
    private val embeddingExporter: EmbeddingExporter = NoOpEmbeddingPort,
) {

    private val logger = LoggerFactory.getLogger(KnowledgeBundleAssembler::class.java)

    /**
     * Assemble a [KnowledgeBundle] containing all propositions in a given context.
     *
     * Reads propositions for [contextId] using the repository's [PropositionRepository.findByContextId]
     * method, which is the optimized override point — this ensures backend repositories can push
     * filtering down to their storage layer rather than scanning everything.
     *
     * If no propositions exist for this context, returns a valid empty bundle (zero propositions,
     * correct contextId). Empty contexts are not errors — they are valid states.
     *
     * @param contextId The ID of the context to assemble.
     * @return A [KnowledgeBundle] containing all propositions in that context, plus whatever
     *   [entitySnapshotExporter] and [embeddingExporter] contribute for them.
     */
    fun forContext(contextId: String): KnowledgeBundle {
        val ctxId = ContextId(contextId)
        val propositions = repository.findByContextId(ctxId)
        logger.info(
            "Assembling bundle for context '{}': {} propositions",
            contextId,
            propositions.size,
        )
        return KnowledgeBundle.from(
            ctxId,
            propositions,
            entities = entitiesFor(propositions),
            embeddings = embeddingsFor(propositions),
        )
    }

    /**
     * Assemble a [KnowledgeBundle] for each distinct context in the repository.
     *
     * Loads all propositions from the repository, groups them by context ID, and returns one
     * [KnowledgeBundle] per distinct context. If the repository is empty, returns an empty list.
     *
     * All propositions for all contexts are loaded into memory in a single operation;
     * callers exporting very large stores should be aware of this and batch if needed.
     *
     * @return A list of [KnowledgeBundle], one per context present in the repository.
     *   Order is unspecified.
     */
    fun allContexts(): List<KnowledgeBundle> {
        val allPropositions = repository.findAll()
        logger.debug("Assembling all contexts: {} total propositions", allPropositions.size)

        if (allPropositions.isEmpty()) {
            logger.info("No propositions in store; allContexts returns empty list")
            return emptyList()
        }

        // Group propositions by context
        val byContext = allPropositions.groupBy { it.contextId }
        logger.info("Found {} distinct context(s)", byContext.size)

        // Build one bundle per context
        return byContext.map { (contextId, propositions) ->
            logger.debug("Assembling bundle for context '{}': {} propositions", contextId.value, propositions.size)
            KnowledgeBundle.from(
                contextId,
                propositions,
                entities = entitiesFor(propositions),
                embeddings = embeddingsFor(propositions),
            )
        }
    }

    /** Snapshots for every resolvedId mentioned by [propositions], via [entitySnapshotExporter]. */
    private fun entitiesFor(propositions: List<Proposition>): List<EntitySnapshot> {
        val resolvedIds = propositions
            .flatMap { it.mentions }
            .mapNotNull { it.resolvedId }
            .toSet()
        if (resolvedIds.isEmpty()) return emptyList()
        return entitySnapshotExporter.snapshotsFor(resolvedIds)
    }

    /** One [EmbeddingEntry] per proposition that [embeddingExporter] has a vector for. */
    private fun embeddingsFor(propositions: List<Proposition>): List<EmbeddingEntry> =
        propositions.mapNotNull { proposition ->
            embeddingExporter.embeddingFor(proposition.id)?.let { vector ->
                EmbeddingEntry(propositionId = proposition.id, vectorBase64 = EmbeddingCodec.encode(vector))
            }
        }
}
