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
package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.SuggestedEntity
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Entity names come from arbitrary source text, so they can contain any of the
 * characters Lucene's classic QueryParser treats as syntax: `: " / - ( ) ~ *`
 * and friends. Each of these searchers builds a fulltext query straight from an
 * entity name and hands it to [NamedEntityDataRepository.textSearch] — where it
 * ultimately reaches Neo4j's `db.index.fulltext.queryNodes`, which re-parses the
 * string with Lucene internally. Cypher parameter binding doesn't protect it: to
 * Lucene, the string is still query syntax, not a literal.
 *
 * These tests capture the query each searcher actually builds for a hostile name
 * and feed it to the real Lucene [QueryParser], proving it parses cleanly rather
 * than trusting our own escaping.
 */
class CandidateSearcherLuceneEscapingTest {

    // Real-world-shaped hostile name: colon, ampersand, parens, slash, trailing tilde.
    private val hostileName = "Acme Corp: R&D (NY/Boston)~"

    private lateinit var repository: NamedEntityDataRepository
    private val schema: DataDictionary = DataDictionary.fromClasses("test")

    private fun suggested(name: String) = SuggestedEntity(
        labels = listOf("Organization"),
        name = name,
        summary = "",
        chunkId = "chunk-1",
    )

    private fun capturedQuery(slot: CapturingSlot<TextSimilaritySearchRequest>): String {
        if (!slot.isCaptured) {
            fail<Unit>("textSearch was never called — searcher swallowed an earlier failure")
        }
        return slot.captured.query
    }

    private fun assertLuceneParseable(query: String) {
        val analyzer = StandardAnalyzer()
        try {
            QueryParser("name", analyzer).parse(query)
        } catch (e: Exception) {
            fail<Unit>("query '$query' is not valid Lucene syntax: ${e.message}")
        } finally {
            analyzer.close()
        }
    }

    private fun parse(query: String): Query {
        val analyzer = StandardAnalyzer()
        return try {
            QueryParser("name", analyzer).parse(query)
        } catch (e: Exception) {
            fail<Unit>("query '$query' is not valid Lucene syntax: ${e.message}")
            throw e
        } finally {
            analyzer.close()
        }
    }

    @Test
    fun `ByExactNameCandidateSearcher builds a Lucene-parseable query for a hostile name`() {
        repository = mockk(relaxed = true)
        val slot = slot<TextSimilaritySearchRequest>()
        every { repository.textSearch(capture(slot), any(), any()) } returns emptyList()

        ByExactNameCandidateSearcher(repository).search(suggested(hostileName), schema)

        assertLuceneParseable(capturedQuery(slot))
    }

    @Test
    fun `PartialNameCandidateSearcher builds a Lucene-parseable query for a hostile name`() {
        repository = mockk(relaxed = true)
        val slot = slot<TextSimilaritySearchRequest>()
        every { repository.textSearch(capture(slot), any(), any()) } returns emptyList()

        PartialNameCandidateSearcher(repository).search(suggested(hostileName), schema)

        assertLuceneParseable(capturedQuery(slot))
    }

    @Test
    fun `FuzzyNameCandidateSearcher builds a Lucene-parseable query for a hostile name`() {
        repository = mockk(relaxed = true)
        val slot = slot<TextSimilaritySearchRequest>()
        every { repository.textSearch(capture(slot), any(), any()) } returns emptyList()

        FuzzyNameCandidateSearcher(repository).search(suggested(hostileName), schema)

        assertLuceneParseable(capturedQuery(slot))
    }

    @Test
    fun `NormalizedNameCandidateSearcher builds a Lucene-parseable query for a hostile name`() {
        repository = mockk(relaxed = true)
        val slot = slot<TextSimilaritySearchRequest>()
        every { repository.textSearch(capture(slot), any(), any()) } returns emptyList()

        NormalizedNameCandidateSearcher(repository).search(suggested(hostileName), schema)

        assertLuceneParseable(capturedQuery(slot))
    }

    @Test
    fun `ByExactNameCandidateSearcher keeps a name with an embedded quote as one exact phrase`() {
        // A naive quote-wrap doesn't throw here — the embedded quote closes the
        // phrase early and Lucene happily reparses the rest as loose OR'd terms.
        // No exception, but "exact match" semantics are gone: it's no longer a
        // PhraseQuery. Assert the real invariant, not just "didn't throw".
        repository = mockk(relaxed = true)
        val slot = slot<TextSimilaritySearchRequest>()
        every { repository.textSearch(capture(slot), any(), any()) } returns emptyList()

        ByExactNameCandidateSearcher(repository).search(suggested("""Acme "Legacy" Corp"""), schema)

        val query = parse(capturedQuery(slot))
        assertTrue(query is PhraseQuery, "expected an exact-phrase query, got ${query.javaClass.simpleName}: $query")
    }

    @Test
    fun `ByExactNameCandidateSearcher still produces an exact-phrase query for a plain name`() {
        repository = mockk(relaxed = true)
        val slot = slot<TextSimilaritySearchRequest>()
        every { repository.textSearch(capture(slot), any(), any()) } returns emptyList()

        ByExactNameCandidateSearcher(repository).search(suggested("Johannes Brahms"), schema)

        assertEquals("\"Johannes Brahms\"", capturedQuery(slot))
    }
}
