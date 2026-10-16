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
package com.embabel.dice.storage.falkordb.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration
import com.embabel.dice.storage.falkordb.FalkorDbChunkHistoryStore
import com.embabel.dice.storage.falkordb.FalkorDbPropositionRepository
import com.embabel.dice.storage.falkordb.FalkorDbTestServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.schema.SchemaManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@Testcontainers
class FalkorDbStorageIntegrationTest {
    private fun graph() = "starter_${UUID.randomUUID().toString().replace("-", "")}"

    private fun runner(graph: String) = ApplicationContextRunner()
        .withInitializer { context ->
            context.beanFactory.addBeanPostProcessor(object : DestructionAwareBeanPostProcessor {
                override fun requiresDestruction(bean: Any) = bean is DatabaseRegistry
                override fun postProcessBeforeDestruction(bean: Any, beanName: String) {
                    if (bean is DatabaseRegistry) bean.databaseNames.forEach { bean.connectionProvider(it)?.end() }
                }
            })
        }
        .withConfiguration(AutoConfigurations.of(
            FalkorDbStorageAutoConfiguration::class.java, DiceStorageAutoConfiguration::class.java,
        ))
        .withPropertyValues(
            "embabel.dice.store.type=falkordb",
            "embabel.dice.store.falkordb.datasource=knowledge",
            "embabel.dice.store.decay.enabled=false",
            "database.datasources.knowledge.type=FALKORDB",
            "database.datasources.knowledge.host=${server.host}",
            "database.datasources.knowledge.port=${server.getMappedPort(6379)}",
            "database.datasources.knowledge.database-name=$graph",
            "database.datasources.knowledge.cypher-dialect=FALKORDB",
        )

    @Test
    fun `property bound stores persist across contexts with schema applied and STRICT transactions`() {
        val graph = graph()
        val fact = Proposition(contextId = ContextId("example"), text = "A durable fact", mentions = emptyList(), confidence = 0.9,
            metadata = mapOf("nested" to listOf("one", "two")))
        val record = ProcessedChunkRecord(BookmarkKey(ContextId("example"), "source"), HashKey(ContextId("example"), "hash"),
            0, 4, Instant.parse("2026-04-05T06:07:08.123456789Z"))
        runner(graph).run { context ->
            assertThat(context).hasNotFailed()
            // Spring Boot runs the schema manager at startup; a context runner does not, so run it here.
            context.getBean(SchemaManager::class.java).enforce()
            assertThat(context.getBean(DataSourceMap::class.java).dataSources.getValue("knowledge").falkorDbTransactionMode)
                .isEqualTo(FalkorDbTransactionMode.STRICT)
            val repository = context.getBean(PropositionRepository::class.java)
            assertThat(repository).isInstanceOf(FalkorDbPropositionRepository::class.java)
            assertThat(repository.supportsVector).isFalse()
            repository.save(fact)
            val chunks = context.getBean(ChunkHistoryStore::class.java)
            assertThat(chunks).isInstanceOf(FalkorDbChunkHistoryStore::class.java)
            chunks.recordProcessed(record)
        }
        runner(graph).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(SchemaManager::class.java).enforce()
            assertThat(context.getBean(PropositionRepository::class.java).findById(fact.id)).isEqualTo(fact)
            val chunks = context.getBean(ChunkHistoryStore::class.java)
            assertThat(chunks.isProcessed(record.hashKey)).isTrue()
            assertThat(chunks.getLastBookmark(record.bookmarkKey)?.processedAt).isEqualTo(record.processedAt)
        }
    }

    @Test
    fun `default alias resolves the datasource and application stores win`() {
        val repository = InMemoryPropositionRepository()
        runner(graph()).withPropertyValues("embabel.dice.store.falkordb.datasource=default")
            .withBean(PropositionRepository::class.java, { repository }).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(PropositionRepository::class.java)).isSameAs(repository)
                assertThat(context.getBean(ChunkHistoryStore::class.java)).isInstanceOf(FalkorDbChunkHistoryStore::class.java)
            }
        val chunks = InMemoryChunkHistoryStore()
        runner(graph()).withBean(ChunkHistoryStore::class.java, { chunks }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(ChunkHistoryStore::class.java)).isSameAs(chunks)
            assertThat(context.getBean(PropositionRepository::class.java)).isInstanceOf(FalkorDbPropositionRepository::class.java)
        }
    }

    private class SyntheticEmbeddingService : EmbeddingService {
        override val name = "synthetic"
        override val provider = "local-test"
        override val dimensions = 2
        override val pricingModel: PricingModel? = null
        val calls = mutableListOf<String>()
        override fun embed(text: String) = floatArrayOf(1f, 0f).also { calls += text }
        override fun embed(texts: List<String>) = texts.map(::embed)
    }

    @Test
    fun `an application embedding bean enables vector search`() {
        runner(graph()).withBean(EmbeddingService::class.java, { SyntheticEmbeddingService() }).run { context ->
            assertThat(context).hasNotFailed()
            val repository = context.getBean(PropositionRepository::class.java)
            assertThat(repository.supportsVector).isTrue()
            val saved = repository.save(Proposition(contextId = ContextId("example"), text = "Embedded fact", mentions = emptyList(), confidence = 0.9))
            assertThat(repository.findSimilarWithScores(TextSimilaritySearchRequest("fact", 0.0, 5), PropositionQuery.againstContext("example")))
                .hasSize(1)
            assertThat(repository.findById(saved.id)).isEqualTo(saved)
        }
    }

    @Test
    fun `the exact vector candidate budget reaches the store`() {
        runner(graph()).withPropertyValues("embabel.dice.store.exact-vector.max-candidates=1")
            .withBean(EmbeddingService::class.java, { SyntheticEmbeddingService() }).run { context ->
                assertThat(context).hasNotFailed()
                val repository = context.getBean(PropositionRepository::class.java)
                val request = TextSimilaritySearchRequest("fact", 0.0, 5)
                val filter = PropositionQuery.againstContext("example")
                repository.save(Proposition(contextId = ContextId("example"), text = "First fact", mentions = emptyList(), confidence = 0.9))
                assertThat(repository.findSimilarWithScores(request, filter)).hasSize(1)
                repository.save(Proposition(contextId = ContextId("example"), text = "Second fact", mentions = emptyList(), confidence = 0.9))
                assertThatThrownBy { repository.findSimilarWithScores(request, filter) }
                    .isInstanceOfSatisfying(ScopedReadException::class.java) {
                        assertThat(it.failure).isEqualTo(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED)
                    }
            }
    }

    @Test
    fun `several embedding beans and no primary fall back to the platform default`() {
        val platformDefault = SyntheticEmbeddingService()
        runner(graph())
            .withBean(Ai::class.java, { mock<Ai>().also { whenever(it.withDefaultEmbeddingService()).thenReturn(platformDefault) } })
            .withBean("first", EmbeddingService::class.java, { SyntheticEmbeddingService() })
            .withBean("second", EmbeddingService::class.java, { SyntheticEmbeddingService() })
            .run { context ->
                assertThat(context).hasNotFailed()
                val repository = context.getBean(PropositionRepository::class.java)
                assertThat(repository.supportsVector).isTrue()
                repository.save(Proposition(contextId = ContextId("example"), text = "Embedded fact", mentions = emptyList(), confidence = 0.9))
                assertThat(platformDefault.calls).isEqualTo(listOf("Embedded fact"))
            }
    }

    companion object {
        @Container
        @JvmField
        val server = FalkorDbTestServer()
    }
}
