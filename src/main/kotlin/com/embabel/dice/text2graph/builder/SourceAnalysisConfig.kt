package com.embabel.dice.text2graph.builder

import com.embabel.agent.core.DataDictionary

/**
 * Analyze sources
 * @param schema the schema to use for analysis
 * @param directions custom Instructions for the LLM on how to analyze the source
 */
data class SourceAnalysisConfig(
    val schema: DataDictionary,
    val directions: String? = null,
)