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
package com.embabel.dice.audit

import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.ExtractionRunTransitioned
import com.embabel.dice.common.PropositionDemoted
import com.embabel.dice.common.PropositionProjectionSkipped
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.proposition.Proposition
import org.jetbrains.annotations.ApiStatus

/** Adapts gate verdicts and terminal extraction events without duplicating tracked repository writes. */
@ApiStatus.Experimental
class AuditEventListener @JvmOverloads constructor(
    private val sink: AuditSink,
    private val contextProvider: AuditContextProvider = AuditContextProvider.NONE,
    private val failurePolicy: AuditFailurePolicy = AuditFailurePolicy.FAIL_WRITE,
) : DiceEventListener {

    override fun onEvent(event: DiceEvent) {
        when (event) {
            is PropositionRejected -> gate(AuditKind.GATE_REJECTED, event.proposition, event.reason)
            is PropositionRoutedToReview -> gate(AuditKind.GATE_REVIEW, event.proposition, event.reason)
            is PropositionDemoted -> gate(AuditKind.GATE_DEMOTED, event.proposition, event.reason)
            is PropositionProjectionSkipped -> gate(AuditKind.GATE_PROJECTION_SKIPPED, event.proposition, event.reason)
            is ExtractionRunTransitioned -> {
                val run = event.run
                val contextId = run.contextId.value
                append(contextId) {
                    val host = contextProvider.attributionFor(contextId)
                    AuditEntry(AuditKind.EXTRACTION_RUN_ENDED, attribution = AuditAttribution(
                        host.actor ?: run.subjectRefs.actor?.let { AuditActorRef(it.token) },
                        AuditRunRef(AuditRunKind.EXTRACTION, run.ref.runId),
                        run.status.name,
                    ))
                }
            }
        }
    }

    private fun gate(kind: AuditKind, proposition: Proposition, reason: String) {
        val contextId = proposition.contextId.value
        append(contextId) {
            AuditEntry(kind, proposition.id, attribution = contextProvider.attributionFor(contextId).copy(reason = reason))
        }
    }

    private fun append(contextId: String, entry: () -> AuditEntry) =
        writeAudit(sink, failurePolicy, contextId, affectsBeliefSet = false) { sink.append(contextId, entry()) }
}
