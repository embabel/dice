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
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.query.graph.PropositionLineage
import java.time.Clock

/**
 * Converts retrieval results that carry whole claims into [Evidence] and [EvidenceItem] values.
 *
 * It converts a list of claims, a list of scored claims, a lineage and a contradiction. The decayed
 * confidence on each item comes from [Proposition.effectiveConfidenceAt] at the time [clock] reads,
 * with the decay rate multiplier [decayK]. The converter is immutable. The `with` methods return a new one.
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
     * Converts one claim into an item. The citation holds the claim id, context, non-blank grounding
     * chunk ids, provenance and uri. The item has no score and no retrievals.
     *
     * @param proposition the claim to convert
     * @return the item for the claim
     * @throws IllegalArgumentException if the claim text is blank
     */
    fun item(proposition: Proposition): EvidenceItem =
        itemWithCitation(proposition, citationFor(proposition, proposition.grounding, proposition.provenanceEntries))

    /**
     * Converts one scored claim into an item with the hit's score and one retrieval at [rank].
     * A score that is not finite is left off the item and the retrieval.
     *
     * @param hit the scored claim
     * @param retriever the retriever that found the claim
     * @param rank the position of the hit in its result list, starting at 1
     * @return the item for the claim
     * @throws IllegalArgumentException if the claim text is blank or [rank] is below 1
     */
    fun item(hit: SimilarityResult<out Proposition>, retriever: RetrieverId, rank: Int): EvidenceItem {
        val score = hit.score.takeIf { it.isFinite() }
        val base = item(hit.match)
        val scored = score?.let { base.withScore(it) } ?: base
        return scored.withRetrieval(retrieval(retriever, rank, score))
    }

    /**
     * Converts a list of claims into evidence. Claims with blank text are skipped. An empty list,
     * or one where every claim is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param propositions the claims, in result order
     * @param searched what the lookup searched for
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     */
    fun fromPropositions(propositions: List<Proposition>, searched: Searched): Evidence =
        Evidence.of(propositions.filter { it.text.isNotBlank() }.map { item(it) }, searched)

    /**
     * Converts a list of claims into evidence, giving each kept claim a retrieval by [retriever]
     * ranked from 1. Claims with blank text are skipped and take no rank. An empty list, or one where
     * every claim is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param propositions the claims, in result order
     * @param searched what the lookup searched for
     * @param retriever the retriever that returned the claims
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     */
    fun fromPropositions(propositions: List<Proposition>, searched: Searched, retriever: RetrieverId): Evidence =
        Evidence.of(
            propositions.filter { it.text.isNotBlank() }
                .mapIndexed { index, proposition ->
                    item(proposition).withRetrieval(retrieval(retriever, index + 1, null))
                },
            searched,
        )

    /**
     * Converts a list of scored claims into evidence with retriever [RetrieverId.VECTOR].
     * Hits whose claim text is blank are skipped. An empty list, or one where every hit is skipped,
     * becomes [Evidence.Missing] for [searched].
     *
     * @param hits the scored claims, in result order
     * @param searched what the lookup searched for
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     */
    fun fromSimilarityResults(hits: List<SimilarityResult<out Proposition>>, searched: Searched): Evidence =
        fromSimilarityResults(hits, searched, RetrieverId.VECTOR)

    /**
     * Converts a list of scored claims into evidence. Each kept hit gets its score and a retrieval by
     * [retriever] ranked from 1. Hits whose claim text is blank are skipped and take no rank. An empty
     * list, or one where every hit is skipped, becomes [Evidence.Missing] for [searched].
     *
     * @param hits the scored claims, in result order
     * @param searched what the lookup searched for
     * @param retriever the retriever that returned the hits
     * @return a [Evidence.Recorded] holding the items in order, or a [Evidence.Missing]
     */
    fun fromSimilarityResults(
        hits: List<SimilarityResult<out Proposition>>,
        searched: Searched,
        retriever: RetrieverId,
    ): Evidence =
        Evidence.of(
            hits.filter { it.match.text.isNotBlank() }
                .mapIndexed { index, hit -> item(hit, retriever, index + 1) },
            searched,
        )

    /**
     * Converts a lineage into evidence. The claim's citation takes its chunk ids and provenance from
     * the lineage. An abstraction (level above 0) becomes [Evidence.Derived] with rule
     * [DerivationRule.ABSTRACTION], its non-blank source ids as input ids, and the loaded sources with
     * non-blank text as inputs. No loaded sources gives empty inputs. Any other claim becomes
     * [Evidence.Recorded] with one item.
     *
     * @param lineage the lineage to convert
     * @return a [Evidence.Derived] for an abstraction, otherwise a [Evidence.Recorded]
     * @throws IllegalArgumentException if the claim text is blank, or the claim is an abstraction whose source ids are all blank
     */
    fun fromLineage(lineage: PropositionLineage): Evidence {
        val proposition = lineage.proposition
        val citation = citationFor(proposition, lineage.groundingChunkIds, lineage.provenanceEntries)
        if (proposition.level == 0) {
            return Evidence.recorded(listOf(itemWithCitation(proposition, citation)))
        }
        val inputIds = nonBlank(proposition.sourceIds)
        require(inputIds.isNotEmpty()) { "an abstraction needs at least one non-blank source id" }
        return Evidence.derived(proposition.text, proposition.confidence, DerivationRule.ABSTRACTION, inputIds)
            .withInputs(lineage.sources.filter { it.text.isNotBlank() }.map { item(it) })
            .withCitation(citation)
    }

    /**
     * Converts a contradiction into [Evidence.Conflicting] with the original claim first, the new
     * claim second, and the contradiction's conflict type.
     *
     * @param contradicted the contradiction to convert
     * @return the conflicting evidence
     * @throws IllegalArgumentException if either claim text is blank or both claims have the same id
     */
    fun fromContradiction(contradicted: RevisionResult.Contradicted): Evidence.Conflicting =
        Evidence.conflicting(
            listOf(item(contradicted.original), item(contradicted.new)),
            contradicted.conflictType,
        )

    /**
     * Builds the citation for a claim from the given chunk ids and provenance.
     *
     * @param proposition the claim being cited
     * @param chunkIds the source chunk ids. Blank entries are left out.
     * @param provenance the provenance entries for the claim
     * @return the citation, with the uri set when the claim has a non-blank one
     */
    private fun citationFor(
        proposition: Proposition,
        chunkIds: List<String>,
        provenance: List<ProvenanceEntry>,
    ): Citation {
        val citation = Citation.of(proposition.id, proposition.contextId)
            .withChunkIds(nonBlank(chunkIds))
            .withProvenance(provenance)
        val uri = proposition.uri
        return if (uri.isNullOrBlank()) citation else citation.withUri(uri)
    }

    /**
     * Builds the item for a claim with a citation that is already built.
     *
     * @param proposition the claim to convert
     * @param citation the citation the item carries
     * @return the item, with no score and no retrievals
     */
    private fun itemWithCitation(proposition: Proposition, citation: Citation): EvidenceItem {
        require(proposition.text.isNotBlank()) { "claim text must not be blank, claim ${proposition.id}" }
        return EvidenceItem.of(proposition.text, citation, proposition.confidence)
            .withEffectiveConfidence(proposition.effectiveConfidenceAt(clock.instant(), decayK))
            .withStatus(proposition.status)
            .withRecordedAt(proposition.created)
            .withRevisedAt(proposition.contentRevised)
            .withDerivedFromIds(nonBlank(proposition.sourceIds))
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
     * Drops blank entries from a list of ids.
     *
     * @param ids the ids to filter
     * @return the non-blank ids, in order
     */
    private fun nonBlank(ids: List<String>): List<String> = ids.filter { it.isNotBlank() }

    companion object {

        /**
         * Creates a converter on the system UTC clock with decay rate multiplier 2.0, the default
         * [Proposition.effectiveConfidenceAt] uses.
         *
         * @return the new converter
         */
        @JvmStatic
        fun create(): EvidenceConverter = EvidenceConverter(Clock.systemUTC(), Proposition.DEFAULT_DECAY_K)
    }
}
