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

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.PropositionRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The proposition store embeds with the host's embedding service whichever backend it runs.
 *
 * The graph backend already preferred a unique or `@Primary` host bean; the in-memory backend went
 * straight to the platform default, so a host that swapped its model at runtime had in-memory
 * propositions embedded by a different one. These pin the in-memory half; both halves now share one
 * resolution.
 */
class PropositionStoreEmbeddingServiceTest {

    private val platformDefault: EmbeddingService = embeddingService("platform-default")
    private val hostService: EmbeddingService = embeddingService("host-model")

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
        .withBean(Ai::class.java, {
            mock<Ai>().also { whenever(it.withDefaultEmbeddingService()).thenReturn(platformDefault) }
        })

    @Test
    fun `the in-memory store embeds with the host's primary embedding service`() {
        runner
            .withUserConfiguration(HostEmbedding::class.java)
            .withBean("hostService", EmbeddingService::class.java, { hostService }, { it.isPrimary = true })
            .run { ctx ->
                ctx.getBean(PropositionRepository::class.java).save(TestFixtures.proposition("Alice likes the Alps"))

                verify(hostService).embed("Alice likes the Alps")
                verify(platformDefault, never()).embed(any<String>())
            }
    }

    @Test
    fun `the in-memory store embeds with the platform default when the host provides none`() {
        runner.run { ctx ->
            ctx.getBean(PropositionRepository::class.java).save(TestFixtures.proposition("Alice likes the Alps"))

            verify(platformDefault).embed("Alice likes the Alps")
        }
    }

    /** A second, non-primary model bean — the platform registers one per model it can reach. */
    @Configuration
    open class HostEmbedding {
        @Bean
        open fun otherModel(): EmbeddingService = embeddingService("other-model")
    }

    private companion object {
        fun embeddingService(name: String): EmbeddingService = mock {
            whenever(it.name).thenReturn(name)
            whenever(it.provider).thenReturn("test")
            whenever(it.dimensions).thenReturn(3)
            whenever(it.embed(any<String>())).thenReturn(floatArrayOf(1f, 0f, 0f))
        }
    }
}
