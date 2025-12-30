package com.embabel.dice.text2graph

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.EntityData
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.common.core.Sourced
import com.embabel.common.util.loggerFor
import com.embabel.dice.text2graph.builder.SourceAnalysisConfig
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.*

data class SuggestedEntity(
    val labels: List<String>,
    val name: String,
    val summary: String,
    @param:JsonPropertyDescription("Will be a UUID. Include only if provided")
    private val id: String? = null,
    @field:JsonPropertyDescription("Map from property name to value")
    val properties: Map<String, Any> = emptyMap(),
) {
    @JsonIgnore
    val suggestedEntity: NamedEntityData = SimpleNamedEntityData(
        id = id ?: UUID.randomUUID().toString(),
        name = name,
        description = summary,
        labels = labels.map { it.substringAfterLast('.') }.toSet(),
        properties = properties,
    )
}

/**
 * Entities suggested by the LLM based on a single input.
 * These entities may duplicate existing entities in the knowledge graph
 */
data class SuggestedEntities(
    override val chunkIds: Set<String>,
    val suggestedEntities: List<SuggestedEntity>,
) : Sourced

data class SuggestedRelationship(
    override val sourceId: String,
    override val targetId: String,
    override val type: String,
    override val description: String? = null,
) : RelationshipInstance {

    fun isValid(
        schema: DataDictionary,
        sourceEntity: EntityData,
        targetEntity: EntityData,
    ): Boolean {
//        val valid =
//            schema.allowedRelationships().any {
//                it.name == type && it.from.labels.intersect(sourceEntity.labels())
//                    .isNotEmpty() && it.to.labels.intersect(
//                    targetEntity.labels()
//                ).isNotEmpty()
//            }
        val from = schema.domainTypeForLabels(sourceEntity.labels())
        val to = schema.domainTypeForLabels(targetEntity.labels())
        if (from == null || to == null) {
            loggerFor<DataDictionary>().info(
                "Relationship {} between {} and {} is invalid",
                type,
                sourceEntity.infoString(verbose = false),
                targetEntity.infoString(verbose = false),
            )
            return false
        } else {
            // TODO fix this
            println("Check relationship $type between $from and $to")
        }
        return true
    }
}


data class SuggestedRelationships(
    val entitiesResolution: Resolutions<SuggestedEntityResolution>,
    val suggestedRelationships: List<SuggestedRelationship>,
)

/**
 * Analyze text
 * Process each chunk in turn.
 * Not responsible for disambiguation or merging,
 * which is handled by a later pipeline stage.
 */
interface SourceAnalyzer {

    /**
     * Identify entities in a chunk based on the provided schema.
     */
    fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisConfig,
    ): SuggestedEntities

    /**
     * Suggest relationships between the given entities based on the provided schema.
     */
    fun suggestRelationships(
        chunk: Chunk,
        suggestedEntitiesResolution: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisConfig,
    ): SuggestedRelationships
}

