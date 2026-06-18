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
package com.embabel.dice.projection.prolog

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.projection.graph.GraphProjector
import org.jetbrains.annotations.ApiStatus
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.proposition.*
import org.slf4j.LoggerFactory

/**
 * Projects propositions to Prolog facts for logical inference.
 *
 * ## Propositions as Source of Truth
 *
 * In the DICE architecture, [Proposition]s are the canonical representation of knowledge.
 * All other representations (graph relationships, Prolog facts, vector embeddings) are
 * **projections** derived from propositions. This ensures:
 * - Single source of truth with full provenance
 * - Multiple views optimized for different query patterns
 * - Consistent grounding back to original sources
 *
 * ## Projection Architecture
 *
 * Projectors transform propositions into specialized representations:
 * ```
 * Propositions (source of truth)
 *      │
 *      ├──► GraphProjector ──► Neo4j relationships
 *      │
 *      ├──► PrologProjector ──► Prolog facts (logical inference)
 *      │
 *      └──► [Your Projector] ──► Custom representation
 * ```
 *
 * ## Creating Custom Projectors
 *
 * To create a new projector, implement [Projector]<YourType>:
 *
 * ```kotlin
 * data class MyProjection(
 *     val data: String,
 *     override val sourcePropositionIds: List<String>,
 * ) : Projected
 *
 * class MyProjector : Projector<MyProjection> {
 *     override fun project(
 *         proposition: Proposition,
 *         schema: DataDictionary,
 *     ): ProjectionResult<MyProjection> {
 *         // Transform proposition to your representation
 *         val projection = MyProjection(
 *             data = proposition.text,
 *             sourcePropositionIds = listOf(proposition.id),
 *         )
 *         return ProjectionSuccess(proposition, projection)
 *     }
 * }
 * ```
 *
 * Then use with the pipeline results:
 * ```kotlin
 * val pipeline = PropositionPipeline.withExtractor(extractor)
 * val result = pipeline.process(chunks, context)
 * val facts = myProjector.projectAll(result.allPropositions, schema)
 * ```
 *
 * ## Recommended Flow for Prolog
 *
 * Since relationship classification requires LLM inference, this projector composes
 * with [GraphProjector] for the classification step:
 *
 * 1. Use [GraphProjector] to classify propositions into [ProjectedRelationship]s
 * 2. Use [projectAll] to convert relationships to Prolog facts
 *
 * This avoids duplicating LLM classification logic across projectors.
 */
@ApiStatus.Experimental
interface PrologProjector : Projector<PrologFact> {

    /**
     * Project a proposition directly to Prolog facts.
     * Delegates relationship classification to an internal [GraphProjector].
     *
     * @param proposition The proposition to project
     * @param schema The data dictionary (used for relationship type inference)
     * @return The projection result
     */
    override fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<PrologFact>

    /**
     * Project a classified relationship to a Prolog fact.
     * Use this when you already have classified relationships from graph projection.
     *
     * @param relationship The relationship to project
     * @return The Prolog fact
     */
    fun projectRelationship(relationship: ProjectedRelationship): PrologFact

    /**
     * Project multiple relationships to a complete result with metadata.
     *
     * @param relationships The relationships to project
     * @return Complete projection result with facts, confidence, and grounding
     */
    fun projectAll(relationships: List<ProjectedRelationship>): PrologProjectionResult
}

/**
 * Default implementation of PrologProjector.
 *
 * For direct proposition projection, delegates relationship classification to a [GraphProjector].
 * The recommended usage is to first project propositions using a [GraphProjector],
 * then use [projectAll] to convert the resulting relationships to Prolog facts.
 *
 * @param graphProjector Optional projector for classifying propositions into relationships.
 *                       Required if using [project] for direct proposition-to-Prolog projection.
 * @param prologSchema The Prolog schema defining predicate mappings
 * @param includeConfidence Whether to generate confidence facts in [projectAll]
 * @param includeGrounding Whether to generate grounding/provenance facts in [projectAll]
 */
class DefaultPrologProjector(
    private val graphProjector: GraphProjector? = null,
    private val prologSchema: PrologSchema = PrologSchema.withDefaults(),
    private val includeConfidence: Boolean = true,
    private val includeGrounding: Boolean = true,
) : PrologProjector {

    private val logger = LoggerFactory.getLogger(DefaultPrologProjector::class.java)

    /**
     * Project a proposition to a Prolog fact.
     * Delegates relationship classification to the configured [GraphProjector].
     *
     * @throws IllegalStateException if no [GraphProjector] is configured
     */
    override fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<PrologFact> {
        val projector = graphProjector
            ?: return ProjectionFailed(
                proposition,
                "No GraphProjector configured. Use projectAll(relationships) with pre-classified relationships, " +
                        "or configure a GraphProjector for direct proposition projection."
            )

        // Delegate classification to the GraphProjector
        return when (val graphResult = projector.project(proposition, schema)) {
            is ProjectionSuccess -> {
                val fact = projectRelationship(graphResult.projected)
                ProjectionSuccess(proposition, fact)
            }

            is ProjectionFailed -> ProjectionFailed(proposition, graphResult.reason)
            is com.embabel.dice.proposition.ProjectionSkipped ->
                ProjectionFailed(proposition, graphResult.reason)
        }
    }

    /**
     * Project a classified relationship to a Prolog fact.
     */
    override fun projectRelationship(relationship: ProjectedRelationship): PrologFact {
        val predicate = prologSchema.getPredicate(relationship.type)

        val fact = PrologFact(
            predicate = predicate,
            args = listOf(relationship.sourceId, relationship.targetId),
            confidence = relationship.confidence,
            sourcePropositionIds = relationship.sourcePropositionIds,
        )

        logger.debug("Projected {} to fact: {}", relationship.type, fact.toProlog())
        return fact
    }

    /**
     * Project multiple relationships to a complete Prolog result with metadata.
     */
    override fun projectAll(relationships: List<ProjectedRelationship>): PrologProjectionResult {
        val facts = relationships.map { projectRelationship(it) }

        val confidenceFacts = if (includeConfidence) {
            facts.map { ConfidenceFact(it) }
        } else {
            emptyList()
        }

        val groundingFacts = if (includeGrounding) {
            facts.flatMap { fact ->
                fact.sourcePropositionIds.map { propId ->
                    GroundingFact(fact, propId)
                }
            }
        } else {
            emptyList()
        }

        logger.info(
            "Projected {} relationships to {} Prolog facts",
            relationships.size,
            facts.size
        )

        return PrologProjectionResult(
            facts = facts,
            confidenceFacts = confidenceFacts,
            groundingFacts = groundingFacts,
        )
    }
}
