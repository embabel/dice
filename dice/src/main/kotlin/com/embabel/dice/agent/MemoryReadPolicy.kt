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

import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import java.util.Collections

@DslMarker
internal annotation class RowProjectionDsl

/** Trusted host identity. Policy content is always revalidated, even when this stamp is reused. */
data class ReadPolicyStamp(val principal: String, val purpose: String, val policyId: String, val version: String) {
    init {
        require(listOf(principal, purpose, policyId, version).none { it.isBlank() }) { "Blank policy identity" }
    }
}

enum class HiddenFieldInfluence { STRICT, ALLOW_SEARCH_INFLUENCE }

enum class MemoryField {
    OWNER, ID, RAW_CONFIDENCE, EFFECTIVE_CONFIDENCE, SCORE, ENTITY_IDS, STATUS, LEVEL,
    CREATED, CONTENT_REVISED, METADATA_REVISED, LAST_ACCESSED, IMPORTANCE, REINFORCE_COUNT,
    TRUST_SCORE, PINNED, TEMPORAL,
}

sealed interface ApprovedText {
    data object Omitted : ApprovedText
    data object Stored : ApprovedText
    data class Literal(val value: String) : ApprovedText {
        init { require(value.toByteArray(Charsets.UTF_8).size <= MAX_LITERAL_BYTES) { "Projected text exceeds 1MiB" } }
    }
}

data class AllowedSource(val sourceId: String, val descriptor: Boolean, val locator: Boolean) {
    init { require(sourceId.isNotBlank()) { "Blank source ID" } }
}

/** A deny-by-default allowlist. Every wither validates and copies its input. */
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
        require((this.fields as Set<*>).all { it is MemoryField }) { "Invalid projection field" }
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

    class Projecting internal constructor(
        private val text: ApprovedText,
        fields: Set<MemoryField> = emptySet(),
        metadataKeys: Set<String> = emptySet(),
        entityHandles: Set<String> = emptySet(),
        sources: List<AllowedSource> = emptyList(),
    ) {
        private val fields = immutablePolicySet(fields)
        private val metadataKeys = immutablePolicySet(metadataKeys)
        private val entityHandles = immutablePolicySet(entityHandles)
        private val sources = immutablePolicyList(sources)

        fun withFields(fields: Set<MemoryField>): Projecting = Projecting(text, fields, metadataKeys, entityHandles, sources)
        fun withMetadataKeys(keys: Set<String>): Projecting = Projecting(text, fields, keys, entityHandles, sources)
        fun withEntityHandles(handles: Set<String>): Projecting = Projecting(text, fields, metadataKeys, handles, sources)
        fun withSources(sources: List<AllowedSource>): Projecting = Projecting(text, fields, metadataKeys, entityHandles, sources)
        fun projection(): RowProjection = RowProjection(text, fields, metadataKeys, entityHandles, sources)
    }

    @RowProjectionDsl
    class Configuration internal constructor() {
        var fields: Set<MemoryField> = emptySet()
        var metadataKeys: Set<String> = emptySet()
        var entityHandles: Set<String> = emptySet()
        var sources: List<AllowedSource> = emptyList()
    }

    companion object {
        @JvmStatic
        fun text(text: ApprovedText) = RowProjection(text, emptySet(), emptySet(), emptySet(), emptyList())

        @JvmStatic
        fun projecting(text: ApprovedText): Projecting = Projecting(text)

        @JvmSynthetic
        operator fun invoke(text: ApprovedText, configure: Configuration.() -> Unit): RowProjection {
            val config = Configuration().apply(configure)
            return RowProjection(text, config.fields, config.metadataKeys, config.entityHandles, config.sources)
        }
    }
}

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
        fun of(
            key: PropositionKey, projection: RowProjection,
            sourceEligibilityAttested: Boolean, storedEmbeddingAllowed: Boolean,
        ): RowGrant {
            require(key.contextIdValue.isNotBlank() && key.propositionId.isNotBlank()) { "Blank row key" }
            require(sourceEligibilityAttested) { "Every row requires source eligibility attestation" }
            require(!storedEmbeddingAllowed || projection.text == ApprovedText.Stored) {
                "Stored embeddings require approved full stored text"
            }
            return RowGrant(key, projection, storedEmbeddingAllowed)
        }
    }
}

/** Finite, ordered grants. No global cache is keyed only by a caller's version string. */
class RowPolicy private constructor(
    val stamp: ReadPolicyStamp,
    grants: Collection<RowGrant>,
    val influence: HiddenFieldInfluence,
) {
    val grants: List<RowGrant> = immutablePolicyList(grants)

    /** Validate before any backend operation; scope/membership checks apply in either influence mode. */
    @JvmOverloads
    fun validate(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        order: ReadOrder = ReadOrder.NONE,
        keywordSearch: Boolean = false,
        vectorSearch: Boolean = false,
        scoreInputs: Set<MemoryField> = emptySet(),
        metadataInputs: Set<String> = emptySet(),
        sourceInputs: List<AllowedSource> = emptyList(),
    ) {
        require(grants.all { it.key.contextIdValue in scope.contextIdValues }) { "Grant outside read scope" }
        if (influence == HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE) return
        val required = LinkedHashSet(scoreInputs)
        spec.fragments.forEach { query ->
            if (query.entityId != null || query.anyEntityIds != null || query.allEntityIds != null) required += MemoryField.ENTITY_IDS
            if (query.statuses != null) required += MemoryField.STATUS
            if (query.minLevel != null || query.maxLevel != null) required += MemoryField.LEVEL
            if (query.createdAfter != null || query.createdBefore != null) required += MemoryField.CREATED
            if (query.revisedAfter != null || query.revisedBefore != null) {
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
            ReadOrder.NONE -> Unit
            ReadOrder.EFFECTIVE_CONFIDENCE_DESC -> required += MemoryField.EFFECTIVE_CONFIDENCE
            ReadOrder.CREATED_DESC -> required += MemoryField.CREATED
            ReadOrder.REVISED_DESC -> {
                required += MemoryField.CONTENT_REVISED
                required += MemoryField.METADATA_REVISED
            }
            ReadOrder.LAST_ACCESSED_DESC -> required += MemoryField.LAST_ACCESSED
            ReadOrder.REINFORCE_COUNT_DESC -> required += MemoryField.REINFORCE_COUNT
            ReadOrder.IMPORTANCE_DESC -> required += MemoryField.IMPORTANCE
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
            require(snapshot.size <= MAX_GRANTS) { "Policy exceeds 1000 grants" }
            val unique = LinkedHashMap<PropositionKey, RowGrant>()
            snapshot.forEach { grant ->
                val prior = unique.putIfAbsent(grant.key, grant)
                require(prior == null || prior == grant) { "Conflicting duplicate grant" }
            }
            require(unique.values.sumOf {
                (it.projection.text as? ApprovedText.Literal)?.value?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            } <= MAX_LITERAL_BYTES) { "Policy projected text exceeds 1MiB" }
            return RowPolicy(stamp, unique.values, influence)
        }
    }
}

private const val MAX_GRANTS = 1000
private const val MAX_LITERAL_BYTES = 1024 * 1024

internal fun <T> immutablePolicyList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> immutablePolicySet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
