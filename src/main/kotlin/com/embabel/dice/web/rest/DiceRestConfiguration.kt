package com.embabel.dice.web.rest

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Configuration to enable DICE REST API controllers.
 * Import this configuration in your application to expose the DICE REST endpoints.
 *
 * Example:
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
)
class DiceRestConfiguration
