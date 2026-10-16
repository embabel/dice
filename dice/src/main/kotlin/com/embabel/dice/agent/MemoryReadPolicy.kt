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

import com.embabel.dice.proposition.ApprovedText
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadBounds
import java.util.Collections

/** Who is reading, why, and under which policy version. It is carried with the policy for auditing; nothing here checks it. */
data class ReadPolicyStamp(val principal: String, val purpose: String, val policyId: String, val version: String) {
    init {
        require(listOf(principal, purpose, policyId, version).none { it.isBlank() }) { "Blank policy identity" }
    }
}

/** Whether fields a row's grant hides may still affect which rows match and how they rank. */
enum class HiddenFieldInfluence {
    /** Hidden fields may not affect matching or ranking. */
    STRICT,

    /** Hidden fields may affect matching and ranking but never appear in output. */
    ALLOW_SEARCH_INFLUENCE,
}

/** A proposition field a grant can approve for output and, under strict policy, for matching. */
enum class MemoryField {
    OWNER, ID, RAW_CONFIDENCE, EFFECTIVE_CONFIDENCE, SCORE, ENTITY_IDS, STATUS, LEVEL,
    CREATED, CONTENT_REVISED, METADATA_REVISED, LAST_ACCESSED, IMPORTANCE, REINFORCE_COUNT,
    TRUST_SCORE, PINNED, TEMPORAL,
}

/** A provenance source a grant approves; `descriptor` shows its display name and `locator` its key. */
data class AllowedSource(val sourceId: String, val descriptor: Boolean, val locator: Boolean) {
    init { require(sourceId.isNotBlank()) { "Blank source ID" } }
}

/** The fields, metadata keys, entity handles and sources one row may show. Java callers start from `text(...)` and use the withers; Kotlin callers use the block. */
class RowProjection private constructor(
    val text: ApprovedText,
    fields: Set<MemoryField>,
    metadataKeys: Set<String>,
    entityHandles: Set<String>,
    sources: List<AllowedSource>,
) {
    val fields: Set<MemoryField> = immutablePolicySet(fields)
    val metadataKeys: Set<String> = immutablePolicySet(metadataKeys)
    val entityHandles: Set<String> = immutablePolicySet(entityHandles)
    val sources: List<AllowedSource> = immutablePolicyList(sources)

    init {
        require(this.metadataKeys.none { it.isBlank() } && this.entityHandles.none { it.isBlank() }) { "Blank projection key" }
        require(this.sources.map { it.sourceId }.distinct().size == this.sources.size) { "Duplicate source permission" }
    }

    fun withFields(fields: Set<MemoryField>) = RowProjection(text, fields, metadataKeys, entityHandles, sources)
    fun withMetadataKeys(keys: Set<String>) = RowProjection(text, fields, keys, entityHandles, sources)
    fun withEntityHandles(handles: Set<String>) = RowProjection(text, fields, metadataKeys, handles, sources)
    fun withSources(sources: List<AllowedSource>) = RowProjection(text, fields, metadataKeys, entityHandles, sources)

    override fun equals(other: Any?): Boolean = other is RowProjection && text == other.text && fields == other.fields &&
        metadataKeys == other.metadataKeys && entityHandles == other.entityHandles && sources == other.sources
    override fun hashCode(): Int = listOf(text, fields, metadataKeys, entityHandles, sources).hashCode()

    class Configuration {
        var fields: Set<MemoryField> = emptySet()
        var metadataKeys: Set<String> = emptySet()
        var entityHandles: Set<String> = emptySet()
        var sources: List<AllowedSource> = emptyList()
    }

    companion object {
        @JvmStatic
        fun text(text: ApprovedText) = RowProjection(text, emptySet(), emptySet(), emptySet(), emptyList())

        @JvmSynthetic
        operator fun invoke(text: ApprovedText, configure: Configuration.() -> Unit): RowProjection {
            val config = Configuration().apply(configure)
            return RowProjection(text, config.fields, config.metadataKeys, config.entityHandles, config.sources)
        }
    }
}

/** Permission to read one row through a projection, and optionally to search its stored embedding. */
class RowGrant private constructor(
    val key: PropositionKey,
    val projection: RowProjection,
    val storedEmbeddingAllowed: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is RowGrant && key == other.key &&
        projection == other.projection && storedEmbeddingAllowed == other.storedEmbeddingAllowed
    override fun hashCode(): Int = listOf(key, projection, storedEmbeddingAllowed).hashCode()

    companion object {
        @JvmStatic
        fun of(key: PropositionKey, projection: RowProjection, storedEmbeddingAllowed: Boolean): RowGrant {
            require(key.contextIdValue.isNotBlank() && key.propositionId.isNotBlank()) { "Blank row key" }
            require(!storedEmbeddingAllowed || projection.text == ApprovedText.Stored) {
                "Stored embeddings require approved full stored text"
            }
            return RowGrant(key, projection, storedEmbeddingAllowed)
        }
    }
}

