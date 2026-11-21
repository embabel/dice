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

import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory

/** Resolve host attribution for the current write. Return only opaque tokens and safe reason codes. */
@ApiStatus.Experimental
fun interface AuditContextProvider {
    fun attributionFor(contextId: String): AuditAttribution

    companion object {
        @JvmField
        val NONE = AuditContextProvider { AuditAttribution() }
    }
}

/**
 * Strict mode propagates audit failures. Best-effort mode can continue a write only after
 * invalidating historical coverage. A joining sink treats writes and in-transaction verdicts
 * strictly. A terminal event is delivered after commit and can report failure without taking
 * back the completed run. Log messages omit exception details and payloads.
 */
@ApiStatus.Experimental
enum class AuditFailurePolicy {
    FAIL_WRITE, LOG_AND_CONTINUE,
}

internal fun writeAudit(
    sink: AuditSink,
    policy: AuditFailurePolicy,
    contextId: String,
    affectsBeliefSet: Boolean,
    action: () -> Unit,
) {
    try {
        action()
    } catch (failure: Exception) {
        if (affectsBeliefSet && sink.transactionParticipation == AuditTransactionParticipation.BEST_EFFORT) {
            try {
                sink.invalidateCoverage(contextId)
            } catch (invalidating: Exception) {
                if (invalidating !== failure) failure.addSuppressed(invalidating)
                throw failure
            }
        }
        if (policy == AuditFailurePolicy.FAIL_WRITE ||
            (affectsBeliefSet && sink.transactionParticipation == AuditTransactionParticipation.JOINS_HOST_TRANSACTION)
        ) throw failure
        auditLogger.warn("An opt-in DICE audit write failed ({})", failure.javaClass.simpleName)
    }
}

private val auditLogger = LoggerFactory.getLogger(AuditSink::class.java)
