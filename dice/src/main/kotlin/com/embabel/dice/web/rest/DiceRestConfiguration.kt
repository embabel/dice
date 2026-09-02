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
 * and schema-governance endpoints. Nothing is component-scanned: the controllers only activate when
 * this class is imported AND the beans they need (PropositionPipeline, PropositionStore,
 * GovernanceOperationsService, etc.) are present, wherever those beans come from.
 *
 * ```java
 * @Configuration
 * @Import(DiceRestConfiguration.class)
 * public class MyAppConfiguration { }
 * ```
 */
@Configuration
@Import(
    MemoryController::class,
    ConditionalControllerImport::class,
)
class DiceRestConfiguration

/**
 * Brings the conditional controllers in last, once every other bean definition is on the registry.
 *
 * [MemoryController] is named directly in the `@Import` above because it carries no condition. The
 * other three gate on `@ConditionalOnBean`: [DiscoveryController] on the proposition store, the
 * projection record store and the collector runner; [PropositionPipelineController] on the
 * pipeline; [GovernanceController] on a `GovernanceOperationsService`. A condition on a plainly
 * imported class is answered while the importing configuration class is being read, so it can only
 * see beans already on the registry at that moment: nothing an auto-configuration will contribute
 * later, because Spring Boot registers auto-configuration bean definitions after every configuration
 * class the application imported, and nothing from a host configuration Spring happens to process
 * afterwards. A host on `dice-storage-autoconfigure` for its stores therefore got no discovery
 * routes at all, silently, and a host whose governance loop came from `MetamodelAutoConfiguration`
 * would have lost its governance routes the same way.
 *
 * A [DeferredImportSelector] is the fix, because Spring processes deferred imports at the end of
 * the configuration-parsing round and this one declares the lowest precedence, so it runs behind
 * Spring Boot's own auto-configuration selector. By the time each controller's condition is asked,
 * its beans are either on the registry or they never will be, and the answer is right in both
 * directions: the controller appears wherever its collaborators exist, whoever contributed them,
 * and stays away, with a clean context and no routes, where they are absent. For the governance
 * controller that means no `/api/v1/metamodel` route for a host that declared no schema, killed the
 * loop with `embabel.dice.metamodel.enabled=false`, runs `drift.mode=off`, or uses the in-memory
 * backend that has no drift log to read.
 */
internal class ConditionalControllerImport : DeferredImportSelector, Ordered {

    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        arrayOf(
            PropositionPipelineController::class.java.name,
            DiscoveryController::class.java.name,
            GovernanceController::class.java.name,
        )

    /** Behind Spring Boot's auto-configuration selector, which sits one step above this. */
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}
