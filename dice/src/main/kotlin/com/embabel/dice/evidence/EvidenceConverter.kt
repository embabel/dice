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
package com.embabel.dice.evidence

import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.query.graph.PropositionLineage
import com.embabel.dice.spi.ConflictType
import java.time.Clock
import java.time.Instant

/**
 * Converts retrieval results that carry whole claims into [Evidence] and [EvidenceItem] values.
 *
 * It converts a list of claims, a list of scored claims, a lineage and a contradiction. The decayed
 * confidence on each item comes from [Proposition.effectiveConfidenceAt] at the time the converter's clock
 * reads, with its decay rate multiplier. The converter is immutable. The `with` methods return a new one.
 *
 * ```java
 * EvidenceConverter converter = EvidenceConverter.create().withDecayK(2.0);
 * Evidence evidence = converter.fromSimilarityResults(hits, Searched.of("ctx-main").withQuery("Where does Alice live?"));
 * ```
 */
class EvidenceConverter private constructor(
    private val clock: Clock,
    private val decayK: Double,
) {

    init {
        require(decayK.isFinite() && decayK >= 0.0) { "decayK must be finite and not negative, was $decayK" }
    }

    /**
     * Returns a converter that reads "now" from [clock] when it works out decayed confidence.
     *
     * @param clock the clock to read the current time from
     * @return the new converter
     */
    fun withClock(clock: Clock): EvidenceConverter = EvidenceConverter(clock, decayK)

    /**
     * Returns a converter that uses [decayK] as the decay rate multiplier. Pass the value the query
     * that selected the claims used.
     *
     * @param decayK the decay rate multiplier. It must be finite and not negative.
     * @return the new converter
     * @throws IllegalArgumentException if [decayK] is NaN, infinite or negative
     */
    fun withDecayK(decayK: Double): EvidenceConverter = EvidenceConverter(clock, decayK)

    /**
     * Converts one claim into an item. The citation holds the claim id, context, grounding chunk ids,
     * provenance and uri. Null and blank chunk ids and source ids are left out. The item has no score
     * and no retrievals.
     *
     * @param proposition the claim to convert
     * @return the item for the claim
     * @throws IllegalArgumentException if the claim text or the claim id is blank
     */
    fun item(proposition: Proposition): EvidenceItem =
        convert(proposition, clock.instant())

    /**
     * Converts one scored claim into an item with the hit's score and one retrieval at [rank].
     * A score that is not finite is left off the item and the retrieval. To convert a page of a longer
     * result list, call this with the hit's position in the whole list on a converter with a fixed clock,
     * so every item is worked out at the same time.
     *
     * @param hit the scored claim
     * @param retriever the retriever that found the claim
     * @param rank the position of the hit in its result list, starting at 1
     * @return the item for the claim
     * @throws IllegalArgumentException if the hit has no claim, the claim text or the claim id is blank,
     *   or [rank] is below 1
     */
    fun item(hit: SimilarityResult<out Proposition>, retriever: RetrieverId, rank: Int): EvidenceItem =
        scoredItem("hit", hit, retriever, rank, clock.instant())

    /**
     * Converts a list of claims into evidence. Claims with blank text are skipped. An empty list,
     * or one where every claim is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param propositions the claims, in result order
     * @param searched what the lookup searched for
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     * @throws IllegalArgumentException if [propositions] holds a null, or a kept claim has a blank id
     */
    fun fromPropositions(propositions: List<Proposition>, searched: Searched): Evidence {
        val claims = EvidenceChecks.copyOf("propositions", propositions)
        val now = clock.instant()
        return Evidence.of(claims.filter { it.text.isNotBlank() }.map { convert(it, now) }, searched)
    }

    /**
     * Converts a list of claims into evidence, giving each kept claim a retrieval by [retriever].
     * The list is the retriever's result in order, and each rank is the claim's position in it,
     * starting at 1. Claims with blank text are skipped, and the claims after them keep their position.
     * An empty list, or one where every claim is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param propositions the retriever's result, in order
     * @param searched what the lookup searched for
     * @param retriever the retriever that returned the claims
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     * @throws IllegalArgumentException if [propositions] holds a null, or a kept claim has a blank id
     */
    fun fromPropositions(propositions: List<Proposition>, searched: Searched, retriever: RetrieverId): Evidence {
        val claims = EvidenceChecks.copyOf("propositions", propositions)
        val now = clock.instant()
        return Evidence.of(
            claims.mapIndexedNotNull { index, proposition ->
                if (proposition.text.isBlank()) {
                    null
                } else {
                    convert(proposition, now, retrieval = retrieval(retriever, index + 1, null))
                }
            },
            searched,
        )
    }

    /**
     * Converts a list of scored claims into evidence with retriever [RetrieverId.VECTOR].
     * The list is the retriever's result in order, and each rank is the hit's position in it,
     * starting at 1. Hits whose claim text is blank are skipped, and the hits after them keep their
     * position. An empty list, or one where every hit is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param hits the retriever's scored claims, in order
     * @param searched what the lookup searched for
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     * @throws IllegalArgumentException if [hits] holds a null or a hit with no claim, or a kept claim has a
     *   blank id
     */
    fun fromSimilarityResults(hits: List<SimilarityResult<out Proposition>>, searched: Searched): Evidence =
        fromSimilarityResults(hits, searched, RetrieverId.VECTOR)

    /**
     * Converts a list of scored claims into evidence. Each kept hit gets its score and a retrieval by
     * [retriever]. The list is the retriever's result in order, and each rank is the hit's position in it,
     * starting at 1. Hits whose claim text is blank are skipped, and the hits after them keep their
     * position. An empty list, or one where every hit is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param hits the retriever's scored claims, in order
     * @param searched what the lookup searched for
     * @param retriever the retriever that returned the hits
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     * @throws IllegalArgumentException if [hits] holds a null or a hit with no claim, or a kept claim has a
     *   blank id
     */
    fun fromSimilarityResults(
        hits: List<SimilarityResult<out Proposition>>,
        searched: Searched,
        retriever: RetrieverId,
    ): Evidence {
        val results = EvidenceChecks.copyOf("hits", hits)
        val now = clock.instant()
        return Evidence.of(
            results.mapIndexedNotNull { index, hit ->
                val label = "hits[$index]"
                if (matchOf(label, hit).text.isBlank()) null else scoredItem(label, hit, retriever, index + 1, now)
            },
            searched,
        )
    }

    /**
     * Converts a lineage into evidence. The claim's citation takes its chunk ids and provenance from
     * the lineage. An abstraction (level above 0) becomes [Evidence.Derived] with rule
     * [DerivationRule.ABSTRACTION], its non-blank source ids as input ids, the loaded sources with
     * non-blank text as inputs, and its status. Its effective confidence comes from the converter's
     * clock and decay rate. No loaded sources gives empty inputs. Any other claim becomes
     * [Evidence.Recorded] with one item.
     *
     * @param lineage the lineage to convert
     * @return a [Evidence.Derived] for an abstraction, otherwise a [Evidence.Recorded]
     * @throws IllegalArgumentException if the claim text or the claim id is blank, or the claim is an abstraction
     *   whose source ids are all blank
     */
    fun fromLineage(lineage: PropositionLineage): Evidence {
        val now = clock.instant()
        val proposition = lineage.proposition
        val citation = citationFor(proposition, lineage.groundingChunkIds, lineage.provenanceEntries)
        if (proposition.level == 0) {
            return Evidence.recorded(listOf(convert(proposition, now, citation)))
        }
        val inputIds = usableIds(proposition.sourceIds)
        require(inputIds.isNotEmpty()) { "an abstraction needs at least one non-blank source id" }
        return Evidence.derived(proposition.text, proposition.confidence, DerivationRule.ABSTRACTION, inputIds)
            .withEffectiveConfidence(proposition.effectiveConfidenceAt(now, decayK))
            .withStatus(proposition.status)
            .withInputs(lineage.sources.filter { it.text.isNotBlank() }.map { convert(it, now) })
            .withCitation(citation)
    }

    /**
     * Converts a contradiction by its conflict type, which the result keeps.
     *
     * | Conflict type | Result |
     * |---|---|
     * | `Contradiction`, `Custom` | [Evidence.Conflicting], the original first and the new claim second |
     * | `Revision`, `WorldProgression` | [Evidence.Superseded], the new claim current and the original previous |
     *
     * Each item keeps its stored status, so a pinned original reads [PropositionStatus.ACTIVE].
     *
     * @param contradicted the contradiction to convert
     * @return the conflicting or superseded evidence
     * @throws IllegalArgumentException if either claim text or claim id is blank, or both claims are the same
     *   claim id in the same context
     */
    fun fromContradiction(contradicted: RevisionResult.Contradicted): Evidence.Disagreement {
        val now = clock.instant()
        val original = convert(contradicted.original, now)
        val new = convert(contradicted.new, now)
        return when (val conflictType = contradicted.conflictType) {
            ConflictType.Contradiction, is ConflictType.Custom ->
                Evidence.conflicting(listOf(original, new), conflictType)
            ConflictType.Revision, ConflictType.WorldProgression ->
                Evidence.superseded(new, listOf(original), conflictType)
        }
    }

    /**
     * Builds the item for a claim in one construction. Every conversion goes through here.
     * The item takes the claim's confidence, status, reinforce count and temporal metadata, its
     * creation time as [EvidenceItem.recordedAt] and its recorded content revision as
     * [EvidenceItem.revisedAt].
     *
     * @param proposition the claim to convert
     * @param now the time the decayed confidence is worked out at
     * @param citation the citation the item carries
     * @param score the finite score, or null for none
     * @param retrieval the retriever's hit for the claim, or null for none
     * @return the item
     * @throws IllegalArgumentException if the claim text or the claim id is blank
     */
    private fun convert(
        proposition: Proposition,
        now: Instant,
        citation: Citation = citationFor(proposition, proposition.grounding, proposition.provenanceEntries),
        score: Double? = null,
        retrieval: Retrieval? = null,
    ): EvidenceItem {
        require(proposition.text.isNotBlank()) { "claim text must not be blank, claim ${proposition.id}" }
        return EvidenceItem.create(
            text = proposition.text,
            citation = citation,
            confidence = proposition.confidence,
            effectiveConfidence = proposition.effectiveConfidenceAt(now, decayK),
            status = proposition.status,
            recordedAt = proposition.created,
            revisedAt = proposition.lastContentRevision,
            derivedFromIds = usableIds(proposition.sourceIds),
            derivedFrom = emptyList(),
            reinforceCount = proposition.reinforceCount,
            temporal = proposition.temporal,
            score = score,
            retrievals = listOfNotNull(retrieval),
        )
    }

    /**
     * Builds the item for a scored claim, with its score and one retrieval at [rank].
     *
     * @param label the name of the hit used in the error message, such as `hits[2]`
     * @param hit the scored claim
     * @param retriever the retriever that found the claim
     * @param rank the position of the hit in its result list, starting at 1
     * @param now the time the decayed confidence is worked out at
     * @return the item
     * @throws IllegalArgumentException if the hit has no claim, the claim text or the claim id is blank,
     *   or [rank] is below 1
     */
    private fun scoredItem(
        label: String,
        hit: SimilarityResult<out Proposition>,
        retriever: RetrieverId,
        rank: Int,
        now: Instant,
    ): EvidenceItem {
        val proposition = matchOf(label, hit)
        val score = hit.score.takeIf { it.isFinite() }
        return convert(proposition, now, score = score, retrieval = retrieval(retriever, rank, score))
    }

    /**
     * Reads the claim from a hit. A hit built in Java can carry a null claim, so it is checked here.
     *
     * @param label the name of the hit used in the error message, such as `hits[2]`
     * @param hit the scored claim
     * @return the hit's claim
     * @throws IllegalArgumentException if the hit has no claim
     */
    private fun matchOf(label: String, hit: SimilarityResult<out Proposition>): Proposition {
        val match: Proposition? = hit.match
        require(match != null) { "$label.match must not be null" }
        return match
    }

    /**
     * Builds the citation for a claim from the given chunk ids and provenance.
     *
     * @param proposition the claim being cited
     * @param chunkIds the source chunk ids. Null and blank entries are left out.
     * @param provenance the provenance entries for the claim
     * @return the citation, with the uri set when the claim has a non-blank one
     */
    private fun citationFor(
        proposition: Proposition,
        chunkIds: List<String?>,
        provenance: List<ProvenanceEntry>,
    ): Citation {
        val citation = Citation.of(proposition.id, proposition.contextId)
            .withChunkIds(usableIds(chunkIds))
            .withProvenance(provenance)
        val uri = proposition.uri
        return if (uri.isNullOrBlank()) citation else citation.withUri(uri)
    }

    /**
     * Builds a retrieval at a rank, with a score when one is given.
     *
     * @param retriever the retriever that found the claim
     * @param rank the position in the result list, starting at 1
     * @param score the finite score, or null for none
     * @return the retrieval
     */
    private fun retrieval(retriever: RetrieverId, rank: Int, score: Double?): Retrieval {
        val ranked = Retrieval.of(retriever).withRank(rank)
        return score?.let { ranked.withScore(it) } ?: ranked
    }

    /**
     * Drops null and blank entries from a list of ids. A list read from a store or built in Java can hold either.
     *
     * @param ids the ids to filter
     * @return the ids that are neither null nor blank, in order
     */
    private fun usableIds(ids: List<String?>): List<String> = ids.filterNotNull().filter { it.isNotBlank() }

    companion object {

        /**
         * Creates a converter on the system UTC clock with decay rate multiplier [Proposition.DEFAULT_DECAY_K].
         *
         * @return the new converter
         */
        @JvmStatic
        fun create(): EvidenceConverter = EvidenceConverter(Clock.systemUTC(), Proposition.DEFAULT_DECAY_K)
    }
}
