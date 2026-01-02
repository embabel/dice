package com.embabel.dice.text2graph.support

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.text2graph.*
import com.embabel.dice.text2graph.resolver.AcceptSuggestionRelationshipResolver
import org.slf4j.LoggerFactory

/**
 * Strategy-based builder for knowledge graphs.
 * Responsible for analyzing source chunks and building a knowledge graph delta
 * and then calling the knowledge graph updater to apply the delta.
 */
class MultiPassKnowledgeGraphBuilder(
    private val sourceAnalyzer: SourceAnalyzer,
    private val entityResolver: com.embabel.dice.common.EntityResolver,
    private val relationshipResolver: RelationshipResolver = AcceptSuggestionRelationshipResolver,
    private val entityMergePolicy: EntityMergePolicy = UseNewEntityMergePolicy,
    private val relationshipMergePolicy: RelationshipMergePolicy = AcceptRecommendedRelationshipMergePolicy,
    private val diceEventListener: DiceEventListener = DiceEventListener.DEV_NULL,
) : KnowledgeGraphBuilder {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun computeDelta(
        chunks: Iterable<Chunk>,
        context: SourceAnalysisContext,
    ): KnowledgeGraphDelta {
        val entityMerges = mutableListOf<Merge<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>>()
        val relationshipMerges = mutableListOf<Merge<SuggestedRelationshipResolution, RelationshipInstance>>()

        for (chunk in chunks) {
            val suggestedEntities = sourceAnalyzer.suggestEntities(chunk, context)
            logger.info(
                "Suggested entities:\n{}",
                suggestedEntities.suggestedEntities.joinToString("\t\n") { it.suggestedEntity.infoString(verbose = true) })
            val entityResolutions = entityResolver.resolve(suggestedEntities, context.schema)
            logger.info("Entity resolution: {}", entityResolutions.infoString(true))
            val suggestedRelationships = sourceAnalyzer.suggestRelationships(chunk, entityResolutions, context)
            logger.info("Suggested relationships: {}", suggestedRelationships)
            val relationshipResolutions = relationshipResolver.resolveRelationships(
                entityResolutions,
                suggestedRelationships,
                context.schema,
            )
            logger.info("Relationships resolution: {}", relationshipResolutions.infoString(verbose = true))
            val newEntityMerges = entityMergePolicy.determineEntities(
                entityResolutions,
                context.schema,
            ).merges
            newEntityMerges
                .map { it.resolution }
                .filterIsInstance<com.embabel.dice.common.NewEntity>()
                .forEach { diceEventListener.onEvent(it) }
            entityMerges += newEntityMerges
            val newRelationshipMerges = relationshipMergePolicy.mergeRelationships(
                relationshipResolutions,
                context.schema
            ).merges
            relationshipMerges += newRelationshipMerges
        }
        return KnowledgeGraphDelta(
            chunkIds = chunks.map { it.id }.toSet(),
            entityMerges = Merges(entityMerges.toList()),
            relationshipMerges = Merges(relationshipMerges),
        )
    }
}