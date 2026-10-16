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
package com.embabel.dice.agent

import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.temporal.TemporalMetadata
import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectedSource(val descriptor: String?, val locator: String?)

/** TEMPORAL approves time windows only; cross-row relationship IDs are never exposed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
class ProjectedTemporal internal constructor(temporal: TemporalMetadata) {
    val observedAt: Instant? = temporal.observedAt
    val validFrom: Instant? = temporal.validFrom
    val validTo: Instant? = temporal.validTo
    val invalidatedAt: Instant? = temporal.invalidatedAt
}

/** Allowlisted output only. No raw proposition, internal key, grant or policy envelope is retained. */
@JsonInclude(JsonInclude.Include.NON_NULL)
class ProjectedMemory internal constructor(
    val text: String?,
    val owner: String?,
    val id: String?,
    val confidence: Double?,
    val effectiveConfidence: Double?,
    val score: Double?,
    entityHandles: List<String>,
    sources: List<ProjectedSource>,
    metadata: Map<String, Any?>,
    val status: PropositionStatus?,
    val level: Int?,
    val created: Instant?,
    val contentRevised: Instant?,
    val metadataRevised: Instant?,
    val lastAccessed: Instant?,
    val importance: Double?,
    val reinforceCount: Int?,
    val trustScore: Double?,
    val pinned: Boolean?,
    val temporal: ProjectedTemporal?,
) {
    val entityHandles: List<String> = immutablePolicyList(entityHandles)
    val sources: List<ProjectedSource> = immutablePolicyList(sources)
    val metadata: Map<String, Any?> = copyJsonObject(metadata, IdentityHashMap(), 0)

    init {
        require(listOfNotNull(confidence, effectiveConfidence, score, importance, trustScore).all { it.isFinite() }) {
            "Non-finite projected number"
        }
    }

    companion object {
        /** The policy adapter must validate its read plan before obtaining these rows. */
        internal fun from(
            grant: RowGrant, proposition: Proposition, score: Double?, asOf: Instant, rankingDecayK: Double,
        ): ProjectedMemory {
            require(grant.key == PropositionKey(proposition.contextId.value, proposition.id)) { "Unexpected projected row" }
            require(rankingDecayK.isFinite() && rankingDecayK >= 0) { "Invalid projection decay" }
            val projection = grant.projection
            val fields = projection.fields
            val handles = LinkedHashSet(projection.entityHandles)
            if (MemoryField.ENTITY_IDS in fields) handles.addAll(proposition.mentions.mapNotNull { it.resolvedId })
            val sources = projection.sources.mapNotNull { approved ->
                if (!approved.descriptor && !approved.locator) return@mapNotNull null
                val source = proposition.provenanceEntries.firstOrNull { it.locator.key() == approved.sourceId }
                    ?: return@mapNotNull null
                ProjectedSource(
                    descriptor = if (approved.descriptor) source.locator.display else null,
                    locator = if (approved.locator) source.locator.key() else null,
                )
            }
            val metadata = LinkedHashMap<String, Any?>()
            projection.metadataKeys.forEach { key ->
                if (proposition.metadata.containsKey(key)) metadata[key] = proposition.metadata[key]
            }
            return ProjectedMemory(
                text = when (val text = projection.text) {
                    ApprovedText.Omitted -> null
                    ApprovedText.Stored -> proposition.text
                    is ApprovedText.Literal -> text.value
                },
                owner = proposition.contextId.value.takeIf { MemoryField.OWNER in fields },
                id = proposition.id.takeIf { MemoryField.ID in fields },
                confidence = proposition.confidence.takeIf { MemoryField.RAW_CONFIDENCE in fields },
                effectiveConfidence = if (MemoryField.EFFECTIVE_CONFIDENCE in fields)
                    proposition.effectiveConfidenceAt(asOf, rankingDecayK) else null,
                score = score.takeIf { MemoryField.SCORE in fields },
                entityHandles = handles.toList(), sources = sources, metadata = metadata,
                status = proposition.status.takeIf { MemoryField.STATUS in fields },
                level = proposition.level.takeIf { MemoryField.LEVEL in fields },
                created = proposition.created.takeIf { MemoryField.CREATED in fields },
                contentRevised = proposition.contentRevised.takeIf { MemoryField.CONTENT_REVISED in fields },
                metadataRevised = proposition.metadataRevised.takeIf { MemoryField.METADATA_REVISED in fields },
                lastAccessed = proposition.lastAccessed.takeIf { MemoryField.LAST_ACCESSED in fields },
                importance = proposition.importance.takeIf { MemoryField.IMPORTANCE in fields },
                reinforceCount = proposition.reinforceCount.takeIf { MemoryField.REINFORCE_COUNT in fields },
                trustScore = if (MemoryField.TRUST_SCORE in fields)
                    (proposition.metadata[DiceMetadataKeys.TRUST_SCORE] as? Number)?.toDouble() else null,
                pinned = proposition.pinned.takeIf { MemoryField.PINNED in fields },
                temporal = if (MemoryField.TEMPORAL in fields) proposition.temporal?.let(::ProjectedTemporal) else null,
            )
        }
    }
}

private fun copyJsonObject(value: Map<*, *>, active: IdentityHashMap<Any, Boolean>, depth: Int): Map<String, Any?> {
    require(depth <= 64 && active.put(value, true) == null) { "Cyclic or excessively nested projected metadata" }
    try {
        val result = LinkedHashMap<String, Any?>()
        value.forEach { (key, entry) ->
            require(key is String) { "Projected metadata keys must be strings" }
            result[key] = copyJsonValue(entry, active, depth + 1)
        }
        return Collections.unmodifiableMap(result)
    } finally {
        active.remove(value)
    }
}

private fun copyJsonValue(value: Any?, active: IdentityHashMap<Any, Boolean>, depth: Int): Any? = when (value) {
    null, is String, is Boolean, is Byte, is Short, is Int, is Long -> value
    is BigInteger -> value.also { require(it.javaClass == BigInteger::class.java) { "Unsupported projected number" } }
    is BigDecimal -> value.also { require(it.javaClass == BigDecimal::class.java) { "Unsupported projected number" } }
    is Double -> value.also { require(it.isFinite()) { "Non-finite projected metadata" } }
    is Float -> value.also { require(it.isFinite()) { "Non-finite projected metadata" } }
    is Map<*, *> -> copyJsonObject(value, active, depth)
    is List<*> -> {
        require(depth <= 64 && active.put(value, true) == null) { "Cyclic or excessively nested projected metadata" }
        try { immutablePolicyList(value.map { copyJsonValue(it, active, depth + 1) }) }
        finally { active.remove(value) }
    }
    else -> throw IllegalArgumentException("Projected metadata must be JSON-safe")
}
