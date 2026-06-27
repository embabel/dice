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
package com.embabel.dice.metamodel

import com.embabel.dice.metamodel.support.JaversMetamodelDiffer
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Default Spring wiring for the metamodel SPI.
 *
 * Import this from a Spring app to get a ready [MetamodelDiffer] and [DriftQuarantinePolicy] without
 * constructing them by hand. Both beans are conditional on nothing else already supplying them, so a
 * consumer can drop in its own diffing or quarantine strategy and this configuration steps aside.
 * Both default implementations are stateless, so a single shared instance is all an app needs.
 */
@Configuration
class MetamodelConfiguration {

    private val logger = LoggerFactory.getLogger(MetamodelConfiguration::class.java)

    /** A JaVers-backed structural differ, unless the app already defines one. */
    @Bean
    @ConditionalOnMissingBean(MetamodelDiffer::class)
    fun metamodelDiffer(): MetamodelDiffer {
        logger.debug("Registering default MetamodelDiffer: JaversMetamodelDiffer")
        return JaversMetamodelDiffer()
    }

    /** The mention-type drift quarantine policy, unless the app already defines one. */
    @Bean
    @ConditionalOnMissingBean(DriftQuarantinePolicy::class)
    fun driftQuarantinePolicy(): DriftQuarantinePolicy {
        logger.debug("Registering default DriftQuarantinePolicy: MentionTypeDriftQuarantinePolicy")
        return MentionTypeDriftQuarantinePolicy()
    }
}