/** The rows a reader may see, each with its projection, in the order results are listed. */
class RowPolicy private constructor(
    val stamp: ReadPolicyStamp,
    grants: Collection<RowGrant>,
    val influence: HiddenFieldInfluence,
) {
    val grants: List<RowGrant> = immutablePolicyList(grants)

    /** Validate before any backend operation; scope/membership checks apply in either influence mode. */
    @JvmOverloads
    fun validate(
        spec: ScopedReadSpec,
        order: PropositionQuery.OrderBy = PropositionQuery.OrderBy.NONE,
        keywordSearch: Boolean = false,
        vectorSearch: Boolean = false,
        scoreInputs: Set<MemoryField> = emptySet(),
        metadataInputs: Set<String> = emptySet(),
        sourceInputs: List<AllowedSource> = emptyList(),
    ) {
        val scope = spec.scope
        require(grants.all { it.key.contextIdValue in scope.contextIdValues }) { "Grant outside read scope" }
        if (influence == HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE) return
        val required = LinkedHashSet(scoreInputs)
        spec.fragments.forEach { query ->
            if (query.contextId != null) required += MemoryField.OWNER
            if (query.entityId != null || query.anyEntityIds != null || query.allEntityIds != null) required += MemoryField.ENTITY_IDS
            if (query.statuses != null) required += MemoryField.STATUS
            if (query.minLevel != null || query.maxLevel != null) required += MemoryField.LEVEL
            if (query.createdAfter != null || query.createdBefore != null) required += MemoryField.CREATED
            if (query.revisedAfter != null || query.revisedBefore != null) {
                // Revision filters read lastTouched, the later of the two revision times.
                required += MemoryField.CONTENT_REVISED
                required += MemoryField.METADATA_REVISED
            }
            if (query.accessedAfter != null || query.accessedBefore != null) required += MemoryField.LAST_ACCESSED
            if (query.minConfidence != null) required += MemoryField.RAW_CONFIDENCE
            if (query.minEffectiveConfidence != null || query.belowEffectiveConfidence != null) required += MemoryField.EFFECTIVE_CONFIDENCE
            if (query.minImportance != null) required += MemoryField.IMPORTANCE
            if (query.minReinforceCount != null) required += MemoryField.REINFORCE_COUNT
            if (query.minTrustScore != null) required += MemoryField.TRUST_SCORE
            if (query.pinned != null) required += MemoryField.PINNED
        }
        when (order) {
            PropositionQuery.OrderBy.NONE -> Unit
            PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC -> required += MemoryField.EFFECTIVE_CONFIDENCE
            PropositionQuery.OrderBy.CREATED_DESC -> required += MemoryField.CREATED
            PropositionQuery.OrderBy.REVISED_DESC -> {
                required += MemoryField.CONTENT_REVISED
                required += MemoryField.METADATA_REVISED
            }
            PropositionQuery.OrderBy.LAST_ACCESSED_DESC -> required += MemoryField.LAST_ACCESSED
            PropositionQuery.OrderBy.REINFORCE_COUNT_DESC -> required += MemoryField.REINFORCE_COUNT
            PropositionQuery.OrderBy.IMPORTANCE_DESC -> required += MemoryField.IMPORTANCE
        }
        // A grant for a derived metric approves that metric's computation, not its raw inputs.
        // EFFECTIVE_CONFIDENCE uses the frozen asOf/decayK; TRUST_SCORE approves only its cache value.
        grants.forEach { grant ->
            require(grant.projection.fields.containsAll(required)) { "Strict read depends on hidden fields" }
            require(grant.projection.metadataKeys.containsAll(metadataInputs)) { "Strict read depends on hidden metadata" }
            require(sourceInputs.all { input ->
                grant.projection.sources.any { approved -> approved.sourceId == input.sourceId &&
                    (!input.descriptor || approved.descriptor) && (!input.locator || approved.locator) }
            }) { "Strict read depends on hidden provenance" }
            require(!keywordSearch || grant.projection.text != ApprovedText.Omitted) { "Strict search requires approved text" }
            require(!vectorSearch || grant.storedEmbeddingAllowed) { "Strict vector search requires stored embedding permission" }
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            stamp: ReadPolicyStamp, grants: Collection<RowGrant>,
            influence: HiddenFieldInfluence = HiddenFieldInfluence.STRICT,
        ): RowPolicy {
            val snapshot = grants.toList()
            require(snapshot.size <= StrictReadBounds.MAX_ROWS) { "Policy exceeds 1000 grants" }
            val unique = LinkedHashMap<PropositionKey, RowGrant>()
            snapshot.forEach { grant ->
                val prior = unique.putIfAbsent(grant.key, grant)
                require(prior == null || prior == grant) { "Conflicting duplicate grant" }
            }
            require(unique.values.sumOf {
                (it.projection.text as? ApprovedText.Literal)?.value?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            } <= StrictReadBounds.MAX_APPROVED_TEXT_BYTES) { "Policy projected text exceeds 1MiB" }
            return RowPolicy(stamp, unique.values, influence)
        }
    }
}

internal fun <T> immutablePolicyList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> immutablePolicySet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
