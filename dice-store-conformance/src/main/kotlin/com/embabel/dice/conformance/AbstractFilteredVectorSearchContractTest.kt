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
package com.embabel.dice.conformance

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.VectorSearchCapable
import com.embabel.dice.store.FilteredVectorSearch
import com.embabel.dice.store.StoreCapabilities
import com.embabel.dice.store.StoreCapability
import com.embabel.dice.store.UnsupportedCapabilityException
import com.embabel.dice.store.VectorFiltering
import com.embabel.dice.store.VectorSearchRequest
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.opentest4j.AssertionFailedError
import java.time.Instant

/**
 * Cross-backend contract for [FilteredVectorSearch]. It checks that a store applies the filter
 * before it cuts to K, and that it orders matches closest first before the cut.
 *
 * The suite tests what the store declares. It reads the store's descriptor with
 * [StoreCapabilities.describe] and runs these cases when the store lists
 * [StoreCapability.FILTERED_VECTOR_SEARCH]. A store that does not list it, including a store that
 * declares no filtering mode, is skipped with a message that names the capability.
 *
 * The filter cases place [excludedCount] propositions nearer the query than every match and leave
 * them out with the filter. A store that takes the nearest N of all propositions, drops the ones
 * that fail the filter and then cuts to K returns `min(K, N - excludedCount())` matches. The cases
 * run at K of 1 and of 5, so such a store passes only with a fixed window of `excludedCount() + 5`
 * or more, or with a window that grows with K by a factor of `excludedCount() + 1` or more.
 *
 * One case sets each of these filter fields in a context of its own: `entityId`, `minLevel`,
 * `pinned`, `minConfidence`, `createdAfter` and `minEffectiveConfidence`. Each context holds
 * [excludedCount] propositions that fail the field and sit nearer than the five that pass it. At K
 * of 1 and of 5 the store returns the K nearest that pass, or refuses the filter with
 * [UnsupportedCapabilityException]. The case names every field the store gets wrong in one run.
 *
 * One case runs for a store that declares [VectorFiltering.AFTER_TOP_K]. Such a store picks the
 * nearest K and then drops the ones that fail the filter. The case checks that every result passes
 * the filter, that results come back closest first and that a K covering every stored proposition
 * returns every match. It does not expect K results from a smaller K. A store that declares
 * [VectorFiltering.AFTER_TOP_K] and has no working [VectorSearchCapable] search fails the case.
 *
 * One case checks the declared vector filtering mode against [expectedVectorFiltering]. A test that
 * names its mode fails when the store's descriptor reports another.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
abstract class AbstractFilteredVectorSearchContractTest {

    /**
     * A store that holds no propositions and embeds text with [ConformanceEmbeddings.embed].
     */
    protected abstract fun store(): PropositionStore

    /**
     * How many nearer propositions the filter leaves out in the cases that check the filter runs
     * before the cut to K. The context, status and filter field cases all use this count. A store
     * that picks a window of candidates, filters it and then cuts to K passes those cases when the
     * window holds this many candidates plus five. A store with such a window returns a number above
     * the window.
     *
     * @return The number of excluded propositions that are nearer than every match. The default is 121.
     */
    protected open fun excludedCount(): Int = 121

    /**
     * How far a score may be from the exact cosine in the case that checks the score scale. The
     * contract states the scale and no accuracy, so the default leaves room for an index that
     * stores rounded vectors.
     *
     * @return The largest accepted difference. The default is 0.01.
     */
    protected open fun scoreTolerance(): Double = 0.01

    /**
     * The vector filtering mode this store is meant to declare. When a subclass returns a mode, one
     * case fails if [StoreCapabilities.describe] reports another, so a descriptor that is not wired
     * to the store is a failure and the other cases are not silently skipped.
     *
     * @return The expected mode, or null to leave the declared mode unchecked.
     */
    protected open fun expectedVectorFiltering(): VectorFiltering? = null

    private val tenant = "vector-tenant"
    private val neighbour = "vector-neighbour"
    private val queryText = ConformanceEmbeddings.text("query", 0.0)

    /** The angles of the matching fixture, farthest last. */
    private val matchDegrees = listOf(30.0, 40.0, 50.0, 60.0, 70.0)

    /**
     * The order the matching fixture is saved in, as indexes into [matchDegrees]. It is neither
     * closest first nor farthest first, so a store that returns matches in the order it holds them
     * fails the ordering case.
     */
    private val saveOrder = listOf(2, 0, 4, 1, 3)

    /**
     * Builds a proposition whose text embeds at an angle from the query.
     *
     * @param label The name of the text. Every fixture proposition gets its own.
     * @param degrees The angle from the query in degrees.
     * @param context The context the proposition belongs to.
     * @param status The proposition's status.
     * @return The proposition.
     */
    private fun proposition(label: String, degrees: Double, context: String, status: PropositionStatus): Proposition =
        Proposition(
            contextId = ContextId(context),
            text = ConformanceEmbeddings.text(label, degrees),
            mentions = emptyList(),
            confidence = 0.9,
            status = status,
        )

    /**
     * Saves five propositions at 30 to 70 degrees in [saveOrder], `prefix-0` closest.
     *
     * @param store The store to seed.
     * @param prefix The start of each label.
     * @param context The context the propositions belong to.
     * @param change Turns each fixture proposition into the one that is saved.
     * @return The texts of the saved propositions, closest first.
     */
    private fun saveMatches(
        store: PropositionStore,
        prefix: String,
        context: String,
        change: (Proposition) -> Proposition = { it },
    ): List<String> {
        val saved = saveOrder.associateWith { i ->
            store.save(change(proposition("$prefix-$i", matchDegrees[i], context, PropositionStatus.ACTIVE))).text
        }
        return matchDegrees.indices.map { saved.getValue(it) }
    }

    /**
     * Saves the five matching propositions in [tenant], `match-0` to `match-4` at 30 to 70 degrees.
     *
     * @param store The store to seed.
     * @return The texts of the matching propositions, closest first.
     */
    private fun seedMatching(store: PropositionStore): List<String> = saveMatches(store, "match", tenant)

    /**
     * Saves [excludedCount] propositions that sit nearer the query than every match. Each angle is
     * between 1.0 and 19.9 degrees for any count.
     *
     * @param store The store to seed.
     * @param context The context the propositions belong to.
     * @param status The status the propositions carry.
     */
    private fun seedNearer(store: PropositionStore, context: String, status: PropositionStatus) =
        (0 until excludedCount()).forEach { i ->
            store.save(proposition("near-$i", (10 + i % 190) / 10.0, context, status))
        }

    /**
     * Returns the store as [FilteredVectorSearch], or skips the case when the store does not list
     * [StoreCapability.FILTERED_VECTOR_SEARCH].
     *
     * @param store The store under test.
     * @return The store's filtered vector search.
     */
    private fun filteredSearch(store: PropositionStore): FilteredVectorSearch {
        val capabilities = StoreCapabilities.describe(store)
        assumeTrue(capabilities.supports(StoreCapability.FILTERED_VECTOR_SEARCH)) {
            "FILTERED_VECTOR_SEARCH is not declared by this store (vector filtering: ${capabilities.vectorFiltering})"
        }
        val search = StoreCapabilities.find(store, FilteredVectorSearch::class.java)
        assertNotNull(search) { "The store lists FILTERED_VECTOR_SEARCH and does not implement FilteredVectorSearch" }
        return search!!
    }

    /**
     * Runs a search and returns the texts of its matches in order.
     *
     * @param search The search under test.
     * @param request The request to run.
     * @return The texts of the matches, in the order the store returned them.
     */
    private fun texts(search: FilteredVectorSearch, request: VectorSearchRequest): List<String> =
        search.findNearest(request).map { it.proposition.text }

    /**
     * Seeds one filter field in a context of its own and checks the store applies or refuses it at
     * K of 1 and of 5. [excludedCount] propositions that fail the field sit at 1.0 to 19.9 degrees.
     * Five that pass sit at 30 to 70 degrees.
     *
     * @param store The store to seed.
     * @param search The search under test.
     * @param field The name of the filter field. It names the context and the labels.
     * @param filter Adds the field to a filter on the field's context.
     * @param failing Turns a fixture proposition into one that fails the field.
     * @param passing Turns a fixture proposition into one that passes the field.
     * @return A message that starts with the field name when the store gets the field wrong, or null.
     */
    private fun fieldFailure(
        store: PropositionStore,
        search: FilteredVectorSearch,
        field: String,
        filter: (PropositionQuery) -> PropositionQuery,
        failing: (Proposition) -> Proposition,
        passing: (Proposition) -> Proposition,
    ): String? {
        val context = "vector-field-$field"
        (0 until excludedCount()).forEach { i ->
            store.save(
                failing(proposition("$field-fail-$i", (10 + i % 190) / 10.0, context, PropositionStatus.ACTIVE)),
            )
        }
        val passingTexts = saveMatches(store, "$field-pass", context, passing)
        val request = VectorSearchRequest.forText(queryText, filter(PropositionQuery.againstContext(context)))
        return listOf(1, 5).firstNotNullOfOrNull { k ->
            val expected = passingTexts.take(k)
            try {
                val found = texts(search, request.withTopK(k))
                if (found == expected) {
                    null
                } else {
                    "$field: with K of $k the store returns $found. With the $field filter applied it returns $expected."
                }
            } catch (refused: UnsupportedCapabilityException) {
                if (refused.capability == StoreCapability.FILTERED_VECTOR_SEARCH) {
                    null
                } else {
                    "$field: with K of $k the store refused the filter for ${refused.capability}"
                }
            }
        }
    }

    @Test
    @DisplayName("a store that lists filtered vector search implements it")
    open fun aStoreThatListsFilteredVectorSearchImplementsIt() {
        filteredSearch(store())
    }

    @Test
    @DisplayName("K matches come back when the filter leaves out a nearer context")
    open fun kMatchesComeBackWhenANearerContextIsExcluded() {
        val store = store()
        val search = filteredSearch(store)
        seedNearer(store, neighbour, PropositionStatus.ACTIVE)
        val matching = seedMatching(store)
        val request = VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant))

        listOf(1, 5).forEach { k ->
            assertEquals(matching.take(k), texts(search, request.withTopK(k))) {
                "With K of $k the store returns the $k nearest propositions in $tenant. " +
                    "${excludedCount()} nearer propositions in $neighbour are left out by the filter."
            }
        }
    }

    @Test
    @DisplayName("a status filter is applied before the cut to K or refused")
    open fun aStatusFilterIsAppliedBeforeTheCutOrRefused() {
        val store = store()
        val search = filteredSearch(store)
        seedNearer(store, tenant, PropositionStatus.SUPERSEDED)
        val matching = seedMatching(store)
        val request = VectorSearchRequest.forText(
            queryText,
            PropositionQuery.againstContext(tenant).withStatus(PropositionStatus.ACTIVE),
        )

        listOf(1, 5).forEach { k ->
            try {
                assertEquals(matching.take(k), texts(search, request.withTopK(k))) {
                    "With K of $k the store returns the $k nearest ACTIVE propositions. " +
                        "${excludedCount()} nearer SUPERSEDED propositions are left out by the filter."
                }
            } catch (refused: UnsupportedCapabilityException) {
                assertEquals(StoreCapability.FILTERED_VECTOR_SEARCH, refused.capability) {
                    "With K of $k the store refused the status filter for another capability"
                }
            }
        }
    }

    @Test
    @DisplayName("matches are ordered closest first and cut to K after ordering")
    open fun matchesAreOrderedClosestFirstAndCutAfterOrdering() {
        val store = store()
        val search = filteredSearch(store)
        val matching = seedMatching(store)

        val matches = search.findNearest(
            VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant)).withTopK(3),
        )

        assertEquals(matching.take(3), matches.map { it.proposition.text })
        matches.zipWithNext().forEach { (closer, farther) ->
            assertTrue(closer.score >= farther.score) {
                "${closer.score} comes before ${farther.score}, so the matches are not closest first"
            }
        }
    }

    @Test
    @DisplayName("each filter field is applied or refused")
    open fun eachFilterFieldIsAppliedOrRefused() {
        val store = store()
        val search = filteredSearch(store)
        val revised = Instant.parse("2026-05-31T00:00:00Z")
        val failures = listOfNotNull(
            fieldFailure(
                store, search, "entityId",
                { it.withEntityId("entity-1") },
                { it.copy(mentions = emptyList()) },
                { it.copy(mentions = listOf(EntityMention(span = "Alice", type = "Person", resolvedId = "entity-1"))) },
            ),
            fieldFailure(
                store, search, "minLevel",
                { it.withMinLevel(1) },
                { it.copy(level = 0) },
                { it.copy(level = 1, sourceIds = listOf("minLevel-source")) },
            ),
            fieldFailure(
                store, search, "pinned",
                { it.withPinned(true) },
                { it.copy(pinned = false) },
                { it.copy(pinned = true) },
            ),
            fieldFailure(
                store, search, "minConfidence",
                { it.withMinConfidence(0.5) },
                { it.copy(confidence = 0.2) },
                { it.copy(confidence = 0.9) },
            ),
            fieldFailure(
                store, search, "createdAfter",
                { it.withCreatedAfter(Instant.parse("2026-02-01T00:00:00Z")) },
                { it.copy(created = Instant.parse("2026-01-01T00:00:00Z")) },
                { it.copy(created = Instant.parse("2026-03-01T00:00:00Z")) },
            ),
            fieldFailure(
                store, search, "minEffectiveConfidence",
                { it.withMinEffectiveConfidence(0.5).withEffectiveConfidenceAsOf(Instant.parse("2026-06-30T00:00:00Z")) },
                { it.copy(confidence = 0.9, decay = 1.0, contentRevised = revised, lastAccessed = revised) },
                { it.copy(confidence = 0.9, decay = 0.0, contentRevised = revised, lastAccessed = revised) },
            ),
        )

        assertAll("Filter fields the store does not apply", failures.map { message -> Executable { fail<Unit>(message) } })
    }

    @Test
    @DisplayName("a match that scores exactly the floor is returned")
    open fun aMatchThatScoresExactlyTheFloorIsReturned() {
        val store = store()
        val search = filteredSearch(store)
        val matching = seedMatching(store)
        val request = VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant)).withTopK(5)
        val unfloored = search.findNearest(request)
        assertTrue(unfloored.size >= 3) { "With no floor the store returns ${unfloored.size} of the 5 matches" }
        val floor = unfloored[2].score

        assertEquals(matching.take(3), texts(search, request.withMinScore(floor))) {
            "With the floor at the third match's score $floor the store returns the three matches that score at or above it"
        }
    }

    @Test
    @DisplayName("equal scores come back in the same order every time")
    open fun equalScoresKeepOneOrder() {
        val store = store()
        val search = filteredSearch(store)
        listOf("tie-0", "tie-1", "tie-2").forEach { label ->
            store.save(proposition(label, 30.0, tenant, PropositionStatus.ACTIVE))
        }
        store.save(proposition("after-tie", 40.0, tenant, PropositionStatus.ACTIVE))
        val request = VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant))

        listOf(4, 2).forEach { k ->
            val runs = (1..3).map { search.findNearest(request.withTopK(k)).map { it.proposition.id } }
            assertEquals(1, runs.distinct().size) {
                "With K of $k three runs of the same request return the ids $runs. Equal scores come back in one order."
            }
        }
    }

    @Test
    @DisplayName("scores are cosine similarity from minus one to one")
    open fun scoresAreCosineSimilarity() {
        val store = store()
        val search = filteredSearch(store)
        val expected = listOf(0.0 to 1.0, 60.0 to 0.5, 90.0 to 0.0, 120.0 to -0.5, 180.0 to -1.0)
        listOf(90.0, 0.0, 180.0, 60.0, 120.0).forEach { degrees ->
            store.save(proposition("scale-$degrees", degrees, tenant, PropositionStatus.ACTIVE))
        }

        val scores = search.findNearest(
            VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant)).withTopK(5),
        ).map { it.score.cosine }

        assertEquals(expected.size, scores.size) { "The store returns the scores $scores for five propositions" }
        expected.zip(scores).forEach { (angleAndCosine, score) ->
            val (degrees, cosine) = angleAndCosine
            assertEquals(cosine, score, scoreTolerance()) {
                "The proposition at $degrees degrees scores $score. Its cosine similarity to the query is $cosine."
            }
        }
    }

    @Test
    @DisplayName("a proposition with an all-zero embedding is not returned")
    open fun aPropositionWithAnAllZeroEmbeddingIsNotReturned() {
        val store = store()
        val search = filteredSearch(store)
        store.save(
            proposition("blank", 0.0, tenant, PropositionStatus.ACTIVE)
                .copy(text = ConformanceEmbeddings.textWithoutDirection("blank")),
        )
        val directed = store.save(proposition("directed", 30.0, tenant, PropositionStatus.ACTIVE)).text

        assertEquals(
            listOf(directed),
            texts(search, VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant)).withTopK(5)),
        ) { "The store returns only the proposition whose embedding has a direction" }
    }

    @Test
    @DisplayName("text that embeds to all zeros matches nothing")
    open fun textThatEmbedsToAllZerosMatchesNothing() {
        val store = store()
        val search = filteredSearch(store)
        seedMatching(store)
        val request = VectorSearchRequest.forText(
            ConformanceEmbeddings.textWithoutDirection("query"),
            PropositionQuery.againstContext(tenant),
        )

        assertEquals(emptyList<String>(), texts(search, request)) {
            "A request whose text embeds to all zeros returns no matches"
        }
    }

    @Test
    @DisplayName("a filter that matches nothing returns no matches")
    open fun aFilterThatMatchesNothingReturnsNoMatches() {
        val store = store()
        val search = filteredSearch(store)
        matchDegrees.forEachIndexed { i, degrees ->
            store.save(proposition("match-$i", degrees, neighbour, PropositionStatus.ACTIVE))
        }

        assertEquals(
            emptyList<String>(),
            texts(search, VectorSearchRequest.forText(queryText, PropositionQuery.againstContext(tenant))),
        ) { "Every proposition is in $neighbour, so a filter on $tenant returns no matches" }
    }

    @Test
    @DisplayName("a store that filters after the cut returns matching propositions closest first, and every match when K covers them all")
    open fun aStoreThatFiltersAfterTheCutKeepsThatModesPromise() {
        val store = store()
        val capabilities = StoreCapabilities.describe(store)
        assumeTrue(capabilities.vectorFiltering == VectorFiltering.AFTER_TOP_K) {
            "AFTER_TOP_K is not this store's vector filtering mode (${capabilities.vectorFiltering})"
        }
        if (store !is VectorSearchCapable || !store.supportsVector) {
            throw AssertionFailedError("The store declares AFTER_TOP_K and has no working VectorSearchCapable search. A store with no similarity search declares NONE.")
        }
        val matching = seedMatching(store)
        (0 until 5).forEach { i ->
            store.save(proposition("other-$i", (i + 1).toDouble(), neighbour, PropositionStatus.ACTIVE))
        }
        val filter = PropositionQuery.againstContext(tenant)

        val cut = store.findSimilarWithScores(
            TextSimilaritySearchRequest(query = queryText, similarityThreshold = 0.0, topK = 3),
            filter,
        )
        assertTrue(cut.size <= 3) { "With K of 3 the store returns ${cut.size} results" }
        cut.forEach { result ->
            assertEquals(tenant, result.match.contextId.value) {
                "The store returns ${result.match.text} from ${result.match.contextId.value}. The filter keeps only $tenant."
            }
        }
        cut.zipWithNext().forEach { (closer, farther) ->
            assertTrue(closer.score >= farther.score) {
                "${closer.score} comes before ${farther.score}, so the results are not closest first"
            }
        }

        val covered = store.findSimilarWithScores(
            TextSimilaritySearchRequest(query = queryText, similarityThreshold = 0.0, topK = 10),
            filter,
        )
        assertEquals(matching, covered.map { it.match.text }) {
            "With K of 10 every stored proposition is in the cut, so the store returns the five in $tenant closest first"
        }
    }

    @Test
    @DisplayName("the store declares the vector filtering mode its test expects")
    open fun theStoreDeclaresTheExpectedVectorFiltering() {
        val expected = expectedVectorFiltering()
        assumeTrue(expected != null) { "expectedVectorFiltering() returns null, so the declared mode is not checked" }
        val declared = StoreCapabilities.describe(store()).vectorFiltering
        assertEquals(expected, declared) { "The store declares $declared and its test expects $expected" }
    }
}
