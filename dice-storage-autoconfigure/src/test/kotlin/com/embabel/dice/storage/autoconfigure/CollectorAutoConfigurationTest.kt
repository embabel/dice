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
import com.embabel.dice.projection.memory.collector.EntityOverlapSignalScorer
import com.embabel.dice.projection.memory.collector.GroundingOverlapSignalScorer
import com.embabel.dice.projection.memory.collector.LexicalSignalScorer
import com.embabel.dice.projection.memory.collector.MultiSignalCollectorStrategy
import com.embabel.dice.projection.memory.collector.PolarityVetoSignalScorer
import com.embabel.dice.projection.memory.collector.ProvenanceOverlapSignalScorer
import com.embabel.dice.projection.memory.collector.VectorCandidatePairSource
import com.embabel.dice.projection.memory.collector.VectorSignalScorer
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CandidatePairSource
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorSignalScorer
import com.embabel.dice.spi.CollectorTraceStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeanProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `ApplicationContextRunner` wiring tests for [CollectorAutoConfiguration]: no full Spring Boot
 * app, no Neo4j — just the autoconfiguration plus a stub in-memory [PropositionRepository] bean
 * and `embabel.dice.collector.*` properties.
 */
class CollectorAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CollectorAutoConfiguration::class.java))
        .withUserConfiguration(StubPropositionRepositoryConfig::class.java)

    @Test
    fun `default properties wire a MultiSignalCollectorStrategy with every built-in signal in Order`() {
        runner.run { ctx ->
            assertThat(ctx).hasSingleBean(MultiSignalCollectorStrategy::class.java)

            val scorerTypes = ctx.getBeanProvider<CollectorSignalScorer>().orderedStream().toList()
                .map { it::class }
            assertThat(scorerTypes).containsExactly(
                VectorSignalScorer::class,
                LexicalSignalScorer::class,
                EntityOverlapSignalScorer::class,
                GroundingOverlapSignalScorer::class,
                ProvenanceOverlapSignalScorer::class,
                PolarityVetoSignalScorer::class,
            )
        }
    }

    @Test
    fun `matchThreshold and a signal weight from properties take effect`() {
        runner
            .withPropertyValues(
                "embabel.dice.collector.match-threshold=0.75",
                "embabel.dice.collector.signals.lexical.weight=0.9",
            )
            .run { ctx ->
                val props = ctx.getBean<CollectorProperties>()
                assertThat(props.matchThreshold).isEqualTo(0.75)
                assertThat(props.signals["lexical"]?.weight).isEqualTo(0.9)

                val scorer = ctx.getBean<LexicalSignalScorer>("lexicalSignalScorer")
                val pair = CandidatePair(anchor = TestFixtures.proposition("a"), member = TestFixtures.proposition("a"))
                val score = scorer.score(pair, TestFixtures.CONTEXT_ID)
                assertThat(score.weight).isEqualTo(0.9)
            }
    }

    @Test
    fun `disabling a signal by property removes it from the context and the strategy`() {
        runner
            .withPropertyValues("embabel.dice.collector.signals.lexical.enabled=false")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean("lexicalSignalScorer")
                val scorerTypes = ctx.getBeanProvider<CollectorSignalScorer>().orderedStream().toList()
                    .map { it::class }
                assertThat(scorerTypes).doesNotContain(LexicalSignalScorer::class)
            }
    }

    @Test
    fun `a custom CollectorSignalScorer bean is added alongside the built-ins`() {
        runner
            .withUserConfiguration(CustomScorerConfig::class.java)
            .run { ctx ->
                val scorers = ctx.getBeanProvider<CollectorSignalScorer>().orderedStream().toList()
                assertThat(scorers).anyMatch { it is CustomScorer }
            }
    }

    @Test
    fun `a custom MultiSignalCollectorStrategy bean wins over the default`() {
        runner
            .withUserConfiguration(CustomStrategyConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(MultiSignalCollectorStrategy::class.java)
                assertThat(ctx.getBean<MultiSignalCollectorStrategy>())
                    .isSameAs(CustomStrategyConfig.INSTANCE)
            }
    }

    @Test
    fun `master switch off means no MultiSignalCollectorStrategy bean`() {
        runner
            .withPropertyValues("embabel.dice.collector.enabled=false")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(MultiSignalCollectorStrategy::class.java)
                assertThat(ctx).doesNotHaveBean(CollectorAutoConfiguration::class.java)
            }
    }

    @Test
    fun `sweep delta and trace properties bind without error`() {
        runner
            .withPropertyValues(
                "embabel.dice.collector.sweep.delta=true",
                "embabel.dice.collector.trace.enabled=false",
                "embabel.dice.collector.trace.detail-retention-days=30",
            )
            .run { ctx ->
                val props = ctx.getBean<CollectorProperties>()
                assertThat(props.sweep.delta).isTrue()
                assertThat(props.trace.enabled).isFalse()
                assertThat(props.trace.detailRetentionDays).isEqualTo(30)
            }
    }

    /**
     * Runs [DiceStorageAutoConfiguration] and [CollectorAutoConfiguration] together, not the
     * isolated stub the other tests use, to prove `@AutoConfiguration(after = ...)` on
     * [CollectorAutoConfiguration] actually resolves `vectorCandidatePairSource`'s
     * `@ConditionalOnBean(PropositionRepository::class)`. Without that `after`, Spring Boot
     * doesn't guarantee `DiceStorageAutoConfiguration` registers its `PropositionRepository` bean
     * first, and the vector pair source can silently drop out.
     */
    @Test
    fun `combining DiceStorageAutoConfiguration and CollectorAutoConfiguration wires the vector pair source`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DiceStorageAutoConfiguration::class.java,
                    CollectorAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(StubAiConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(PropositionRepository::class.java)
                assertThat(ctx).hasSingleBean(VectorCandidatePairSource::class.java)
                assertThat(ctx).hasSingleBean(MultiSignalCollectorStrategy::class.java)

                val pairSources = ctx.getBeanProvider<CandidatePairSource>().orderedStream().toList()
                assertThat(pairSources).anyMatch { it is VectorCandidatePairSource }
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CollectorProperties::class)
    class StubPropositionRepositoryConfig {
        @Bean
        fun propositionRepository(): PropositionRepository =
            InMemoryPropositionRepository(mock<EmbeddingService>())
    }

    /**
     * Satisfies [DiceStorageAutoConfiguration]'s `@ConditionalOnBean(Ai::class)` gate on
     * `inMemoryPropositionRepository`, so the real in-memory `PropositionRepository` bean gets
     * registered and exercises the actual cross-auto-configuration ordering path.
     */
    @Configuration(proxyBeanMethods = false)
    class StubAiConfig {
        @Bean
        fun ai(): com.embabel.agent.api.common.Ai {
            val ai = mock<com.embabel.agent.api.common.Ai>()
            org.mockito.kotlin.whenever(ai.withDefaultEmbeddingService()).thenReturn(mock<EmbeddingService>())
            return ai
        }
    }

    class CustomScorer : CollectorSignalScorer {
        override fun score(pair: CandidatePair, contextId: com.embabel.agent.core.ContextId): CollectorSignalScore? = null
    }

    @Configuration(proxyBeanMethods = false)
    class CustomScorerConfig {
        @Bean
        fun customScorer(): CollectorSignalScorer = CustomScorer()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomStrategyConfig {
        @Bean
        fun multiSignalCollectorStrategy(): MultiSignalCollectorStrategy = INSTANCE

        companion object {
            val INSTANCE: MultiSignalCollectorStrategy = MultiSignalCollectorStrategy(
                pairSources = emptyList(),
                scorers = emptyList(),
                componentsFinder = com.embabel.dice.spi.InMemoryConnectedComponentsFinder(),
                traceStore = object : CollectorTraceStore {
                    override fun recordRunContext(runId: String, contextId: com.embabel.agent.core.ContextId) {}
                    override fun recordCandidateEdges(runId: String, edges: List<com.embabel.dice.spi.CollectorCandidateEdge>) {}
                    override fun recordComponents(runId: String, components: List<com.embabel.dice.spi.CollectorComponent>) {}
                    override fun recordDecision(runId: String, decision: com.embabel.dice.spi.CollectorDecision) {}
                    override fun deleteTracesForContext(contextId: com.embabel.agent.core.ContextId) {}
                },
            )
        }
    }
}
