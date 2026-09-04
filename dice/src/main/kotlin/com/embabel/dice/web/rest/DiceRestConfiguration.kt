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
package com.embabel.dice.web.rest

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.core.type.AnnotationMetadata

/**
 * Opt-in Spring configuration that activates all DICE REST controllers.
 *
 * Import this in your application config to expose the proposition extraction, memory, discovery,
 * and schema-governance endpoints. Nothing is component-scanned — the controllers only activate when
 * this class is imported AND the required beans (PropositionPipeline, PropositionStore,
 * GovernanceOperationsService, etc.) are present.
 *
 * ```java
 * @Configuration
 * @Import(DiceRestConfiguration.class)
 * public class MyAppConfiguration { }
 * ```
 */
@Configuration
@Import(
    PropositionPipelineController::class,
    MemoryController::class,
    DiscoveryController::class,
    GovernanceControllerImport::class,
)
class DiceRestConfiguration

/**
 * Brings [GovernanceController] in last, once every other bean definition is on the registry.
 *
 * The other three controllers are named directly in the `@Import` above and carry their own
 * `@ConditionalOnBean`. That condition is answered while the importing configuration class is being
 * read, so it can see the host's own beans and it cannot see anything an auto-configuration will
 * contribute later — Spring Boot registers auto-configuration bean definitions after every
 * configuration class the application imported.
 *
 * `GovernanceController` needs a `GovernanceOperationsService`, and that service is exactly such a
 * bean: `MetamodelAutoConfiguration` in `dice-storage-autoconfigure` builds it for a host that
 * declared a schema. Naming the controller in the `@Import` list would therefore leave it switched
 * off in every application that got its governance loop from the auto-configuration.
 *
 * A [DeferredImportSelector] is the fix, because Spring processes deferred imports at the end of the
 * configuration-parsing round and this one declares the lowest precedence, so it runs behind Spring
 * Boot's own auto-configuration selector. By the time the controller's condition is asked, the
 * governance service is either on the registry or it never will be, and the answer is right in both
 * directions: the controller appears for a host whose governance loop is wired, and stays away —
 * with a clean context and no `/api/v1/metamodel` route — for a host that declared no schema, killed
 * the loop with `embabel.dice.metamodel.enabled=false`, runs `drift.mode=off`, or uses the in-memory
 * backend that has no drift log to read.
 */
internal class GovernanceControllerImport : DeferredImportSelector, Ordered {

    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        arrayOf(GovernanceController::class.java.name)

    /** Behind Spring Boot's auto-configuration selector, which sits one step above this. */
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}
