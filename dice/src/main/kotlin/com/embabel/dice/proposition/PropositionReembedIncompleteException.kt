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

/**
 * A [PropositionRepository.reembedAll] that re-embedded some propositions but not all of them.
 *
 * Thrown only after the run has finished: every proposition that COULD be embedded was written,
 * and a backend that owns a vector index has put that index back. So catching this is safe — the
 * store is consistent, just not uniformly on the current model. The failed propositions are still
 * stored; a backend whose index no longer matches their old vector keeps them out of vector search
 * rather than leaving a vector of the wrong shape.
 *
 * Re-running [PropositionRepository.reembedAll] is the retry: it rewrites every vector again, so
 * the ones that succeeded are rewritten harmlessly and the ones that failed get another attempt.
 *
 * [cause] is the first failure; later ones (up to [MAX_SUPPRESSED]) are attached as suppressed
 * exceptions, so a run against an embedding service that is down does not carry ten thousand
 * identical stack traces.
 *
 * @param report what the run did achieve: [PropositionReembedReport.propositions] counts only the
 * propositions actually re-embedded
 * @param failedPropositionIds ids of the propositions whose re-embed failed, in the order attempted
 * @param notAttemptedPropositionIds ids of the propositions not tried because the run stopped after
 * too many consecutive failures (see [ConsecutiveFailureBreaker]); they are handled as failed ones are
 */
class PropositionReembedIncompleteException @JvmOverloads constructor(
    val report: PropositionReembedReport,
    val failedPropositionIds: List<String>,
    cause: Throwable?,
    val notAttemptedPropositionIds: List<String> = emptyList(),
) : RuntimeException(
    """
    Re-embed incomplete: ${failedPropositionIds.size} proposition(s) failed, ${notAttemptedPropositionIds.size} were not
    attempted after repeated failures, and ${report.propositions} were re-embedded (indexRecreated=${report.indexRecreated}).
    Re-run reembedAll to retry. Failed ids: ${abbreviate(failedPropositionIds)}
    """.trimIndent().replace("\n", " "),
    cause,
) {

    /** How many propositions were re-embedded before and after the failures. */
    val reembedded: Int get() = report.propositions

    companion object {

        /** The most failures attached as suppressed exceptions beyond the [cause]. */
        const val MAX_SUPPRESSED = 10

        private const val IDS_IN_MESSAGE = 20

        /**
         * Build the exception from per-proposition failures (id to what went wrong), taking the
         * first as the cause and suppressing a bounded number of the rest.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            report: PropositionReembedReport,
            failures: Map<String, Throwable>,
            notAttempted: List<String> = emptyList(),
        ): PropositionReembedIncompleteException {
            val causes = failures.values.toList()
            return PropositionReembedIncompleteException(
                report,
                failures.keys.toList(),
                causes.firstOrNull(),
                notAttempted,
            ).apply {
                causes.drop(1).take(MAX_SUPPRESSED).forEach { addSuppressed(it) }
            }
        }

        private fun abbreviate(ids: List<String>): String =
            if (ids.size <= IDS_IN_MESSAGE) ids.joinToString()
            else "${ids.take(IDS_IN_MESSAGE).joinToString()} … and ${ids.size - IDS_IN_MESSAGE} more"
    }
}
