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
package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.ReferenceOnlyEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher
import org.slf4j.LoggerFactory

/**
 * Entity resolver decorator that checks a list of known entities before delegating to another resolver.
 *
 * Useful for ensuring that specific entities (like the current user) are always matched
 * correctly without requiring a search.
 *
 * @param knownEntities Entities to check before searching
 * @param delegate The resolver to delegate to if no known entity matches
 */
class KnownEntityResolver(
    private val knownEntities: List<NamedEntity>,
    private val delegate: EntityResolver,
) : EntityResolver {

    private val logger = LoggerFactory.getLogger(KnownEntityResolver::class.java)

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        if (knownEntities.isEmpty()) {
            return delegate.resolve(suggestedEntities, schema)
        }

        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            val knownMatch = findKnownMatch(suggested)
            if (knownMatch != null) {
                logger.info("Matched '{}' to known entity '{}'", suggested.name, knownMatch.name)
                // ReferenceOnlyEntity for externally-managed entities
                // whose labels already cover the suggestion — no need
                // to update. ExistingEntity (which merges labels) when
                // the suggestion would *widen* the type set — e.g.
                // Airbnb already resolved as `Saas`, suggestion comes
                // back as `Trip` — we want the existing row to gain
                // the `Trip` label rather than fork into a duplicate.
                val suggestedLabels = suggested.labels.map { it.lowercase() }.toSet()
                val knownLabels = knownMatch.labels().map { it.lowercase() }.toSet()
                if (suggestedLabels.minus(knownLabels).isEmpty()) {
                    ReferenceOnlyEntity(suggested, knownMatch.toNamedEntityData())
                } else {
                    ExistingEntity(suggested, knownMatch.toNamedEntityData())
                }
            } else {
                null // Will be resolved by delegate
            }
        }

        // If all matched, return early
        if (resolutions.none { it == null }) {
            @Suppress("UNCHECKED_CAST")
            return Resolutions(
                chunkIds = suggestedEntities.chunkIds,
                resolutions = resolutions as List<SuggestedEntityResolution>,
            )
        }

        // Delegate unmatched entities
        val unmatchedSuggested = suggestedEntities.suggestedEntities
            .filterIndexed { index, _ -> resolutions[index] == null }

        val delegateResult = delegate.resolve(
            SuggestedEntities(
                suggestedEntities = unmatchedSuggested,
                sourceText = suggestedEntities.sourceText,
            ),
            schema
        )

        // Merge results
        var delegateIndex = 0
        val mergedResolutions = resolutions.map { known ->
            known ?: delegateResult.resolutions[delegateIndex++]
        }

        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = mergedResolutions,
        )
    }

    private fun findKnownMatch(suggested: SuggestedEntity): NamedEntity? {
        val normalizedSuggested = NormalizedNameCandidateSearcher.normalizeName(suggested.name)
        val suggestedLabels = suggested.labels.map { it.lowercase() }.toSet()

        // Collect all known entities with a name match. Real-world
        // single concepts often carry multiple types (Airbnb is both a
        // SaaS and a Trip vendor; Apple is both a SaaS and a Biller),
        // so we cannot reject a name match just because the suggested
        // type and the known type don't yet intersect — we want to
        // *widen* the known entity, not create a duplicate row keyed
        // by the same name.
        val nameMatches = knownEntities.filter { known ->
            val normalizedKnown = NormalizedNameCandidateSearcher.normalizeName(known.name)
            normalizedSuggested.equals(normalizedKnown, ignoreCase = true)
        }
        if (nameMatches.isEmpty()) return null

        // Tiebreak when multiple known entities share a normalised
        // name (e.g. "Apple" the company vs "Apple" the fruit): prefer
        // a label-overlapping match so we don't silently merge
        // distinct concepts. If no overlap exists at all, take the
        // first name match — the persister can widen its labels.
        val labelOverlap = nameMatches.firstOrNull { known ->
            known.labels().map { it.lowercase() }.toSet().intersect(suggestedLabels).isNotEmpty()
        }
        return labelOverlap ?: nameMatches.first()
    }

    @Suppress("USELESS_ELVIS")  // Java interop: name can be null despite Kotlin declaration
    private fun NamedEntity.toNamedEntityData(): NamedEntityData =
        if (this is NamedEntityData) this
        else SimpleNamedEntityData(
            id = id ?: "",
            name = name ?: "",
            description = description ?: "",
            labels = labels(),
            properties = emptyMap(),
        )

    companion object {
        /**
         * Create a known entity resolver wrapping an existing resolver.
         *
         * @param knownEntities Entities to check first
         * @param delegate The resolver to delegate to if no match found
         * @return A new KnownEntityResolver
         */
        @JvmStatic
        fun withKnownEntities(
            knownEntities: List<NamedEntity>,
            delegate: EntityResolver,
        ): EntityResolver {
            return if (knownEntities.isEmpty()) {
                delegate
            } else {
                KnownEntityResolver(knownEntities, delegate)
            }
        }
    }
}
