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

import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.MetamodelStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.storage.DrivineMetamodelStore
import org.drivine.manager.PersistenceManager
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configures the metamodel drift-check feature: opt-in (`embabel.dice.metamodel.enabled=true`,
 * off by default) beans for observing a live graph's schema, diffing it against a declared one, and
 * running the check.
 *
 * A consumer must supply its own [DeclaredSchemaSource] bean -- there's no sensible default declared
 * schema, so [driftCheckRunner] only activates once one is present. Everything else has a working
 * default, each overridable by defining a bean of that type first:
 * - [ObservedSchemaSource] defaults to [DrivineObservedSchemaSource], reading the same graph this
 *   app is already connected to.
 * - [MetamodelStore] defaults to [DrivineMetamodelStore].
 * - [DeclaredObservedDiffer] defaults to [StructuralMetamodelDiffer].
 * - [DriftQuarantinePolicy] defaults to [MentionTypeDriftQuarantinePolicy].
 *
 * No scheduler is wired here -- this only wires the *capability* to run a check. A consumer decides
 * when to call [DriftCheckRunner.run] (cron, an admin endpoint, a startup hook, ...); the `me` app,
 * for example, has its own scheduling harness for this.
 */
@AutoConfiguration
@EnableConfigurationProperties(MetamodelProperties::class)
@ConditionalOnProperty(prefix = "embabel.dice.metamodel", name = ["enabled"], havingValue = "true")
open class MetamodelAutoConfiguration {

    private val logger = LoggerFactory.getLogger(MetamodelAutoConfiguration::class.java)

    @Bean
    @ConditionalOnMissingBean(ObservedSchemaSource::class)
    open fun observedSchemaSource(persistenceManager: PersistenceManager): ObservedSchemaSource {
        logger.debug("Registering default ObservedSchemaSource: DrivineObservedSchemaSource")
        return DrivineObservedSchemaSource(persistenceManager)
    }

    @Bean
    @ConditionalOnMissingBean(MetamodelStore::class)
    open fun metamodelStore(persistenceManager: PersistenceManager): MetamodelStore {
        logger.debug("Registering default MetamodelStore: DrivineMetamodelStore")
        return DrivineMetamodelStore(persistenceManager)
    }

    @Bean
    @ConditionalOnMissingBean(DeclaredObservedDiffer::class)
    open fun declaredObservedDiffer(): DeclaredObservedDiffer {
        logger.debug("Registering default DeclaredObservedDiffer: StructuralMetamodelDiffer")
        return StructuralMetamodelDiffer()
    }

    @Bean
    @ConditionalOnMissingBean(DriftQuarantinePolicy::class)
    open fun driftQuarantinePolicy(): DriftQuarantinePolicy {
        logger.debug("Registering default DriftQuarantinePolicy: MentionTypeDriftQuarantinePolicy")
        return MentionTypeDriftQuarantinePolicy()
    }

    /**
     * Only wired once a [DeclaredSchemaSource] bean exists -- without one there is nothing to diff
     * the observed schema against, and this auto-configuration has no default to fall back to.
     */
    @Bean
    @ConditionalOnBean(DeclaredSchemaSource::class)
    @ConditionalOnMissingBean(DriftCheckRunner::class)
    open fun driftCheckRunner(
        observedSchemaSource: ObservedSchemaSource,
        declaredSchemaSource: DeclaredSchemaSource,
        declaredObservedDiffer: DeclaredObservedDiffer,
        metamodelStore: MetamodelStore,
        driftQuarantinePolicy: DriftQuarantinePolicy,
        propositionRepository: PropositionRepository,
    ): DriftCheckRunner {
        logger.info("Metamodel drift-check runner wired (no scheduler; a consumer calls run() itself)")
        return DefaultDriftCheckRunner(
            observedSchemaSource = observedSchemaSource,
            declaredSchemaSource = declaredSchemaSource,
            differ = declaredObservedDiffer,
            store = metamodelStore,
            quarantinePolicy = driftQuarantinePolicy,
            propositionRepository = propositionRepository,
        )
    }
}
