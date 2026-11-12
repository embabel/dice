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
 * The lists and the map a draft holds are read-only copies, taken when a step or `toDraft()`
 * receives them. A call such as `draft.getSourceIds().clear()` throws
 * `UnsupportedOperationException`. A proposition built from the draft holds the same read-only
 * collections.
 *
 * ```java
 * Proposition fact = Proposition.draft("acme", "Alice lives in Paris", 0.9)
 *         .withMentions(List.of(new EntityMention("Alice", "Person", null, MentionRole.OTHER, Map.of())))
 *         .withGrounding(List.of("chunk-1"))
 *         .withDecay(0.1)
 *         .toProposition();
 * ```
 *
 * A time that is not set follows the one before it: `contentRevised` follows `created`, and
 * `metadataRevised` and `lastAccessed` follow `contentRevised`. When no time is set at all, all
 * four are the moment [toProposition] runs.
 *
 * Each [toProposition] call on a draft without an id generates a new id, so two calls give two
 * different propositions. Set an id with [withId] to build the same proposition every time.
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
 * @property created When the proposition was first created, or null to use the default.
 * @property contentRevised When the content last changed, or null to follow [created].
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
class PropositionDraft internal constructor(
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
) {

    /** The context id as a plain string, for Java callers. */
    val contextIdValue: String get() = contextId.value

    /** Sets the text. */
    fun withText(text: String): PropositionDraft = rebuild(text = text)

    /** Sets the confidence. */
    fun withConfidence(confidence: Double): PropositionDraft = rebuild(confidence = confidence)

    /** Sets the id; null makes [toProposition] generate one. */
    fun withId(id: String?): PropositionDraft = rebuild(id = id)

    /** Replaces the mentions with a read-only copy of [mentions]. */
    fun withMentions(mentions: List<EntityMention>): PropositionDraft =
        rebuild(mentions = Collections.unmodifiableList(ArrayList(mentions)))

    /** Sets the decay. */
    fun withDecay(decay: Double): PropositionDraft = rebuild(decay = decay)

    /** Sets the importance. */
    fun withImportance(importance: Double): PropositionDraft = rebuild(importance = importance)

    /** Sets the reasoning. */
    fun withReasoning(reasoning: String?): PropositionDraft = rebuild(reasoning = reasoning)

    /** Appends [chunkIds] to the grounding and removes duplicates. */
    fun withGrounding(chunkIds: List<String>): PropositionDraft =
        rebuild(grounding = Collections.unmodifiableList((grounding + chunkIds).distinct()))

    /** Sets the creation time. */
    fun withCreated(created: Instant): PropositionDraft = rebuild(created = created)

    /** Sets the time the content last changed. */
    fun withContentRevised(contentRevised: Instant): PropositionDraft = rebuild(contentRevised = contentRevised)

    /** Sets the time the metadata last changed. */
    fun withMetadataRevised(metadataRevised: Instant): PropositionDraft = rebuild(metadataRevised = metadataRevised)

    /** Sets the time the proposition was last used. */
    fun withLastAccessed(lastAccessed: Instant): PropositionDraft = rebuild(lastAccessed = lastAccessed)

    /** Sets whether the proposition is pinned. */
    fun withPinned(pinned: Boolean): PropositionDraft = rebuild(pinned = pinned)

    /** Sets the lifecycle status. */
    fun withStatus(status: PropositionStatus): PropositionDraft = rebuild(status = status)

    /** Sets the abstraction level. */
    fun withLevel(level: Int): PropositionDraft = rebuild(level = level)

    /** Replaces the source ids with a read-only copy of [sourceIds]. */
    fun withSourceIds(sourceIds: List<String>): PropositionDraft =
        rebuild(sourceIds = Collections.unmodifiableList(ArrayList(sourceIds)))

    /** Sets the reinforce count. */
    fun withReinforceCount(reinforceCount: Int): PropositionDraft = rebuild(reinforceCount = reinforceCount)

    /** Replaces the metadata with a read-only copy of [metadata]. */
    fun withMetadata(metadata: Map<String, Any>): PropositionDraft =
        rebuild(metadata = Collections.unmodifiableMap(LinkedHashMap(metadata)))

    /** Sets the URI. */
    fun withUri(uri: String?): PropositionDraft = rebuild(uri = uri)

    /** Sets the temporal details. */
    fun withTemporal(temporal: TemporalMetadata?): PropositionDraft = rebuild(temporal = temporal)

    /** Replaces the provenance entries with [entries], keeping each distinct entry once. */
    fun withProvenance(entries: List<ProvenanceEntry>): PropositionDraft =
        rebuild(provenanceEntries = Collections.unmodifiableList(entries.distinct()))

    /**
     * Builds the proposition through its primary constructor.
     *
     * @return a new proposition with this draft's values
     * @throws IllegalArgumentException when a value is out of range, with the constructor's message
     */
    fun toProposition(): Proposition {
        val created = created ?: contentRevised ?: Instant.now()
        val contentRevised = contentRevised ?: created
        return Proposition(
            id = id ?: UUID.randomUUID().toString(),
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = confidence,
            decay = decay,
            importance = importance,
            reasoning = reasoning,
            grounding = grounding,
            created = created,
            contentRevised = contentRevised,
            metadataRevised = metadataRevised ?: contentRevised,
            pinned = pinned,
            lastAccessed = lastAccessed ?: contentRevised,
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

    /**
     * Makes a new draft that keeps every value of this one except those passed in.
     *
     * @param contextId the context
     * @param text the text
     * @param confidence the confidence
     * @param id the id, or null
     * @param mentions the mentions
     * @param decay the decay
     * @param importance the importance
     * @param reasoning the reasoning, or null
     * @param grounding the grounding chunk ids
     * @param created the creation time, or null
     * @param contentRevised the content change time, or null
     * @param metadataRevised the metadata change time, or null
     * @param lastAccessed the last use time, or null
     * @param pinned whether the proposition is pinned
     * @param status the lifecycle status
     * @param level the abstraction level
     * @param sourceIds the source proposition ids
     * @param reinforceCount the reinforce count
     * @param metadata the extra values
     * @param uri the URI, or null
     * @param temporal the temporal details, or null
     * @param provenanceEntries the provenance entries
     * @return the new draft
     */
    private fun rebuild(
        contextId: ContextId = this.contextId,
        text: String = this.text,
        confidence: ZeroToOne = this.confidence,
        id: String? = this.id,
        mentions: List<EntityMention> = this.mentions,
        decay: ZeroToOne = this.decay,
        importance: ZeroToOne = this.importance,
        reasoning: String? = this.reasoning,
        grounding: List<String> = this.grounding,
        created: Instant? = this.created,
        contentRevised: Instant? = this.contentRevised,
        metadataRevised: Instant? = this.metadataRevised,
        lastAccessed: Instant? = this.lastAccessed,
        pinned: Boolean = this.pinned,
        status: PropositionStatus = this.status,
        level: Int = this.level,
        sourceIds: List<String> = this.sourceIds,
        reinforceCount: Int = this.reinforceCount,
        metadata: Map<String, Any> = this.metadata,
        uri: String? = this.uri,
        temporal: TemporalMetadata? = this.temporal,
        provenanceEntries: List<ProvenanceEntry> = this.provenanceEntries,
    ): PropositionDraft = PropositionDraft(
        contextId = contextId,
        text = text,
        confidence = confidence,
        id = id,
        mentions = mentions,
        decay = decay,
        importance = importance,
        reasoning = reasoning,
        grounding = grounding,
        created = created,
        contentRevised = contentRevised,
        metadataRevised = metadataRevised,
        lastAccessed = lastAccessed,
        pinned = pinned,
        status = status,
        level = level,
        sourceIds = sourceIds,
        reinforceCount = reinforceCount,
        metadata = metadata,
        uri = uri,
        temporal = temporal,
        provenanceEntries = provenanceEntries,
    )

    internal companion object {

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
                contextId = contextId,
                text = text,
                confidence = confidence,
                id = null,
                mentions = emptyList(),
                decay = 0.0,
                importance = 0.5,
                reasoning = null,
                grounding = emptyList(),
                created = null,
                contentRevised = null,
                metadataRevised = null,
                lastAccessed = null,
                pinned = false,
                status = PropositionStatus.ACTIVE,
                level = 0,
                sourceIds = emptyList(),
                reinforceCount = 0,
                metadata = emptyMap(),
                uri = null,
                temporal = null,
                provenanceEntries = emptyList(),
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
    }
}
