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

import com.embabel.dice.common.SourceAnalysisContext
import org.jetbrains.annotations.ApiStatus
import java.util.function.Supplier

/** Adds run attribution to synchronous writes and gate events from one context. */
@ApiStatus.Experimental
object AuditRunContext {
    private val active = ThreadLocal<Binding>()

    /** A binding is thread-confined and restored even if the action throws. */
    @JvmStatic
    fun <T> withRun(contextId: String, run: AuditRunRef, action: Supplier<T>): T {
        require(contextId.isNotBlank())
        val previous = active.get()
        active.set(Binding(contextId, run))
        try {
            return action.get()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    internal fun runFor(contextId: String): AuditRunRef? =
        active.get()?.takeIf { it.contextId == contextId }?.run

    private data class Binding(val contextId: String, val run: AuditRunRef)
}

internal fun <T> withExtractionAuditRun(context: SourceAnalysisContext?, action: () -> T): T {
    val run = context?.currentRun ?: return action()
    return AuditRunContext.withRun(context.contextId.value, AuditRunRef(AuditRunKind.EXTRACTION, run.runId), action)
}

internal fun AuditContextProvider.resolveAttribution(contextId: String): AuditAttribution {
    val attribution = attributionFor(contextId)
    return if (attribution.run != null) attribution else attribution.copy(run = AuditRunContext.runFor(contextId))
}
