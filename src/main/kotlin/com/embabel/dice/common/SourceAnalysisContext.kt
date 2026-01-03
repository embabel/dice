package com.embabel.dice.common

import com.embabel.agent.core.DataDictionary

data class KnownEntity(
    val name: String,
    val type: String,
    val description: String,
    val id: String,
)

/**
 * Base context for analyzing sources.
 * Individual analyzers may extend this to require additional fields as needed.
 * @param schema the schema to use for analysis
 * @param entityResolver the entity resolver to use for entity disambiguation
 * @param knownEntities optional list of known entities to assist with disambiguation
 * @param templateModel optional additional model data for analysis. Must be passed to any templated
 * LLM prompts used.
 */
data class SourceAnalysisContext(
    val schema: DataDictionary,
    val entityResolver: EntityResolver,
    val knownEntities: List<KnownEntity> = emptyList(),
    val templateModel: Map<String, Any> = emptyMap(),
)