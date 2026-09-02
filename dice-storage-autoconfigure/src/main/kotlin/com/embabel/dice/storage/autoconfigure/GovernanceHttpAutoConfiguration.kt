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
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.web.rest.GovernanceController
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * Puts the governance operator surface on HTTP.
 *
 * It registers one bean, [GovernanceController], and it registers it only for a host that asked for
 * the governance loop. Four things have to hold at once: the application supplies a
 * [DeclaredSchemaSource] bean, `embabel.dice.metamodel.enabled` is absent or `true`, the loop wired
 * a [GovernanceOperationsService], and this is a servlet web application with Spring MVC on the
 * classpath. Miss any one of them and the routes under `/api/v1/metamodel` do not exist. Under
 * `drift.mode=off` there is no check to run, so there is no service and no controller either.
 *
 * ## The opt-in is stated here as well as on the loop
 *
 * A declared schema and the kill switch are the gate on [MetamodelAutoConfiguration], which is where
 * the service comes from, so a context that reaches this class has satisfied them once already. They
 * are repeated because this is the part of governance a host meets from outside — six public routes,
 * one of them a write — and leaning on the service alone leaves those routes one hand-wired
 * `GovernanceOperationsService` bean away from appearing in an application that declared no schema.
 * The condition that opens a public surface belongs on the class that opens it.
 *
 * ## How the ordering is guaranteed
 *
 * `@ConditionalOnBean` sees only the bean definitions registered by the time it runs, so an
 * auto-configuration asking about a bean that another auto-configuration contributes has to run
 * afterwards. `@AutoConfiguration(after = [MetamodelAutoConfiguration::class])` is what arranges
 * that: Spring Boot sorts auto-configuration classes on those declarations before it evaluates a
 * single condition, so [MetamodelAutoConfiguration] has already had its say about
 * [GovernanceOperationsService] when this class is asked. The [DeclaredSchemaSource] half needs no
 * ordering, because it is the host's own bean and Spring Boot registers every application bean
 * definition before it processes any auto-configuration at all.
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
@ConditionalOnBean(value = [DeclaredSchemaSource::class, GovernanceOperationsService::class])
@ConditionalOnProperty(
    prefix = "embabel.dice.metamodel",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
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
