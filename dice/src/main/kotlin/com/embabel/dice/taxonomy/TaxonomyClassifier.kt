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

import com.embabel.dice.proposition.Proposition
import org.jetbrains.annotations.ApiStatus

/**
 * Classifies propositions against a caller-provided taxonomy.
 */
@ApiStatus.Experimental
fun interface TaxonomyClassifier {

    /**
     * Return the best taxonomy node identifier for [proposition], or `null` to abstain.
     */
    fun classify(proposition: Proposition, taxonomy: Taxonomy): String?
}

/**
 * Classifies propositions by matching taxonomy labels and keywords in proposition text.
 *
 * Matches are case-insensitive whole words. When multiple nodes match, the deepest node
 * wins; declaration order breaks ties.
 */
@ApiStatus.Experimental
class LexicalTaxonomyClassifier : TaxonomyClassifier {

    override fun classify(proposition: Proposition, taxonomy: Taxonomy): String? =
        taxonomy.nodes
            .filter { node ->
                (node.keywords + node.label)
                    .filter(String::isNotBlank)
                    .any { term ->
                        Regex(
                            pattern = "(?U)(?<!\\w)${Regex.escape(term)}(?!\\w)",
                            option = RegexOption.IGNORE_CASE,
                        ).containsMatchIn(proposition.text)
                    }
            }
            .maxByOrNull { node -> taxonomy.ancestors(node.id).size }
            ?.id
}
