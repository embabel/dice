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
package com.embabel.dice.storage.autoconfigure

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.PersistenceManager
import org.drivine.schema.IndexManager
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The index is made when a model appears, not when the process starts.
 *
 * A host that starts without an embedding model cannot register this index: it is created AT the
 * model's dimension. The catalog therefore registers nothing, on the stated assumption that the
 * next boot would have one. A host that applies a provider key WITHOUT restarting never has a next
 * boot, and the index was never made — the model came alive, was used, and every read failed with
 * "There is no such vector schema index".
 */
class PropositionVectorIndexConvergenceTest {

    private class FakeEmbedding(override val dimensions: Int) : EmbeddingService {
        override val name = "text-embedding-3-small"
        override val provider = "openai"
        override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT
        override fun embed(text: String) = FloatArray(dimensions)
        override fun embed(texts: List<String>) = texts.map { embed(it) }
    }

    private fun convergence(
        indexes: IndexManager,
        dimensions: () -> Int?,
    ): PropositionVectorIndexConvergence {
        val persistence = mock<PersistenceManager>()
        whenever(persistence.indexes).thenReturn(indexes)
        return PropositionVectorIndexConvergence(
            persistenceManager = persistence,
            dimensionsOf = dimensions,
            spec = { dims -> VectorIndexSpec("Proposition", "embedding", dims, name = "Proposition_embedding_vector") },
        )
    }

    @Test
    @DisplayName("with a model, the index is ensured at that model's dimension")
    fun `ensures at the model dimension`() {
        val indexes = mock<IndexManager>()

        val made = convergence(indexes) { 1536 }.ensure()

        assertThat(made).isTrue()
        val spec = org.mockito.kotlin.argumentCaptor<VectorIndexSpec>()
        verify(indexes).ensure(spec.capture())
        // The dimension is the point: an index made at the wrong one accepts writes a real model
        // would disagree with, which is why none is made without a model to ask.
        assertThat(spec.firstValue.dimensions).isEqualTo(1536)
    }

    @Test
    @DisplayName("with no model, nothing is touched and it says so")
    fun `no model is not an error`() {
        val indexes = mock<IndexManager>()

        val made = convergence(indexes) { null }.ensure()

        // The ordinary answer on a host that has not been given a key yet. A caller may ask
        // hopefully, and often will — see the idempotence note below.
        assertThat(made).isFalse()
        verify(indexes, never()).ensure(any())
    }

    @Test
    @DisplayName("the dimension is read on every call, so a model that arrives later is seen")
    fun `the model is resolved per call`() {
        val indexes = mock<IndexManager>()
        var model: EmbeddingService? = null
        val resolve = PropositionVectorIndexConvergence.dimensionsFrom {
            model ?: error("no embedding model is configured")
        }
        val convergence = convergence(indexes, resolve)

        // Before the key: nothing to make, and asking must not throw.
        assertThat(convergence.ensure()).isFalse()

        // The whole point of the class: the model arrives after the bean was built.
        model = FakeEmbedding(3072)

        assertThat(convergence.ensure()).isTrue()
        val spec = org.mockito.kotlin.argumentCaptor<VectorIndexSpec>()
        verify(indexes).ensure(spec.capture())
        assertThat(spec.firstValue.dimensions).isEqualTo(3072)
    }

    @Test
    @DisplayName("a model that refuses a dimension counts as no model")
    fun `a placeholder is not a model`() {
        val indexes = mock<IndexManager>()
        // A placeholder that exists so injection works, and throws rather than inventing a
        // dimension. Indistinguishable from absence for this purpose, and treated the same.
        val resolve = PropositionVectorIndexConvergence.dimensionsFrom {
            object : EmbeddingService {
                override val name = "setup-required-embedding"
                override val provider = "none"
                override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT
                override val dimensions: Int get() = error("no embedding model is available")
                override fun embed(text: String) = FloatArray(0)
                override fun embed(texts: List<String>) = emptyList<FloatArray>()
            }
        }

        assertThat(convergence(indexes, resolve).ensure()).isFalse()
        verify(indexes, never()).ensure(any())
    }

    @Test
    @DisplayName("a failure to converge is reported, not thrown at the host")
    fun `a failure is soft`() {
        val indexes = mock<IndexManager>()
        whenever(indexes.ensure(any())).thenThrow(IllegalStateException("the database is not up"))

        // The host calls this from wherever it noticed a key arrive. Taking that path down
        // because an index could not be made would be a worse outcome than no index.
        assertThat(convergence(indexes) { 1536 }.ensure()).isFalse()
    }
}
