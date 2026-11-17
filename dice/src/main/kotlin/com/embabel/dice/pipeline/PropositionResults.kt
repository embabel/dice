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

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.core.types.HasInfoString
import com.embabel.dice.common.*
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.revision.isReplay

/**
 * Statistics about proposition revision outcomes. A replay, a merge or reinforcement that left the
 * stored proposition as it was, is not counted.
 */
data class PropositionExtractionStats(
    /** Number of propositions that were new (not similar to existing) */
    val newCount: Int,
    /** Number of propositions that were merged with existing identical ones */
    val mergedCount: Int,
    /** Number of propositions that reinforced existing similar ones */
    val reinforcedCount: Int,
    /** Number of propositions that contradicted existing ones */
    val contradictedCount: Int,
    /** Number of propositions that generalized existing ones */
    val generalizedCount: Int,
) {
    /** Total number of propositions processed */
    val total: Int get() = newCount + mergedCount + reinforcedCount + contradictedCount + generalizedCount

    companion object {
        fun from(revisionResults: List<RevisionResult>): PropositionExtractionStats = PropositionExtractionStats(
            newCount = revisionResults.count { it is RevisionResult.New },
            mergedCount = revisionResults.count { it is RevisionResult.Merged && !it.isReplay() },
            reinforcedCount = revisionResults.count { it is RevisionResult.Reinforced && !it.isReplay() },
            contradictedCount = revisionResults.count { it is RevisionResult.Contradicted },
            generalizedCount = revisionResults.count { it is RevisionResult.Generalized },
        )
    }
}

/**
 * Common interface for proposition revision statistics.
 */
interface PropositionExtractionResult {
    /** All revision results */
    val revisionResults: List<RevisionResult>

    /** Statistics about revision outcomes */
    val propositionExtractionStats: PropositionExtractionStats get() = PropositionExtractionStats.from(revisionResults)

    /** Whether revision was enabled */
    val hasRevision: Boolean get() = revisionResults.isNotEmpty()

    /**
     * All propositions that need to be persisted after revision.
     * Includes both new propositions and updates to existing ones:
     * - New: the new proposition
     * - Merged: the revised (merged) proposition
     * - Reinforced: the revised (reinforced) proposition
     * - Contradicted: both the original (with reduced confidence) and the new
     * - Generalized: the new generalizing proposition
     *
     * Each stored proposition appears once, as the last copy the results produced for it. A replay
     * is left out when another result changed or contradicted that same stored proposition, so
     * saving the list cannot undo that change.
     */
    val revisedPropositionsToPersist: List<Proposition>
        get() {
            val changedIds = revisionResults.mapNotNull { it.changedStoredId() }.toSet()
            val toPersist = revisionResults
                .filterNot { it.isReplay() && it.storedId() in changedIds }
                .flatMap { result ->
                    when (result) {
                        is RevisionResult.New -> listOf(result.proposition)
                        is RevisionResult.Merged -> listOf(result.revised)
                        is RevisionResult.Reinforced -> listOf(result.revised)
                        is RevisionResult.Contradicted -> listOf(result.original, result.new)
                        is RevisionResult.Generalized -> listOf(result.proposition)
                    }
                }
            // A result can come back before the one it built on, so the final copy of a stored
            // proposition is the one no other result started from, not simply the last in the list.
            val startedFrom = revisionResults.mapNotNull { it.changedOriginal() }.toSet()
            val finalById = LinkedHashMap<String, Proposition>()
            for (proposition in toPersist) {
                val held = finalById[proposition.id]
                if (held == null || proposition !in startedFrom || held in startedFrom) finalById[proposition.id] = proposition
            }
            val lastIndexById = toPersist.withIndex().associate { (index, proposition) -> proposition.id to index }
            return toPersist.withIndex()
                .filter { (index, proposition) -> lastIndexById[proposition.id] == index }
                .map { (_, proposition) -> finalById.getValue(proposition.id) }
        }
}

