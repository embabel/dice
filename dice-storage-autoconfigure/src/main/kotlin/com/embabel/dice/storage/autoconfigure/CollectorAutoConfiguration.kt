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

import com.embabel.dice.projection.memory.collector.EntityOverlapSignalScorer
import com.embabel.dice.projection.memory.collector.GroundingOverlapSignalScorer
import com.embabel.dice.projection.memory.collector.LexicalSignalScorer
import com.embabel.dice.projection.memory.collector.MultiSignalCollectorStrategy
import com.embabel.dice.projection.memory.collector.PolarityVetoSignalScorer
import com.embabel.dice.projection.memory.collector.ProvenanceOverlapSignalScorer
import com.embabel.dice.projection.memory.collector.VectorCandidatePairSource
import com.embabel.dice.projection.memory.collector.VectorSignalScorer
import com.embabel.dice.projection.memory.collector.CollectorSurvivorPolicy
import com.embabel.dice.projection.memory.collector.defaultCollectorSurvivorPolicy
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.CandidatePairSource
import com.embabel.dice.spi.CollectorSignalScorer
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.ConnectedComponentsFinder
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import com.embabel.dice.storage.CollectorTraceSchema
import com.embabel.dice.storage.DrivineCollectorTraceStore
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaCatalog
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order

/**
 * Auto-configures the multi-signal duplicate collector: the candidate-pair sources, signal
 * scorers, and everything else [MultiSignalCollectorStrategy] needs, all driven from
 * [CollectorProperties].
 *
 * `embabel.dice.collector.enabled=false` switches the whole thing off — no beans, no strategy.
 * Every collaborator is `@ConditionalOnMissingBean`, so an application's own bean always wins;
 * every built-in scorer is individually `@ConditionalOnProperty` under
 * `embabel.dice.collector.signals.<name>.enabled`, so ops can drop one signal from the blend
 * without touching code. `@Order` on the scorer beans fixes the injected `List<CollectorSignalScorer>`
 * order, which is also the order signals show up in the trace.
 *
 * The graph trace store is declared before the in-memory one so the flip resolves by
 * registration order, matching [DiceStorageAutoConfiguration]'s pattern for the proposition store.
 */
@AutoConfiguration(after = [DiceStorageAutoConfiguration::class])
@EnableConfigurationProperties(CollectorProperties::class)
@ConditionalOnProperty(prefix = "embabel.dice.collector", name = ["enabled"], havingValue = "true", matchIfMissing = true)
open class CollectorAutoConfiguration(
    private val props: CollectorProperties,
) {

    private val logger = LoggerFactory.getLogger(CollectorAutoConfiguration::class.java)

    // ---- Shared collaborators ----

    @Bean
    @ConditionalOnMissingBean(ConnectedComponentsFinder::class)
    open fun connectedComponentsFinder(): ConnectedComponentsFinder = InMemoryConnectedComponentsFinder()

    @Bean
    @ConditionalOnMissingBean(CollectorSurvivorPolicy::class)
    open fun collectorSurvivorPolicy(): CollectorSurvivorPolicy = defaultCollectorSurvivorPolicy

    // ---- Trace store: graph backend (embabel.dice.store.type=graph) before in-memory default ----

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(prefix = "embabel.dice.collector.trace", name = ["enabled"], matchIfMissing = true)
    @ConditionalOnMissingBean(CollectorTraceStore::class)
    open fun drivineCollectorTraceStore(persistenceManager: PersistenceManager): CollectorTraceStore {
        logger.info("Wiring graph collector trace store (Drivine/Neo4j)")
        return DrivineCollectorTraceStore(persistenceManager)
    }

    @Bean
    @ConditionalOnMissingBean(CollectorTraceStore::class)
    open fun inMemoryCollectorTraceStore(): CollectorTraceStore {
        logger.info("Wiring in-memory collector trace store")
        return InMemoryCollectorTraceStore()
    }

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    open fun collectorTraceSchema(): SchemaCatalog = SchemaCatalog.of(*CollectorTraceSchema.specs().toTypedArray())

    // ---- Built-in pair source ----

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnProperty(
        prefix = "embabel.dice.collector.signals.vector",
        name = ["enabled"],
        matchIfMissing = true,
    )
    @Order(1)
    open fun vectorCandidatePairSource(repository: PropositionRepository): CandidatePairSource {
        val vectorProps = props.signals["vector"]
        return VectorCandidatePairSource(
            repository = repository,
            similarityThreshold = vectorProps?.similarityThreshold ?: 0.7,
            topK = vectorProps?.topK ?: 10,
        )
    }

    // ---- Built-in scorers (List<CollectorSignalScorer> injection order == @Order below) ----

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.collector.signals.vector", name = ["enabled"], matchIfMissing = true)
    @Order(1)
    open fun vectorSignalScorer(): CollectorSignalScorer =
        VectorSignalScorer(weight = props.signals["vector"]?.weight ?: 1.0)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.collector.signals.lexical", name = ["enabled"], matchIfMissing = true)
    @Order(2)
    open fun lexicalSignalScorer(): CollectorSignalScorer =
        LexicalSignalScorer(weight = props.signals["lexical"]?.weight ?: 0.5)

    @Bean
    @ConditionalOnProperty(
        prefix = "embabel.dice.collector.signals.entity-overlap",
        name = ["enabled"],
        matchIfMissing = true,
    )
    @Order(3)
    open fun entityOverlapSignalScorer(): CollectorSignalScorer =
        EntityOverlapSignalScorer(weight = props.signals["entity-overlap"]?.weight ?: 1.0)

    @Bean
    @ConditionalOnProperty(
        prefix = "embabel.dice.collector.signals.grounding-overlap",
        name = ["enabled"],
        matchIfMissing = true,
    )
    @Order(4)
    open fun groundingOverlapSignalScorer(): CollectorSignalScorer =
        GroundingOverlapSignalScorer(weight = props.signals["grounding-overlap"]?.weight ?: 0.5)

    @Bean
    @ConditionalOnProperty(
        prefix = "embabel.dice.collector.signals.provenance-overlap",
        name = ["enabled"],
        matchIfMissing = true,
    )
    @Order(5)
    open fun provenanceOverlapSignalScorer(): CollectorSignalScorer =
        ProvenanceOverlapSignalScorer(weight = props.signals["provenance-overlap"]?.weight ?: 0.5)

    @Bean
    @ConditionalOnProperty(
        prefix = "embabel.dice.collector.signals.polarity-veto",
        name = ["enabled"],
        matchIfMissing = true,
    )
    @Order(6)
    open fun polarityVetoSignalScorer(): CollectorSignalScorer = PolarityVetoSignalScorer()

    // ---- The strategy itself ----

    @Bean
    @ConditionalOnMissingBean(MultiSignalCollectorStrategy::class)
    open fun multiSignalCollectorStrategy(
        pairSources: List<CandidatePairSource>,
        scorers: List<CollectorSignalScorer>,
        componentsFinder: ConnectedComponentsFinder,
        traceStore: CollectorTraceStore,
        survivorPolicy: CollectorSurvivorPolicy,
    ): MultiSignalCollectorStrategy {
        logger.info(
            "Wiring multi-signal collector: matchThreshold={}, signals=[{}]",
            props.matchThreshold,
            scorers.joinToString(", ") { it::class.simpleName ?: "?" },
        )
        return MultiSignalCollectorStrategy(
            pairSources = pairSources,
            scorers = scorers,
            componentsFinder = componentsFinder,
            traceStore = traceStore,
            survivorPolicy = survivorPolicy,
            matchThreshold = props.matchThreshold,
        )
    }
}
