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

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.temporal.TemporalMetadata
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.Collections
import java.util.UUID

/**
 * A draft is an unfinished [Proposition] that Java code fills in one named step at a time.
 *
 * Start one with `Proposition.draft(contextIdValue, text, confidence)`, or copy an existing
 * proposition with `proposition.toDraft()`. Every step returns a new draft and leaves the one it
 * is called on as it was, so a draft can be shared and reused. [toProposition] builds the
 * proposition through its primary constructor, which checks every value.
 *
 * A step sets one value and nothing else. It does not move contentRevised or metadataRevised,
 * which the methods of the same name on Proposition do.
 *
 * The lists and the map a draft holds are read-only copies, taken when a step or `toDraft()`
 * receives them. A call such as `draft.getSourceIds().clear()` throws
 * `UnsupportedOperationException`. A proposition built from the draft holds the same read-only
 * collections. The draft does not copy what its values hold: the lists inside a
 * [TemporalMetadata], the hints map inside an [EntityMention] and the values in the metadata map
 * are kept as given, as [Proposition] keeps them.
 *
 * ```java
 * Proposition fact = Proposition.draft("acme", "Alice lives in Paris", 0.9)
 *         .withMentions(List.of(new EntityMention("Alice", "Person", null, MentionRole.OTHER, Map.of())))
 *         .withGrounding(List.of("chunk-1"))
 *         .withDecay(0.1)
 *         .toProposition();
 * ```
 *
 * A time that is not set is filled in when [toProposition] runs:
 *
 * - contentRevised takes the build moment, as the Proposition constructor does.
 * - created takes the earliest time that is set, or contentRevised if that is earlier, so a
 *   proposition is never created after its content last changed.
 * - metadataRevised takes contentRevised, so the metadata never looks older than the content.
 * - lastAccessed takes contentRevised, as the Proposition constructor does.
 *
 * Decay is measured from contentRevised, so set it when you import an older fact. The other times
 * then follow it. When no time is set, all four are the build moment. For two calls to build equal
 * propositions, set the id and contentRevised.
 *
 * @property contextId The context the proposition belongs to.
 * @property text The statement in natural language.
 * @property confidence How certain the statement is, from 0.0 to 1.0.
 * @property id The proposition id, or null to generate one when the proposition is built.
 * @property mentions The entities the text mentions.
 * @property decay How quickly the statement goes stale, from 0.0 to 1.0.
 * @property importance How much the statement matters, from 0.0 to 1.0.
 * @property reasoning Why the statement was extracted, or null.
 * @property grounding The ids of the chunks that support the statement.
 * @property created When the proposition was first created, or null for the earliest time set.
 * @property contentRevised When the content last changed, or null for the build moment.
 * @property metadataRevised When the metadata last changed, or null to follow [contentRevised].
 * @property lastAccessed When the proposition was last used, or null to follow [contentRevised].
 * @property pinned Whether decay-driven sweeps leave the proposition alone.
 * @property status The lifecycle status.
 * @property level The abstraction level, where 0 is a raw observation.
 * @property sourceIds The ids of the propositions this one was abstracted from.
 * @property reinforceCount How many times the proposition has been merged or reinforced.
 * @property metadata Extra values stored with the proposition.
 * @property uri The proposition's URI, or null.
 * @property temporal The valid-time and observed-time details, or null.
 * @property provenanceEntries The source material entries that support the statement.
 */
