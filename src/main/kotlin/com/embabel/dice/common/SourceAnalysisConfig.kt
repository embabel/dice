package com.embabel.dice.common

import com.embabel.agent.core.DataDictionary

/**
 * Analyze sources
 * @param schema the schema to use for analysis
 * @param entityResolver the entity resolver to use for disambiguation
 * @param directions custom Instructions for the LLM on how to analyze the source
 */
data class SourceAnalysisConfig(
    val schema: DataDictionary,
    val entityResolver: EntityResolver,
    val directions: String? = null,
)