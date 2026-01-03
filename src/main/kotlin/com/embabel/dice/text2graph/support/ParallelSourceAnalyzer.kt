package com.embabel.dice.text2graph.support

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.*
import com.embabel.dice.text2graph.SourceAnalyzer
import com.embabel.dice.text2graph.SuggestedRelationships
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs multiple source analyzers in parallel on the same chunk and merges results.
 * Entities suggested by multiple analyzers are weighted higher (agreement scoring).
 */
class ParallelSourceAnalyzer(
    private val analyzers: List<SourceAnalyzer>,
    private val executor: ExecutorService = Executors.newFixedThreadPool(analyzers.size),
    private val config: Config = Config(),
) : SourceAnalyzer {

    data class Config(
        /**
         * Minimum number of analyzers that must agree on an entity for it to be included.
         * Default is 1 (include all entities).
         */
        val minAgreement: Int = 1,
    )

    private val logger = LoggerFactory.getLogger(ParallelSourceAnalyzer::class.java)

    init {
        require(analyzers.isNotEmpty()) { "At least one analyzer is required" }
    }

    override fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedEntities {
        val futures = analyzers.map { analyzer ->
            CompletableFuture.supplyAsync({
                runCatching {
                    analyzer.suggestEntities(chunk, context)
                }.getOrElse { e ->
                    logger.warn("Analyzer failed for chunk ${chunk.id}: ${e.message}", e)
                    SuggestedEntities(emptyList())
                }
            }, executor)
        }

        val allResults = futures.map { it.join() }
        val allEntities = allResults.flatMap { it.suggestedEntities }

        // Group by normalized name to find agreement
        val entityGroups = allEntities.groupBy { normalizeForComparison(it.name) }

        // Select entities based on agreement, preferring those with more votes
        val selectedEntities = entityGroups
            .filter { (_, entities) -> entities.size >= config.minAgreement }
            .map { (_, entities) ->
                // Pick the entity with the most specific labels (most labels = most specific)
                // or merge information from agreeing analyzers
                selectBestEntity(entities)
            }

        logger.info(
            "Parallel analysis complete: {} analyzers, {} total suggestions, {} after agreement filtering",
            analyzers.size,
            allEntities.size,
            selectedEntities.size
        )

        return SuggestedEntities(
            suggestedEntities = selectedEntities,
        )
    }

    override fun suggestRelationships(
        chunk: Chunk,
        suggestedEntitiesResolution: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisContext,
    ): SuggestedRelationships {
        val futures = analyzers.map { analyzer ->
            CompletableFuture.supplyAsync({
                runCatching {
                    analyzer.suggestRelationships(chunk, suggestedEntitiesResolution, context)
                }.getOrElse { e ->
                    logger.warn("Analyzer failed for relationships in chunk ${chunk.id}: ${e.message}", e)
                    SuggestedRelationships(suggestedEntitiesResolution, emptyList())
                }
            }, executor)
        }

        val allResults = futures.map { it.join() }
        val allRelationships = allResults.flatMap { it.suggestedRelationships }

        // Group by relationship signature to find agreement
        val relationshipGroups = allRelationships.groupBy {
            "${it.sourceId}-[${it.type}]->${it.targetId}"
        }

        val selectedRelationships = relationshipGroups
            .filter { (_, rels) -> rels.size >= config.minAgreement }
            .map { (_, rels) -> rels.first() }

        logger.info(
            "Parallel relationship analysis: {} total, {} after agreement",
            allRelationships.size,
            selectedRelationships.size
        )

        return SuggestedRelationships(
            entitiesResolution = suggestedEntitiesResolution,
            suggestedRelationships = selectedRelationships,
        )
    }

    /**
     * Select the best entity from a group of similar entities suggested by different analyzers.
     * Prefers more specific labels and longer summaries.
     */
    private fun selectBestEntity(entities: List<SuggestedEntity>): SuggestedEntity {
        return entities.maxByOrNull { entity ->
            // Score: more labels = more specific, longer summary = more detail
            entity.labels.size * 10 + entity.summary.length
        } ?: entities.first()
    }

    /**
     * Normalize entity name for comparison across analyzers.
     */
    private fun normalizeForComparison(name: String): String {
        return name
            .lowercase()
            .replace(Regex("^(mr\\.?|mrs\\.?|ms\\.?|dr\\.?|prof\\.?)\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