class PropositionDraft private constructor(
    private val values: Values,
) {

    /**
     * Every value a draft holds, kept together so a step can copy them all in one go. It has one
     * field per Proposition constructor parameter, which PropositionDraftParityTest checks, so a
     * draft rebuilt from JSON builds the same proposition as the draft that wrote it.
     */
    private data class Values(
        val contextId: ContextId,
        val text: String,
        val confidence: ZeroToOne,
        val id: String?,
        val mentions: List<EntityMention>,
        val decay: ZeroToOne,
        val importance: ZeroToOne,
        val reasoning: String?,
        val grounding: List<String>,
        val created: Instant?,
        val contentRevised: Instant?,
        val metadataRevised: Instant?,
        val lastAccessed: Instant?,
        val pinned: Boolean,
        val status: PropositionStatus,
        val level: Int,
        val sourceIds: List<String>,
        val reinforceCount: Int,
        val metadata: Map<String, Any>,
        val uri: String?,
        val temporal: TemporalMetadata?,
        val provenanceEntries: List<ProvenanceEntry>,
    )

    @get:JsonProperty("contextId")
    val contextId: ContextId get() = values.contextId

    val text: String get() = values.text

    val confidence: ZeroToOne get() = values.confidence

    val id: String? get() = values.id

    val mentions: List<EntityMention> get() = values.mentions

    val decay: ZeroToOne get() = values.decay

    val importance: ZeroToOne get() = values.importance

    val reasoning: String? get() = values.reasoning

    val grounding: List<String> get() = values.grounding

    val created: Instant? get() = values.created

    val contentRevised: Instant? get() = values.contentRevised

    val metadataRevised: Instant? get() = values.metadataRevised

    val lastAccessed: Instant? get() = values.lastAccessed

    val pinned: Boolean get() = values.pinned

    val status: PropositionStatus get() = values.status

    val level: Int get() = values.level

    val sourceIds: List<String> get() = values.sourceIds

    val reinforceCount: Int get() = values.reinforceCount

    val metadata: Map<String, Any> get() = values.metadata

    val uri: String? get() = values.uri

    val temporal: TemporalMetadata? get() = values.temporal

    val provenanceEntries: List<ProvenanceEntry> get() = values.provenanceEntries

    /** The context id as a plain string, for Java callers. */
    @get:JsonIgnore
    val contextIdValue: String get() = contextId.value

    /**
     * Sets the text.
     * Unlike Proposition.withText, this leaves contentRevised as it is.
     */
    fun withText(text: String): PropositionDraft = PropositionDraft(values.copy(text = text))

    /**
     * Sets the confidence.
     * Unlike Proposition.withConfidence, this leaves contentRevised as it is.
     */
    fun withConfidence(confidence: Double): PropositionDraft = PropositionDraft(values.copy(confidence = confidence))

    /** Sets the id; null makes [toProposition] generate one. */
    fun withId(id: String?): PropositionDraft = PropositionDraft(values.copy(id = id))

    /** Replaces the mentions with a read-only copy of [mentions]. */
    fun withMentions(mentions: List<EntityMention>): PropositionDraft =
        PropositionDraft(values.copy(mentions = Collections.unmodifiableList(ArrayList(mentions))))

    /** Sets the decay. */
    fun withDecay(decay: Double): PropositionDraft = PropositionDraft(values.copy(decay = decay))

    /** Sets the importance. */
    fun withImportance(importance: Double): PropositionDraft = PropositionDraft(values.copy(importance = importance))

    /** Sets the reasoning. */
    fun withReasoning(reasoning: String?): PropositionDraft = PropositionDraft(values.copy(reasoning = reasoning))

    /**
     * Appends [chunkIds] to the grounding and removes duplicates.
     * To replace or clear the grounding, use [withExactGrounding].
     * Unlike Proposition.withGrounding, this leaves metadataRevised as it is.
     */
    fun withGrounding(chunkIds: List<String>): PropositionDraft =
        PropositionDraft(values.copy(grounding = Collections.unmodifiableList((grounding + chunkIds).distinct())))

    /** Sets the grounding to exactly [chunkIds], in the order given. An empty list clears it. */
    fun withExactGrounding(chunkIds: List<String>): PropositionDraft =
        PropositionDraft(values.copy(grounding = Collections.unmodifiableList(ArrayList(chunkIds))))

    /** Sets the creation time. */
    fun withCreated(created: Instant): PropositionDraft = PropositionDraft(values.copy(created = created))

    /** Sets the time the content last changed. */
    fun withContentRevised(contentRevised: Instant): PropositionDraft = PropositionDraft(values.copy(contentRevised = contentRevised))

    /** Sets the time the metadata last changed. */
    fun withMetadataRevised(metadataRevised: Instant): PropositionDraft = PropositionDraft(values.copy(metadataRevised = metadataRevised))

    /** Sets the time the proposition was last used. */
    fun withLastAccessed(lastAccessed: Instant): PropositionDraft = PropositionDraft(values.copy(lastAccessed = lastAccessed))

    /**
     * Sets whether the proposition is pinned.
     * Unlike Proposition.withPinned, this leaves metadataRevised as it is.
     */
    fun withPinned(pinned: Boolean): PropositionDraft = PropositionDraft(values.copy(pinned = pinned))

    /**
     * Sets the lifecycle status.
     * Unlike Proposition.withStatus, this leaves metadataRevised as it is.
     */
    fun withStatus(status: PropositionStatus): PropositionDraft = PropositionDraft(values.copy(status = status))

    /** Sets the abstraction level. */
    fun withLevel(level: Int): PropositionDraft = PropositionDraft(values.copy(level = level))

    /** Replaces the source ids with a read-only copy of [sourceIds]. */
    fun withSourceIds(sourceIds: List<String>): PropositionDraft =
        PropositionDraft(values.copy(sourceIds = Collections.unmodifiableList(ArrayList(sourceIds))))

    /** Sets the reinforce count. */
    fun withReinforceCount(reinforceCount: Int): PropositionDraft = PropositionDraft(values.copy(reinforceCount = reinforceCount))

    /** Replaces the metadata with a read-only copy of [metadata]. */
    fun withMetadata(metadata: Map<String, Any>): PropositionDraft =
        PropositionDraft(values.copy(metadata = Collections.unmodifiableMap(LinkedHashMap(metadata))))

    /** Sets the URI. */
    fun withUri(uri: String?): PropositionDraft = PropositionDraft(values.copy(uri = uri))

    /**
     * Sets the temporal details.
     * Unlike Proposition.withTemporal, this leaves metadataRevised as it is.
     */
    fun withTemporal(temporal: TemporalMetadata?): PropositionDraft = PropositionDraft(values.copy(temporal = temporal))

    /**
     * Replaces the provenance entries with a read-only copy of [entries] that drops duplicates,
     * keeping the first of each, as Proposition.withProvenance does.
     * Unlike Proposition.withProvenance, this leaves metadataRevised as it is.
     */
    fun withProvenance(entries: List<ProvenanceEntry>): PropositionDraft =
        PropositionDraft(values.copy(provenanceEntries = Collections.unmodifiableList(entries.distinct())))

    /**
     * Builds the proposition through its primary constructor.
     *
     * @return a new proposition with this draft's values
     * @throws IllegalArgumentException when a value is out of range, with the constructor's message
     */
    fun toProposition(): Proposition {
        val now = Instant.now()
        val contentRevised = values.contentRevised ?: now
        val explicitTimes = listOfNotNull(values.created, values.contentRevised, values.metadataRevised, values.lastAccessed)
        return TEMPLATE.copy(
            id = id ?: UUID.randomUUID().toString(),
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = confidence,
            decay = decay,
            importance = importance,
            reasoning = reasoning,
            grounding = grounding,
            created = values.created ?: (explicitTimes + contentRevised).min(),
            contentRevised = contentRevised,
            metadataRevised = values.metadataRevised ?: contentRevised,
            pinned = pinned,
            lastAccessed = values.lastAccessed ?: contentRevised,
            status = status,
            level = level,
            sourceIds = sourceIds,
            reinforceCount = reinforceCount,
            metadata = metadata,
            uri = uri,
            temporal = temporal,
            provenanceEntries = provenanceEntries,
        )
    }

    override fun toString(): String = "PropositionDraft(contextId=${contextId.value}, text=$text)"

    internal companion object {

        // A proposition with the constructor's defaults. A draft that starts from scratch takes
        // its default values from here, so the defaults live in one place.
        private val TEMPLATE = Proposition(contextId = ContextId("template"), text = "", mentions = emptyList(), confidence = 0.0)

        /**
         * Reads a draft from its flat JSON form, one key per value, which is also the form the
         * first draft release wrote. contextId, text and confidence must be present and not null;
         * a missing one fails the read with a message that names it. Any other key that is missing
         * or null takes the value [Proposition.draft] starts with, so importance reads as 0.5, a
         * list as empty and status as ACTIVE, with or without Jackson's Kotlin module.
         *
         * @param contextId the context id as a plain string
         * @return a draft holding the values from the JSON
         * @throws IllegalArgumentException when contextId, text or confidence is missing
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("contextId") contextId: String?,
            @JsonProperty("text") text: String?,
            @JsonProperty("confidence") confidence: Double?,
            @JsonProperty("id") id: String?,
            @JsonProperty("mentions") mentions: List<EntityMention>?,
            @JsonProperty("decay") decay: Double?,
            @JsonProperty("importance") importance: Double?,
            @JsonProperty("reasoning") reasoning: String?,
            @JsonProperty("grounding") grounding: List<String>?,
            @JsonProperty("created") created: Instant?,
            @JsonProperty("contentRevised") contentRevised: Instant?,
            @JsonProperty("metadataRevised") metadataRevised: Instant?,
            @JsonProperty("lastAccessed") lastAccessed: Instant?,
            @JsonProperty("pinned") pinned: Boolean?,
            @JsonProperty("status") status: PropositionStatus?,
            @JsonProperty("level") level: Int?,
            @JsonProperty("sourceIds") sourceIds: List<String>?,
            @JsonProperty("reinforceCount") reinforceCount: Int?,
            @JsonProperty("metadata") metadata: Map<String, Any>?,
            @JsonProperty("uri") uri: String?,
            @JsonProperty("temporal") temporal: TemporalMetadata?,
            @JsonProperty("provenanceEntries") provenanceEntries: List<ProvenanceEntry>?,
        ): PropositionDraft {
            requireNotNull(contextId) { "PropositionDraft JSON needs a contextId" }
            requireNotNull(text) { "PropositionDraft JSON needs a text" }
            requireNotNull(confidence) { "PropositionDraft JSON needs a confidence" }
            return PropositionDraft(
                values = Values(
                    contextId = ContextId(contextId),
                    text = text,
                    confidence = confidence,
                    id = id,
                    mentions = Collections.unmodifiableList(ArrayList(mentions.orEmpty())),
                    decay = decay ?: TEMPLATE.decay,
                    importance = importance ?: TEMPLATE.importance,
                    reasoning = reasoning,
                    grounding = Collections.unmodifiableList(ArrayList(grounding.orEmpty())),
                    created = created,
                    contentRevised = contentRevised,
                    metadataRevised = metadataRevised,
                    lastAccessed = lastAccessed,
                    pinned = pinned ?: TEMPLATE.pinned,
                    status = status ?: TEMPLATE.status,
                    level = level ?: TEMPLATE.level,
                    sourceIds = Collections.unmodifiableList(ArrayList(sourceIds.orEmpty())),
                    reinforceCount = reinforceCount ?: TEMPLATE.reinforceCount,
                    metadata = Collections.unmodifiableMap(LinkedHashMap(metadata.orEmpty())),
                    uri = uri,
                    temporal = temporal,
                    provenanceEntries = Collections.unmodifiableList(ArrayList(provenanceEntries.orEmpty())),
                ),
            )
        }

        /**
         * Starts a draft with the three values every proposition needs and defaults for the rest.
         *
         * @param contextId the context the proposition belongs to
         * @param text the statement in natural language
         * @param confidence how certain the statement is
         * @return a draft with no id, no times set and every other value at its default
         */
        internal fun of(contextId: ContextId, text: String, confidence: ZeroToOne): PropositionDraft =
            PropositionDraft(
                values = Values(
                    contextId = contextId,
                    text = text,
                    confidence = confidence,
                    id = null,
                    mentions = emptyList(),
                    decay = TEMPLATE.decay,
                    importance = TEMPLATE.importance,
                    reasoning = TEMPLATE.reasoning,
                    grounding = TEMPLATE.grounding,
                    created = null,
                    contentRevised = null,
                    metadataRevised = null,
                    lastAccessed = null,
                    pinned = TEMPLATE.pinned,
                    status = TEMPLATE.status,
                    level = TEMPLATE.level,
                    sourceIds = TEMPLATE.sourceIds,
                    reinforceCount = TEMPLATE.reinforceCount,
                    metadata = TEMPLATE.metadata,
                    uri = TEMPLATE.uri,
                    temporal = TEMPLATE.temporal,
                    provenanceEntries = TEMPLATE.provenanceEntries,
                ),
            )

        /**
         * Starts a draft that holds every value of an existing proposition, its id and times included.
         *
         * @param proposition the proposition to copy
         * @return a draft that builds a proposition equal to [proposition] and holds its own read-only
         *   copies of the lists and the map
         */
        internal fun from(proposition: Proposition): PropositionDraft =
            PropositionDraft(
                values = Values(
                    contextId = proposition.contextId,
                    text = proposition.text,
                    confidence = proposition.confidence,
                    id = proposition.id,
                    mentions = Collections.unmodifiableList(ArrayList(proposition.mentions)),
                    decay = proposition.decay,
                    importance = proposition.importance,
                    reasoning = proposition.reasoning,
                    grounding = Collections.unmodifiableList(ArrayList(proposition.grounding)),
                    created = proposition.created,
                    contentRevised = proposition.contentRevised,
                    metadataRevised = proposition.metadataRevised,
                    lastAccessed = proposition.lastAccessed,
                    pinned = proposition.pinned,
                    status = proposition.status,
                    level = proposition.level,
                    sourceIds = Collections.unmodifiableList(ArrayList(proposition.sourceIds)),
                    reinforceCount = proposition.reinforceCount,
                    metadata = Collections.unmodifiableMap(LinkedHashMap(proposition.metadata)),
                    uri = proposition.uri,
                    temporal = proposition.temporal,
                    provenanceEntries = Collections.unmodifiableList(ArrayList(proposition.provenanceEntries)),
                )
            )
    }
}
