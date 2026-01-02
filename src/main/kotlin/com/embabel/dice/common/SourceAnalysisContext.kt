package com.embabel.dice.common

import com.embabel.agent.core.DataDictionary

/**
 * Base context for analyzing sources.
 * Individual analyzers may extend this to require additional fields as needed.
 * @param schema the schema to use for analysis
 * @param entityResolver the entity resolver to use for entity disambiguation
 */
open class SourceAnalysisContext(
    val schema: DataDictionary,
    val entityResolver: EntityResolver,
)