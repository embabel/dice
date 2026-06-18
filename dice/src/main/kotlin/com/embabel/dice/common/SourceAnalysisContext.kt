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
package com.embabel.dice.common

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary

/**
 * Base context for analyzing sources.
 * Individual analyzers may extend this to require additional fields as needed.
 * @param schema the schema to use for analysis
 * @param entityResolver the entity resolver to use for entity disambiguation
 * @param knownEntities optional list of known entities to assist with disambiguation and prompt context
 * @param relations optional collection of additional relation types beyond those defined in the schema
 * @param promptVariables optional additional model data for analysis. Must be passed to any templated
 * LLM prompts used.
 */
data class SourceAnalysisContext @JvmOverloads constructor(
    val schema: DataDictionary,
    val entityResolver: EntityResolver,
    val contextId: ContextId,
    val knownEntities: List<KnownEntity> = emptyList(),
    val relations: Relations = Relations.empty(),
    val promptVariables: Map<String, Any> = emptyMap(),
) {

    companion object {
        /**
         * Start building a SourceAnalysisContext with the given context ID.
         * This is the entry point for the strongly-typed builder pattern.
         *
         * Usage from Java:
         * ```java
         * SourceAnalysisContext context = SourceAnalysisContext
         *     .withContextId("my-context")
         *     .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
         *     .withSchema(DataDictionary.fromClasses("myschema", Person.class))
         *     .withKnownEntities(knownEntities)  // optional
         *     .withTemplateModel(templateModel); // optional
         * ```
         *
         * @param contextId The context identifier for this analysis run
         * @return Builder step requiring an entity resolver
         */
        @JvmStatic
        fun withContextId(contextId: String): WithContextId = WithContextId(contextId)
    }

    /**
     * Returns the context ID as a String for Java interop.
     */
    fun getContextIdValue(): String = contextId.value

    /**
     * Returns a copy with the specified known entities added.
     */
    fun withKnownEntities(vararg knownEntities: KnownEntity): SourceAnalysisContext =
        copy(knownEntities = knownEntities.toList() + this.knownEntities)

    /**
     * Returns a copy with the specified relations collection.
     */
    fun withRelations(relations: Relations): SourceAnalysisContext =
        copy(relations = this.relations + relations)

    /**
     * Returns a copy with the specified relations added.
     */
    fun withRelations(vararg relations: Relation): SourceAnalysisContext =
        copy(relations = this.relations + Relations.of(*relations))


    /**
     * Returns a copy with the specified template model.
     */
    fun withPromptVariables(promptVariables: Map<String, Any>): SourceAnalysisContext =
        copy(promptVariables = promptVariables)

    /**
     * Builder step: has context ID, needs entity resolver.
     */
    class WithContextId internal constructor(private val contextId: String) {
        /**
         * Set the entity resolver for disambiguation.
         * @param entityResolver The resolver to use
         * @return Builder step requiring a schema
         */
        fun withEntityResolver(entityResolver: EntityResolver): WithEntityResolver =
            WithEntityResolver(contextId, entityResolver)
    }

    /**
     * Builder step: has context ID and entity resolver, needs schema.
     */
    class WithEntityResolver internal constructor(
        private val contextId: String,
        private val entityResolver: EntityResolver,
    ) {
        /**
         * Set the schema defining valid entity and relationship types.
         * @param schema The data dictionary schema
         * @return Complete SourceAnalysisContext
         */
        fun withSchema(schema: DataDictionary): SourceAnalysisContext =
            SourceAnalysisContext(
                schema = schema,
                entityResolver = entityResolver,
                contextId = ContextId(contextId),
            )
    }
}