/**
 * The outcome of processing a single chunk through the proposition pipeline.
 *
 * A chunk either succeeds ([Success], with propositions and entity resolutions) or fails
 * ([Failed], with a typed serializable error). Modelling failure inline preserves the
 * `chunkResults.size == chunks.size` invariant — a failed chunk surfaces as a [Failed]
 * slot rather than aborting the run or being silently dropped.
 *
 * All flat-access members ([propositions], [revisionResults], [persist], [newEntities], etc.)
 * are available on this interface, so call sites work without smart-casting. The
 * Success-only fields (`suggestedPropositions`, `entityResolutions`) live on [Success]
 * and require a smart-cast to access.
 */
sealed interface ChunkPropositionResult : PersistablePropositions, HasInfoString {

    /** Id of the chunk this result originated from. */
    val chunkId: String

    /**
     * A chunk that was processed successfully.
     */
    data class Success(
        override val chunkId: String,
        val suggestedPropositions: SuggestedPropositions,
        val entityResolutions: Resolutions<SuggestedEntityResolution>,
        override val propositions: List<Proposition>,
        override val revisionResults: List<RevisionResult> = emptyList(),
    ) : ChunkPropositionResult {

        override fun newEntities(): List<NamedEntityData> =
            entityResolutions.resolutions
                .filterIsInstance<NewEntity>()
                .map { it.suggested.suggestedEntity }
                .distinctBy { it.id }

        override fun updatedEntities(): List<NamedEntityData> =
            entityResolutions.resolutions
                .filterIsInstance<ExistingEntity>()
                .map { it.recommended }
                .distinctBy { it.id }

        override fun referenceOnlyEntities(): List<NamedEntityData> =
            entityResolutions.resolutions
                .filterIsInstance<ReferenceOnlyEntity>()
                .map { it.existing }
                .distinctBy { it.id }

        override fun infoString(verbose: Boolean?, indent: Int): String {
            val prefix = "  ".repeat(indent)
            val stats = propositionExtractionStats
            val newEntitiesCount = newEntities().size
            val updatedEntitiesCount = updatedEntities().size
            val referenceOnlyCount = referenceOnlyEntities().size

            return buildString {
                append("ChunkPropositionResult(chunk=$chunkId, ")
                append("propositions=${propositions.size}, ")
                append("entities: $newEntitiesCount new, $updatedEntitiesCount updated")
                if (referenceOnlyCount > 0) {
                    append(", $referenceOnlyCount reference-only")
                }
                if (hasRevision) {
                    append(", revision: ")
                    append("${stats.newCount} new, ")
                    append("${stats.mergedCount} merged, ")
                    append("${stats.reinforcedCount} reinforced, ")
                    append("${stats.contradictedCount} contradicted, ")
                    append("${stats.generalizedCount} generalized")
                }
                append(")")

                if (verbose == true) {
                    appendLine()
                    append("${prefix}Propositions:")
                    propositions.forEachIndexed { i, prop ->
                        appendLine()
                        val revisionInfo = if (hasRevision && i < revisionResults.size) {
                            when (val result = revisionResults[i]) {
                                is RevisionResult.New -> "[NEW]"
                                is RevisionResult.Merged -> "[MERGED with ${result.original.id.take(8)}]"
                                is RevisionResult.Reinforced -> "[REINFORCED ${result.original.id.take(8)}]"
                                is RevisionResult.Contradicted -> "[CONTRADICTED ${result.original.id.take(8)}]"
                                is RevisionResult.Generalized -> "[GENERALIZED ${result.generalizes.size} props]"
                            }
                        } else ""
                        append("$prefix  • ${prop.text} (conf: ${String.format("%.2f", prop.confidence)}) $revisionInfo")
                    }
                    if (newEntitiesCount > 0 || updatedEntitiesCount > 0 || referenceOnlyCount > 0) {
                        appendLine()
                        append("${prefix}Entities:")
                        newEntities().forEach { entity ->
                            appendLine()
                            append("$prefix  • [NEW] ${entity.name} (${entity.labels().joinToString()})")
                        }
                        updatedEntities().forEach { entity ->
                            appendLine()
                            append("$prefix  • [UPDATED] ${entity.name} (${entity.labels().joinToString()})")
                        }
                        referenceOnlyEntities().forEach { entity ->
                            appendLine()
                            append("$prefix  • [REF-ONLY] ${entity.name} (${entity.labels().joinToString()})")
                        }
                    }
                }
            }
        }
    }

