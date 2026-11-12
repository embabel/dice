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

import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.temporal.TemporalMetadata
import java.time.Instant

/**
 * Marks the builder functions so that a block cannot call an outer builder by accident.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class PropositionDsl

/**
 * The receiver of the block passed to `Proposition(contextId, text, confidence) { }`.
 *
 * ```kotlin
 * val fact = Proposition(contextId, "Alice lives in Paris", confidence = 0.9) {
 *     mentions(alice)
 *     grounding("chunk-1")
 *     decay(0.1)
 * }
 * ```
 *
 * Each function sets one value by calling the draft step with the same name. The builder has no
 * public properties, so a name inside the block is always one of these functions.
 */
@PropositionDsl
class PropositionDraftBuilder internal constructor(private var draft: PropositionDraft) {

    /** Sets the proposition id. */
    fun id(id: String) {
        draft = draft.withId(id)
    }

    /** Replaces the mentions with the ones given. */
    fun mentions(vararg mentions: EntityMention) {
        mentions(mentions.toList())
    }

    /** Replaces the mentions with the ones given. */
    fun mentions(mentions: List<EntityMention>) {
        draft = draft.withMentions(mentions)
    }

    /** Sets the decay. */
    fun decay(decay: Double) {
        draft = draft.withDecay(decay)
    }

    /** Sets the importance. */
    fun importance(importance: Double) {
        draft = draft.withImportance(importance)
    }

    /** Sets the reasoning. */
    fun reasoning(reasoning: String) {
        draft = draft.withReasoning(reasoning)
    }

    /** Appends the chunk ids to the grounding and removes duplicates. */
    fun grounding(vararg chunkIds: String) {
        grounding(chunkIds.toList())
    }

    /** Appends the chunk ids to the grounding and removes duplicates. */
    fun grounding(chunkIds: List<String>) {
        draft = draft.withGrounding(chunkIds)
    }

    /** Sets the creation time. */
    fun created(created: Instant) {
        draft = draft.withCreated(created)
    }

    /** Sets the time the content last changed. */
    fun contentRevised(contentRevised: Instant) {
        draft = draft.withContentRevised(contentRevised)
    }

    /** Sets the time the metadata last changed. */
    fun metadataRevised(metadataRevised: Instant) {
        draft = draft.withMetadataRevised(metadataRevised)
    }

    /** Sets the time the proposition was last used. */
    fun lastAccessed(lastAccessed: Instant) {
        draft = draft.withLastAccessed(lastAccessed)
    }

    /** Sets whether the proposition is pinned. */
    fun pinned(pinned: Boolean = true) {
        draft = draft.withPinned(pinned)
    }

    /** Sets the lifecycle status. */
    fun status(status: PropositionStatus) {
        draft = draft.withStatus(status)
    }

    /** Sets the abstraction level. */
    fun level(level: Int) {
        draft = draft.withLevel(level)
    }

    /** Replaces the source ids with the ones given. */
    fun sourceIds(vararg sourceIds: String) {
        sourceIds(sourceIds.toList())
    }

    /** Replaces the source ids with the ones given. */
    fun sourceIds(sourceIds: List<String>) {
        draft = draft.withSourceIds(sourceIds)
    }

    /** Sets the reinforce count. */
    fun reinforceCount(reinforceCount: Int) {
        draft = draft.withReinforceCount(reinforceCount)
    }

    /** Replaces the metadata with the map given. */
    fun metadata(metadata: Map<String, Any>) {
        draft = draft.withMetadata(metadata)
    }

    /** Sets the URI. */
    fun uri(uri: String) {
        draft = draft.withUri(uri)
    }

    /** Sets the temporal details. */
    fun temporal(temporal: TemporalMetadata) {
        draft = draft.withTemporal(temporal)
    }

    /** Replaces the provenance entries with the ones given. */
    fun provenance(entries: List<ProvenanceEntry>) {
        draft = draft.withProvenance(entries)
    }

    /**
     * Returns the draft that the block has built up.
     *
     * @return the draft holding every value set in the block
     */
    internal fun build(): PropositionDraft = draft
}
