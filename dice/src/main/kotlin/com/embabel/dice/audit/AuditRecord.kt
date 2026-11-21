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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.temporal.TemporalMetadata
import org.jetbrains.annotations.ApiStatus
import java.time.Instant
import java.util.List.copyOf

/** Only the tracked proposition fields. Grounding, metadata, and provenance never enter history. */
@ApiStatus.Experimental
data class AuditedProposition(
    val id: String,
    val contextId: String,
    val text: String,
    val status: PropositionStatus,
    val confidence: Double,
    val importance: Double,
    val pinned: Boolean,
    val temporal: TemporalMetadata?,
) {
    internal fun snapshot() = copy(temporal = temporal?.let {
        it.copy(supersedes = copyOf(it.supersedes), contradicts = copyOf(it.contradicts))
    })

    companion object {
        @JvmStatic
        fun from(proposition: Proposition): AuditedProposition = AuditedProposition(
            proposition.id, proposition.contextId.value, proposition.text, proposition.status,
            proposition.confidence, proposition.importance, proposition.pinned, proposition.temporal,
        ).snapshot()
    }
}

/** A pseudonym minted by the host. Supply a token that cannot be resolved to a person's identity. */
@ApiStatus.Experimental
data class AuditActorRef(val token: String) {
    init {
        require(token.length <= 256 && opaqueToken.matches(token)) { "Invalid opaque audit actor token" }
    }

    override fun toString(): String = "AuditActorRef"

    companion object {
        private val opaqueToken = Regex("[A-Za-z0-9._:~-]+")
    }
}

@ApiStatus.Experimental
enum class AuditRunKind { EXTRACTION, COLLECTOR, DECAY, DRIFT }

@ApiStatus.Experimental
data class AuditRunRef(val kind: AuditRunKind, val id: String) {
    init {
        require(id.isNotBlank() && id.length <= 256) { "Invalid opaque audit run identifier" }
    }

    override fun toString(): String = "AuditRunRef(kind=$kind)"
}

/** Supply only opaque references and reason codes that are safe to retain in the audit log. */
@ApiStatus.Experimental
data class AuditAttribution @JvmOverloads constructor(
    val actor: AuditActorRef? = null,
    val run: AuditRunRef? = null,
    val reason: String? = null,
)

@ApiStatus.Experimental
enum class AuditKind {
    CREATED, CHANGED, PINNED, UNPINNED, DELETED,
    GATE_REJECTED, GATE_REVIEW, GATE_DEMOTED, GATE_PROJECTION_SKIPPED,
    EXTRACTION_RUN_ENDED, ERASURE,
}

@ApiStatus.Experimental
enum class AuditField { TEXT, STATUS, CONFIDENCE, IMPORTANCE, PINNED, TEMPORAL }

/** Values are String, PropositionStatus, Double, Boolean, or TemporalMetadata by field. */
@ApiStatus.Experimental
data class AuditFieldChange(val field: AuditField, val oldValue: Any?, val newValue: Any?)

/** The unsequenced payload of one write or decision. The sink assigns transaction time and sequence. */
@ApiStatus.Experimental
data class AuditEntry @JvmOverloads constructor(
    val kind: AuditKind,
    val propositionId: String? = null,
    val before: AuditedProposition? = null,
    val after: AuditedProposition? = null,
    val attribution: AuditAttribution = AuditAttribution(),
    val erasedRecordCount: Long? = null,
    val erasedBaselineCount: Long? = null,
) {
    val reason: String? get() = attribution.reason

    val changes: List<AuditFieldChange>
        get() = listOf(
            AuditFieldChange(AuditField.TEXT, before?.text, after?.text),
            AuditFieldChange(AuditField.STATUS, before?.status, after?.status),
            AuditFieldChange(AuditField.CONFIDENCE, before?.confidence, after?.confidence),
            AuditFieldChange(AuditField.IMPORTANCE, before?.importance, after?.importance),
            AuditFieldChange(AuditField.PINNED, before?.pinned, after?.pinned),
            AuditFieldChange(AuditField.TEMPORAL, before?.temporal, after?.temporal),
        ).filter { it.oldValue != it.newValue }

    init {
        when (kind) {
            AuditKind.CREATED -> require(before == null && after != null)
            AuditKind.DELETED -> require(before != null && after == null)
            AuditKind.CHANGED, AuditKind.PINNED, AuditKind.UNPINNED ->
                require(before != null && after != null && before != after)
            else -> require(before == null && after == null)
        }
        require(before == null || before.id == propositionId)
        require(after == null || after.id == propositionId)
        if (kind == AuditKind.ERASURE) {
            require(propositionId == null && attribution.run == null && attribution.reason == null)
            require(erasedRecordCount != null && erasedRecordCount >= 0)
            require(erasedBaselineCount != null && erasedBaselineCount >= 0)
        } else {
            require(erasedRecordCount == null && erasedBaselineCount == null)
        }
    }

    override fun toString(): String = "AuditEntry(kind=$kind)"
}

@ApiStatus.Experimental
data class AuditRecord(val contextId: String, val sequence: Long, val recordedAt: Instant, val entry: AuditEntry) {
    val kind: AuditKind get() = entry.kind
    val propositionId: String? get() = entry.propositionId
    val before: AuditedProposition? get() = entry.before
    val after: AuditedProposition? get() = entry.after
    val attribution: AuditAttribution get() = entry.attribution
    val reason: String? get() = entry.reason
    val changes: List<AuditFieldChange> get() = entry.changes
    val erasedRecordCount: Long? get() = entry.erasedRecordCount
    val erasedBaselineCount: Long? get() = entry.erasedBaselineCount
}