    /**
     * A chunk that failed to process. Carries the [chunkId], a serialization-friendly [error]
     * message, and the optional raw [cause] for in-process inspection. Contributes nothing
     * to any proposition or entity aggregate.
     */
    data class Failed(
        override val chunkId: String,
        val error: String,
        val cause: Throwable? = null,
    ) : ChunkPropositionResult {

        override val propositions: List<Proposition> get() = emptyList()
        override val revisionResults: List<RevisionResult> get() = emptyList()

        override fun newEntities(): List<NamedEntityData> = emptyList()
        override fun updatedEntities(): List<NamedEntityData> = emptyList()
        override fun referenceOnlyEntities(): List<NamedEntityData> = emptyList()

        override fun infoString(verbose: Boolean?, indent: Int): String =
            "Failed(chunk=$chunkId: $error)"
    }

    companion object {
        /** Construct a [Success] result. Java/test-friendly factory. */
        @JvmStatic
        fun success(
            chunkId: String,
            suggestedPropositions: SuggestedPropositions,
            entityResolutions: Resolutions<SuggestedEntityResolution>,
            propositions: List<Proposition>,
            revisionResults: List<RevisionResult> = emptyList(),
        ): Success = Success(chunkId, suggestedPropositions, entityResolutions, propositions, revisionResults)

        /** Construct a [Failed] result from a [Throwable]. Java/test-friendly factory. */
        @JvmStatic
        fun failed(chunkId: String, cause: Throwable): Failed =
            Failed(chunkId, cause.message ?: cause.toString(), cause)
    }
}

/**
 * Result of processing multiple chunks through the proposition pipeline.
 * Implements [EntityExtractionResult] for access to entities needing persistence.
 *
 * This result contains all extracted data but does NOT persist anything.
 * The caller is responsible for persisting entities and propositions as needed.
 */
data class PropositionResults(
    val chunkResults: List<ChunkPropositionResult>,
    val allPropositions: List<Proposition>,
) : PersistablePropositions {

    override val propositions: List<Proposition> get() = allPropositions

    val totalPropositions: Int get() = allPropositions.size
    val fullyResolvedCount: Int get() = allPropositions.count { it.isFullyResolved() }
    val partiallyResolvedCount: Int get() = allPropositions.count { !it.isFullyResolved() && it.mentions.any { m -> m.resolvedId != null } }
    val unresolvedCount: Int get() = allPropositions.count { it.mentions.none { m -> m.resolvedId != null } }

    /** All revision results across all chunks */
    override val revisionResults: List<RevisionResult> get() = chunkResults.flatMap { it.revisionResults }

    /** Chunk ids of all chunks that failed to process. */
    val failedChunkIds: List<String>
        get() = chunkResults.filterIsInstance<ChunkPropositionResult.Failed>().map { it.chunkId }

    override fun newEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.newEntities() }.distinctBy { it.id }

    override fun updatedEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.updatedEntities() }.distinctBy { it.id }

    override fun referenceOnlyEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.referenceOnlyEntities() }.distinctBy { it.id }
}

/**
 * Returns the id of the stored proposition a merge or reinforcement started from, or null for
 * other results.
 */
private fun RevisionResult.storedId(): String? = when (this) {
    is RevisionResult.Merged -> original.id
    is RevisionResult.Reinforced -> original.id
    else -> null
}

/**
 * Returns the copy a merge or reinforcement that changed something started from, or null.
 */
private fun RevisionResult.changedOriginal(): Proposition? = when (this) {
    is RevisionResult.Merged -> original.takeUnless { isReplay() }
    is RevisionResult.Reinforced -> original.takeUnless { isReplay() }
    else -> null
}

/**
 * Returns the id of the stored proposition this result changed, or null when it changed none.
 * A contradiction counts as a change to the proposition it contradicts.
 */
private fun RevisionResult.changedStoredId(): String? = when (this) {
    is RevisionResult.Merged, is RevisionResult.Reinforced -> if (isReplay()) null else storedId()
    is RevisionResult.Contradicted -> original.id
    else -> null
}
