package com.embabel.dice.proposition

import com.embabel.agent.core.DataDictionary
import com.embabel.common.core.types.HasInfoString

/**
 * Marker interface for types that are projected from propositions.
 * Extends [Derivation] to inherit confidence, decay, and grounding.
 * Provides traceability back to source propositions.
 */
interface Projection : Derivation {
    /**
     * IDs of the propositions that this projection derives from.
     * This is the grounding for projected items.
     */
    val sourcePropositionIds: List<String>

    /**
     * Grounding defaults to source proposition IDs.
     * Projections trace back to the propositions they were derived from.
     */
    override val grounding: List<String>
        get() = sourcePropositionIds
}

/**
 * Generic projector that transforms propositions into typed projections.
 * Implementations project to specific backends (Graph, Prolog, Vector, Memory).
 *
 * @param T The type of projection result (e.g., ProjectedRelationship, PrologFact)
 */
interface Projector<T : Projection> {

    /**
     * Project a single proposition to a target representation.
     *
     * @param proposition The proposition to project
     * @param schema The data dictionary defining domain types and relationships
     * @return The projection result (success, skipped, or failure)
     */
    fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<T>

    /**
     * Project multiple propositions.
     *
     * @param propositions The propositions to project
     * @param schema The data dictionary defining domain types and relationships
     * @return Aggregated projection results
     */
    fun projectAll(
        propositions: List<Proposition>,
        schema: DataDictionary,
    ): ProjectionResults<T> {
        val results = propositions.map { project(it, schema) }
        return ProjectionResults(results)
    }
}

/**
 * Result of attempting to project a proposition.
 *
 * @param T The type of successful projection
 */
sealed interface ProjectionResult<out T : Projection> : HasInfoString {
    val proposition: Proposition
}

/**
 * Proposition was successfully projected.
 */
data class ProjectionSuccess<T : Projection>(
    override val proposition: Proposition,
    val projected: T,
) : ProjectionResult<T> {
    override fun infoString(verbose: Boolean?, indent: Int): String =
        "Projected(${proposition.text.take(40)}...)"
}

/**
 * Proposition was skipped because it didn't meet projection criteria.
 */
data class ProjectionSkipped<T : Projection>(
    override val proposition: Proposition,
    val reason: String,
) : ProjectionResult<T> {
    override fun infoString(verbose: Boolean?, indent: Int): String =
        "Skipped(${proposition.text.take(40)}...: $reason)"
}

/**
 * Proposition couldn't be projected due to an error or incompatibility.
 */
data class ProjectionFailed<T : Projection>(
    override val proposition: Proposition,
    val reason: String,
) : ProjectionResult<T> {
    override fun infoString(verbose: Boolean?, indent: Int): String =
        "Failed(${proposition.text.take(40)}...: $reason)"
}

/**
 * Aggregated results from projecting multiple propositions.
 */
data class ProjectionResults<T : Projection>(
    val results: List<ProjectionResult<T>>,
) {
    val successes: List<ProjectionSuccess<T>>
        get() = results.filterIsInstance<ProjectionSuccess<T>>()

    val skipped: List<ProjectionSkipped<T>>
        get() = results.filterIsInstance<ProjectionSkipped<T>>()

    val failures: List<ProjectionFailed<T>>
        get() = results.filterIsInstance<ProjectionFailed<T>>()

    val projected: List<T>
        get() = successes.map { it.projected }

    val successCount: Int get() = successes.size
    val skipCount: Int get() = skipped.size
    val failureCount: Int get() = failures.size
    val totalCount: Int get() = results.size
}
