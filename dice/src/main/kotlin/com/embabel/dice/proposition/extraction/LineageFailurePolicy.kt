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
package com.embabel.dice.proposition.extraction

import org.jetbrains.annotations.ApiStatus

/**
 * What happens when a run's lineage cannot be written.
 *
 * A host asks for attribution by giving an extraction a run. This says how much that ask is worth:
 * whether an extraction whose lineage could not be recorded is a failure, or a success with a gap
 * in the audit.
 *
 * **[STRICT] is the default, and the reason is that silence is the wrong answer here.** Lineage is
 * an audit record. An operator who turns it on and gets no error believes every stored claim can be
 * traced to the run that produced it, and the one moment that belief is worth something — after an
 * incident, reconstructing what a run wrote — is the moment a silent gap makes it worthless. A
 * warning in a log is not a control: nobody reads it in time, and the extraction that dropped the
 * record reported success. Asking for attribution and getting none should be loud.
 *
 * [LENIENT] exists for the host that has decided the claims matter more than their attribution and
 * is willing to say so in configuration. It is a deliberate downgrade, chosen once, in the open.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class LineageFailurePolicy {

    /**
     * A lineage write that cannot happen fails the extraction.
     *
     * Two things fail under this policy, and they are the two ways attribution can go missing:
     *
     * - An analysis carries a run and no [PropositionRunLinkStore] is bound. The host asked for
     *   attribution and nothing can record it. That is a wiring mistake: it is the same on every
     *   call and it will never fix itself.
     * - The link write itself throws. A run that does not exist, a proposition in another tenant, a
     *   database that will not take the write.
     *
     * The exception reaches the caller. What that costs depends on who owns the transaction: with
     * no ambient transaction the claims were already saved and stand, so the caller learns that
     * stored claims are unattributed. Inside a host's `@Transactional`, the claims and the lineage
     * share that transaction's fate and the failure rolls both back — which is what a host running
     * extraction under strict attribution is asking for.
     */
    STRICT,

    /**
     * A lineage write that cannot happen is logged, and the extraction carries on.
     *
     * The claims are stored and the audit has a gap. Nothing tells a later reader that the gap is
     * there, so a host choosing this is accepting that "no run produced this claim" and "the record
     * was lost" look identical from the outside.
     */
    LENIENT,
    ;

    companion object {

        /** What an extractor uses when a host binds lineage without naming a policy. */
        @JvmField
        val DEFAULT: LineageFailurePolicy = STRICT
    }
}

/**
 * A run's lineage could not be recorded, under [LineageFailurePolicy.STRICT].
 *
 * Thrown when an analysis carries a run and no [PropositionRunLinkStore] is bound, or when the link
 * write failed. In the second case the store's own exception is the [cause] — a scope rejection and
 * a database outage both arrive here, and the cause is what tells them apart.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property key The run the lineage was for
 */
@ApiStatus.Experimental
class LineageNotRecordedException(
    val key: ExtractionRunKey,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
