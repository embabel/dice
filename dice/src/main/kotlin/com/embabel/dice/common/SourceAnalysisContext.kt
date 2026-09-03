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
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.proposition.extraction.ExtractionPerspective

/**
 * Base context for analyzing sources.
 * Individual analyzers may extend this to require additional fields as needed.
 * @param schema the schema to use for analysis
 * @param entityResolver the entity resolver to use for entity disambiguation
 * @param knownEntities optional list of known entities to assist with disambiguation and prompt context
 * @param relations optional collection of additional relation types beyond those defined in the schema
 * @param promptVariables optional additional model data for analysis. Must be passed to any templated
 * LLM prompts used.
 * @param perspective optional per-call extraction perspective. When set it overrides the
 * extractor instance's default perspective for this analysis only (see [ExtractionPerspective]).
 * `null` (the default) means "use the extractor's own perspective" — zero behaviour change.
 * @param sourceLocator optional pointer to where this run's material lives. When set, the pipeline
 * stamps it onto every extracted proposition's provenance, so a caller that knows the real source
 * (a file, a URI, a connector record) gets richer grounding than the content-hash fallback.
 * @param sourceRevision optional revision of [sourceLocator] — the provider's own identifier for
 * the version of that source this run reads. Setting it requires a [sourceLocator] whose key it
 * matches, so a revision can never name a source the run is not actually reading.
 * @param mintNewEntities whether a mention the resolver could NOT match to an existing entity may
 * be persisted as a NEW entity node. Default FALSE: unresolved mentions stay unresolved (the
 * proposition is still persisted; its mention simply carries no resolvedId), so extraction never
 * creates phantom "mentioned in proposition" nodes for things the graph doesn't know — a term the
 * user merely asked about must not become an entity. Opt in per ingestion for sources whose
 * mentions deserve nodes.
 */
data class SourceAnalysisContext @JvmOverloads constructor(
    val schema: DataDictionary,
    val entityResolver: EntityResolver,
    val contextId: ContextId,
    val knownEntities: List<KnownEntity> = emptyList(),
    val relations: Relations = Relations.empty(),
    val promptVariables: Map<String, Any> = emptyMap(),
    val sourceLocator: SourceLocator? = null,
    val perspective: ExtractionPerspective? = null,
    val mintNewEntities: Boolean = false,
    /**
     * Base properties stamped onto every entity MINTED by this analysis (no-op
     * when [mintNewEntities] is false). The caller knows things the pipeline
     * does not — e.g. ownership/scoping properties a multi-tenant deployment
     * requires on new nodes for them to be visible to scoped queries. Stamped
     * values win over extractor-supplied properties of the same key.
     */
    val mintedEntityProperties: Map<String, Any> = emptyMap(),
    val sourceRevision: SourceRevisionRef? = null,
) {

    init {
        sourceRevision?.let { revision ->
            val locator = requireNotNull(sourceLocator) {
                "sourceLocator is required when sourceRevision is set"
            }
            require(revision.sourceKey == locator.key()) {
                "sourceRevision source key must match sourceLocator source key"
            }
        }
    }

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
     * Returns a copy carrying the given per-call extraction [perspective], which
     * overrides the extractor instance's default for this analysis only.
     */
    fun withPerspective(perspective: ExtractionPerspective): SourceAnalysisContext =
        copy(perspective = perspective)

    /**
     * Returns a copy that grounds this run's propositions in the given source.
     */
    fun withSourceLocator(sourceLocator: SourceLocator): SourceAnalysisContext =
        copy(sourceLocator = sourceLocator)

    /**
     * Returns a copy carrying a revision of this context's source. Throws if this context has no
     * locator, or if the revision names a different source key.
     */
    fun withSourceRevision(sourceRevision: SourceRevisionRef): SourceAnalysisContext =
        copy(sourceRevision = sourceRevision)

    /**
     * Returns a copy allowing (or forbidding) this analysis to persist NEW entities
     * for mentions the resolver could not match. See [mintNewEntities].
     */
    fun withMintNewEntities(mintNewEntities: Boolean): SourceAnalysisContext =
        copy(mintNewEntities = mintNewEntities)

    /**
     * Returns a copy whose minted entities carry the given base [properties]
     * (e.g. ownership/scoping fields). See [mintedEntityProperties].
     */
    fun withMintedEntityProperties(properties: Map<String, Any>): SourceAnalysisContext =
        copy(mintedEntityProperties = properties)

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
