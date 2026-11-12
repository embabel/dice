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

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.jetbrains.annotations.ApiStatus

/**
 * An embedding service that embeds with [ConformanceEmbeddings], so a store can run the similarity suite.
 *
 * Build the store under test with this service. It rejects a text that has no angle marker, because
 * the suite needs every stored text to sit at a known angle.
 *
 * Java:
 * ```java
 * new InMemoryPropositionRepository(new ConformanceEmbeddingService());
 * ```
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @param dimensions the length of each embedding
 */
@ApiStatus.Experimental
class ConformanceEmbeddingService @JvmOverloads constructor(
    override val dimensions: Int = DEFAULT_DIMENSIONS,
) : EmbeddingService {

    override val name: String = "conformance-embeddings"
    override val provider: String = "dice-store-conformance"
    override val pricingModel: PricingModel? = null

    /**
     * Returns the embedding of a text that ends in an angle marker.
     *
     * @param text a text that ends in a marker, such as one built by [ConformanceEmbeddings.text]
     * @return the embedding, with [dimensions] components
     * @throws IllegalArgumentException if the text has no angle marker
     */
    override fun embed(text: String): FloatArray = ConformanceEmbeddings.embed(text, dimensions)

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)

    companion object {
        /** The number of dimensions of a service built with no argument. */
        const val DEFAULT_DIMENSIONS: Int = 8
    }
}
