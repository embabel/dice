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
package com.embabel.dice.storage

import com.embabel.dice.audit.AuditContextProvider
import com.embabel.dice.audit.AuditingPropositionRepository
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.temporal.TemporalMetadata
import org.jetbrains.annotations.ApiStatus
import java.time.Instant
import java.util.UUID

internal const val WORLD_PROGRESSION_OCCURRENCE = "dice.world-progression.occurrence"

/**
 * Records a world change already accepted by the application. Both claims and their audit records
 * are committed in one SQLite transaction. See the storage guide for the required schema setup.
 */
@ApiStatus.Experimental
class SqliteWorldProgression private constructor(
    private val repository: DrivinePropositionRepository,
    private val audit: DrivineAuditLogStore,
    contextProvider: AuditContextProvider,
) {

    private val audited = AuditingPropositionRepository.wrapping(
        repository, audit, contextProvider, canonicalLookup = repository.auditCanonicalLookup(),
    )

    /**
     * Closes [previous] at [effectiveAt] and opens a new occurrence of [successor] at the same
     * instant. The application supplies a stable [transitionId] unique to this event in its context.
     * Replaying the event returns its stored successor, even after another event has replaced it.
     */
    fun record(
        transitionId: String,
        previous: Proposition,
        successor: Proposition,
        effectiveAt: Instant,
    ): Proposition {
        val contextId = previous.contextId.value
        require(transitionId.isNotBlank()) { "A stable transition ID is required" }
        require(previous.contextId == successor.contextId && previous.text != successor.text && successor.text.isNotBlank()) {
            "A transition requires two distinct claims in the same context"
        }
        require(effectiveAt <= audit.now()) { "A world change cannot take effect in the future" }
        val id = UUID.nameUUIDFromBytes("dice-world-progression:${contextId.length}:$contextId$transitionId".toByteArray())
            .toString()
        val next = successor.temporal ?: TemporalMetadata()
        require(next.validFrom == null || next.validFrom == effectiveAt) { "Successor has a different start time" }
        require(next.validTo?.isAfter(effectiveAt) != false && next.invalidatedAt == null && next.supersedes.isEmpty()) {
            "Successor must be a new occurrence with a valid end"
        }
        return audit.duringWorldProgression(contextId) {
            val existing = repository.findWorldOccurrence(contextId, id)
            if (existing != null) {
                require(
                    existing.contextId == successor.contextId && existing.text == successor.text &&
                        existing.temporal?.validFrom == effectiveAt && existing.temporal?.supersedes == listOf(previous.id) &&
                        existing.metadata[WORLD_PROGRESSION_OCCURRENCE] == id,
                ) { "Transition ID already identifies a different occurrence" }
                return@duringWorldProgression existing
            }

            val current = requireNotNull(repository.findWorldOccurrence(contextId, previous.id)) {
                "Previous occurrence was not found in this context"
            }
            require(
                current.text == previous.text &&
                    current.status == PropositionStatus.ACTIVE && !current.pinned &&
                    current.contentRevised == previous.contentRevised && current.metadataRevised == previous.metadataRevised,
            ) { "Previous occurrence is no longer the active unpinned claim supplied by the caller" }
            val prior = current.temporal ?: TemporalMetadata()
            require(
                (prior.validFrom?.isBefore(effectiveAt) != false) &&
                    (prior.validTo == null || prior.validTo == effectiveAt) && prior.invalidatedAt == null,
            ) { "Effective time is outside the previous occurrence's open validity window" }

            require(repository.findActiveExactTextId(successor, id) == null) {
                "A different active occurrence already has the successor's text"
            }
            val retired = current.withStatus(PropositionStatus.SUPERSEDED).withTemporal(prior.copy(validTo = effectiveAt))
            val replacement = successor.toDraft().withId(id)
                .withStatus(PropositionStatus.ACTIVE)
                .withTemporal(next.copy(validFrom = effectiveAt, supersedes = listOf(current.id)))
                .toProposition().withMetadataValue(WORLD_PROGRESSION_OCCURRENCE, id)
            check(audited.save(retired).id == current.id)
            repository.withNewWorldOccurrence(id) {
                audited.save(replacement).also { check(it.id == id) { "Store did not preserve the new occurrence ID" } }
            }
        }
    }

    companion object {
        /**
         * Use at startup after registering the proposition and audit schemas. On an opted-in
         * existing SQLite database, removes the legacy exact-text uniqueness constraint.
         */
        @JvmStatic
        @JvmOverloads
        fun enable(
            repository: DrivinePropositionRepository,
            audit: DrivineAuditLogStore,
            contextProvider: AuditContextProvider = AuditContextProvider.NONE,
        ): SqliteWorldProgression {
            repository.prepareWorldProgression(audit)
            return SqliteWorldProgression(repository, audit, contextProvider)
        }
    }
}
