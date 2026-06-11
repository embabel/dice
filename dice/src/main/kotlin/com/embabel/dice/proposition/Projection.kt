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
package com.embabel.dice.proposition

import com.embabel.agent.core.DataDictionary
import com.embabel.common.core.types.HasInfoString

/**
 * Marker interface for types derived from propositions (graph relationships, Prolog facts, etc.).
 * Carries confidence, decay, and grounding inherited from [Derivation], plus a link back to the
 * source propositions.
 */
interface Projection : Derivation {
    /**
     * IDs of the propositions this projection was derived from.
     */
    val sourcePropositionIds: List<String>

    /**
     * Grounding traces back to the source propositions by default.
     */
    override val grounding: List<String>
        get() = sourcePropositionIds
}

/**
 * Transforms propositions into a typed target representation (graph, Prolog, memory context, etc.).
 *
 * @param T The projection type produced by this projector
 */
interface Projector<T : Projection> {

    /**
     * Project a single proposition.
     *
     * @param proposition The proposition to project
     * @param schema The data dictionary defining domain types and relationships
     * @return Success, skipped, or failure — never throws
     */
    fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<T>

    /**
     * Project a batch of propositions.
     *
     * @param propositions The propositions to project
     * @param schema The data dictionary defining domain types and relationships
     * @return Aggregated results for the whole batch
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
 * Why a proposition could not be projected (or was skipped). Use [describe] for a
 * human-readable summary, or branch on the concrete subtype to react programmatically
 * without parsing text.
 */
sealed interface ProjectionFailureReason {

    /**
     * A concise human-readable rendering of this failure reason.
     */
    fun describe(): String

    /**
     * No predicate in the schema or relations matched the proposition.
     *
     * @property detail The proposition text or predicate detail that failed to match
     */
    data class NoMatchingPredicate(val detail: String) : ProjectionFailureReason {
        override fun describe(): String = "no matching predicate: $detail"
    }

    /**
     * A mention's declared type did not match the relation's expected type.
     *
     * @property role The role of the mismatched mention (subject or object)
     * @property actual The type declared on the mention
     * @property expected The type expected by the matched relation
     */
    data class TypeMismatch(
        val role: MentionRole,
        val actual: String,
        val expected: String,
    ) : ProjectionFailureReason {
        override fun describe(): String =
            "${role.name.lowercase()} type '$actual' does not match expected '$expected'"
    }

    /**
     * A subject or object mention could not be resolved to an entity id.
     *
     * @property role The role of the unresolved mention
     * @property span The text span of the unresolved mention, if known
     */
    data class UnresolvedMention(
        val role: MentionRole,
        val span: String? = null,
    ) : ProjectionFailureReason {
        override fun describe(): String =
            "unresolved ${role.name.lowercase()} mention${span?.let { " '$it'" } ?: ""}"
    }

    /**
     * The proposition was rejected by the projection policy.
     *
     * @property detail Why the policy rejected the proposition
     */
    data class PolicyRejected(val detail: String) : ProjectionFailureReason {
        override fun describe(): String = "policy rejected: $detail"
    }
}

/**
 * The outcome of attempting to project a single proposition.
 *
 * @param T The type produced on success
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
    val structuredReason: ProjectionFailureReason? = null,
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
    val structuredReason: ProjectionFailureReason? = null,
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

    /**
     * Render a human-readable summary of these results: how many propositions
     * were projected, skipped, and failed, followed by a grouped breakdown of
     * the reasons (using the structured reason where present, falling back to
     * the string reason otherwise).
     */
    fun summary(): String {
        val header = "projected $successCount of $totalCount, $skipCount skipped, $failureCount failed"
        val reasons = (skipped.map { "skipped: ${it.structuredReason?.describe() ?: it.reason}" } +
            failures.map { "failed: ${it.structuredReason?.describe() ?: it.reason}" })
        if (reasons.isEmpty()) {
            return header
        }
        val breakdown = reasons
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString("; ") { (reason, count) -> "$reason (x$count)" }
        return "$header. Reasons: $breakdown"
    }
}
