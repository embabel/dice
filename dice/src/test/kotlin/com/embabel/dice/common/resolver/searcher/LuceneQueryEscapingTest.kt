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

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryparser.classic.QueryParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LuceneQueryEscapingTest {

    @Test
    fun `passes a plain name through unchanged`() {
        assertEquals("Johannes Brahms", luceneEscape("Johannes Brahms"))
    }

    @Test
    fun `escapes a colon`() {
        assertEquals("""Acme Corp\: R\&D""", luceneEscape("Acme Corp: R&D"))
    }

    @Test
    fun `escapes an embedded quote`() {
        assertEquals("""Acme \"Legacy\" Corp""", luceneEscape("""Acme "Legacy" Corp"""))
    }

    @Test
    fun `escapes a slash`() {
        assertEquals("""NY\/Boston""", luceneEscape("NY/Boston"))
    }

    @Test
    fun `escapes a hyphen`() {
        assertEquals("""R\-D""", luceneEscape("R-D"))
    }

    @Test
    fun `escapes parens`() {
        assertEquals("""\(NY\)""", luceneEscape("(NY)"))
    }

    @Test
    fun `escapes a trailing tilde`() {
        assertEquals("""Boston\~""", luceneEscape("Boston~"))
    }

    @Test
    fun `escaped output is valid Lucene query syntax for a hostile name`() {
        val analyzer = StandardAnalyzer()
        val hostile = "Acme Corp: R&D (NY/Boston)~"
        try {
            QueryParser("name", analyzer).parse(luceneEscape(hostile))
        } finally {
            analyzer.close()
        }
    }

    @Test
    fun `exact phrase query escapes first so an embedded quote cannot close the phrase early`() {
        val query = luceneExactPhraseQuery("""Acme "Legacy" Corp""")
        assertEquals("\"Acme \\\"Legacy\\\" Corp\"", query)

        val analyzer = StandardAnalyzer()
        try {
            val parsed = QueryParser("name", analyzer).parse(query)
            assertEquals("name:\"acme legacy corp\"", parsed.toString())
        } finally {
            analyzer.close()
        }
    }

    @Test
    fun `exact phrase query for a plain name matches today's unescaped quote-wrap`() {
        assertEquals("\"Johannes Brahms\"", luceneExactPhraseQuery("Johannes Brahms"))
    }
}
