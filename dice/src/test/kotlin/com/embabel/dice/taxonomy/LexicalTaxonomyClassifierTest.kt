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
package com.embabel.dice.taxonomy

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexicalTaxonomyClassifierTest {

    private val classifier = LexicalTaxonomyClassifier()

    @Test
    fun `returns the node whose keyword matches`() {
        val taxonomy = Taxonomy(
            listOf(
                TaxonomyNode(id = "science", label = "Science", keywords = listOf("physics")),
                TaxonomyNode(id = "art", label = "Art"),
            ),
        )

        assertThat(classifier.classify(proposition("Physics explains motion"), taxonomy))
            .isEqualTo("science")
    }

    @Test
    fun `returns the deepest node when multiple nodes match`() {
        val taxonomy = Taxonomy(
            listOf(
                TaxonomyNode(id = "technology", label = "Technology"),
                TaxonomyNode(
                    id = "software",
                    label = "Software",
                    parentId = "technology",
                    keywords = listOf("code"),
                ),
            ),
        )

        assertThat(classifier.classify(proposition("Technology depends on code"), taxonomy))
            .isEqualTo("software")
    }

    @Test
    fun `returns the first declared node when matching nodes have equal depth`() {
        val taxonomy = Taxonomy(
            listOf(
                TaxonomyNode(id = "first", label = "First", keywords = listOf("shared")),
                TaxonomyNode(id = "second", label = "Second", keywords = listOf("shared")),
            ),
        )

        assertThat(classifier.classify(proposition("A shared keyword"), taxonomy))
            .isEqualTo("first")
    }

    @Test
    fun `abstains when no node matches`() {
        val taxonomy = Taxonomy(
            listOf(TaxonomyNode(id = "science", label = "Science")),
        )

        assertThat(classifier.classify(proposition("A completely unrelated statement"), taxonomy))
            .isNull()
    }

    @Test
    fun `does not match a keyword inside another word`() {
        val taxonomy = Taxonomy(
            listOf(TaxonomyNode(id = "art", label = "Creativity", keywords = listOf("art"))),
        )

        assertThat(classifier.classify(proposition("Start here"), taxonomy)).isNull()
    }

    @Test
    fun `does not match before unicode word continuations`() {
        val taxonomy = Taxonomy(
            listOf(TaxonomyNode(id = "art", label = "Creativity", keywords = listOf("art"))),
        )

        listOf(
            "art\u0301ist",
            "art\u203Fist",
            "art\u200Dist",
        ).forEach { text ->
            assertThat(classifier.classify(proposition(text), taxonomy))
                .describedAs("classification for %s", text)
                .isNull()
        }
    }

    @Test
    fun `matches escaped regex metacharacters as a complete term`() {
        val taxonomy = Taxonomy(
            listOf(TaxonomyNode(id = "cpp", label = "C plus plus", keywords = listOf("c++"))),
        )

        assertThat(classifier.classify(proposition("C++ is a programming language"), taxonomy))
            .isEqualTo("cpp")
    }

    private fun proposition(text: String) = Proposition(
        contextId = ContextId("test"),
        text = text,
        mentions = emptyList(),
        confidence = 1.0,
    )
}
