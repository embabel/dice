package com.embabel.dice.text2graph.support

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.AllowedRelationship
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.text2graph.*
import com.embabel.dice.text2graph.builder.SourceAnalysisConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for source analyzers.
 * Supports multiple LLMs for parallel analysis with agreement voting.
 */
@ConfigurationProperties("embabel.dice.source-analyzer")
data class SourceAnalyzerProperties(
    
    /**
     * List of LLM configurations. If multiple are specified, they run in parallel
     * and results are merged with agreement voting.
     */
    val llms: List<LlmOptions> = listOf(LlmOptions()),

    /**
     * Minimum number of LLMs that must agree on an entity for it to be included.
     * Only applies when multiple LLMs are configured.
     */
    val minAgreement: Int = 1,
)

/**
 * Source analyzer that uses an LLM to suggest entities and relationships.
 * @param ai AI service to use for LLM calls.
 * @param llmOptions LLM configuration for this analyzer.
 */
class LlmSourceAnalyzer(
    private val ai: Ai,
    private val llmOptions: LlmOptions = LlmOptions(),
) : SourceAnalyzer {

    private val logger = LoggerFactory.getLogger(LlmSourceAnalyzer::class.java)

    override fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisConfig
    ): SuggestedEntities {
        val entities = ai
            .withLlm(llmOptions)
            .withId("suggest-entities")
            .creating(Entities::class.java)
            .fromTemplate(
                "suggest_entities",
                mapOf(
                    "context" to context,
                    "chunk" to chunk,
                )
            )
        return SuggestedEntities(
            chunkIds = setOf(chunk.id),
            suggestedEntities = entities.entities,
        )
    }

    override fun suggestRelationships(
        chunk: Chunk,
        suggestedEntitiesResolution: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisConfig
    ): SuggestedRelationships {
        val entitiesToUse =
            suggestedEntitiesResolution.resolutions
                .mapNotNull { it.recommended }
        val model = mapOf(
            "possibleRelationships" to context.schema.possibleRelationshipsBetween(entitiesToUse),
            "entitiesToUse" to entitiesToUse,
            "chunk" to chunk,
        )
        val relationships = ai
            .withLlm(llmOptions)
            .withId("suggest-relationships")
            .creating(Relationships::class.java)
            .fromTemplate(
                "suggest_relationships",
                model,
            )
        val allEntities = suggestedEntitiesResolution.resolutions
            .mapNotNull { it.recommended }
        val newRelationships = relationships.relationships
            .filter {
                val sourceEntity = allEntities.find { entity -> entity.id == it.sourceId }
                val targetEntity = allEntities.find { entity -> entity.id == it.targetId }
                if (sourceEntity == null || targetEntity == null) {
                    logger.warn("Internal error checking relationship: ${it.sourceId} -[${it.type}]-> ${it.targetId} because one of the entities is not found.")
                    return@filter false
                }

                it.isValid(context.schema, sourceEntity, targetEntity)
            }
        return SuggestedRelationships(
            entitiesResolution = suggestedEntitiesResolution,
            suggestedRelationships = newRelationships,
        )
    }
}

private fun DataDictionary.possibleRelationshipsBetween(entitiesToUse: List<NamedEntityData>): List<AllowedRelationship> {
    return allowedRelationships().filter { rel ->
        entitiesToUse.any { it.labels().contains(rel.from.name) } &&
                entitiesToUse.any { it.labels().contains(rel.to.name) }
    }
}

/**
 * LLM generated
 */
internal data class Entities(
    val entities: List<SuggestedEntity>,
)

private data class Relationships(
    val relationships: List<SuggestedRelationship>,
)