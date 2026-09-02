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

import com.embabel.dice.governance.GovernanceOperationsService
import com.embabel.dice.web.rest.GovernanceController
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * Puts the governance operator surface on HTTP.
 *
 * It registers one bean, [GovernanceController], and only when there is something for it to serve:
 * the governance loop wired a [GovernanceOperationsService], the application is a servlet web
 * application, and Spring MVC is on the classpath. Since the service itself carries the loop's own
 * conditions, a host with no declared schema, `embabel.dice.metamodel.enabled=false`, or
 * `drift.mode=off` gets no controller either.
 *
 * ## Turning the HTTP surface off
 *
 * The operator surface is worth having through an agent's tools or a host's own code without opening
 * a public endpoint. This lives in its own auto-configuration so that choice is one line, and the
 * service and the agent tools stay wired:
 *
 * ```yaml
 * spring:
 *   autoconfigure:
 *     exclude: com.embabel.dice.storage.autoconfigure.GovernanceHttpAutoConfiguration
 * ```
 *
 * A host that wants the endpoints on a different path, behind extra authorization, or shaped
 * differently supplies its own [GovernanceController] bean; `@ConditionalOnMissingBean` backs this
 * one off.
 *
 * ## What is on the endpoints
 *
 * The routes read and run; one of them writes. `POST .../quarantine/{propositionId}/release` lifts a
 * quarantine hold, so it changes stored data and should sit behind whatever authorization the host
 * puts on its administrative routes. The reads and the drift check move no proposition, though a
 * check does write a drift report and stamp the declared version.
 */
@AutoConfiguration(after = [MetamodelAutoConfiguration::class])
@ConditionalOnClass(name = [REST_CONTROLLER])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(GovernanceOperationsService::class)
class GovernanceHttpAutoConfiguration {

    private val logger = LoggerFactory.getLogger(GovernanceHttpAutoConfiguration::class.java)

    @Bean
    @ConditionalOnMissingBean(GovernanceController::class)
    fun governanceController(operations: GovernanceOperationsService): GovernanceController {
        logger.info("Metamodel governance REST surface wired under /api/v1/metamodel")
        return GovernanceController(operations)
    }
}

/**
 * Spring MVC's marker, named as text. Spring MVC is a `provided` dependency, so a consumer can run
 * DICE without it; naming the class by text keeps this configuration loadable on such a classpath,
 * because `@ConditionalOnClass` is read from the bytecode and the annotated class is never loaded
 * when the answer is no.
 */
private const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
