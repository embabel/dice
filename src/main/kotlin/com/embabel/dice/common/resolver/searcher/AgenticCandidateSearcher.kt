package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.tools.ResultsEvent
import com.embabel.agent.rag.tools.ResultsListener
import com.embabel.agent.rag.tools.ToolishRag
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.SearchResult
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * Structured response from the agentic entity search.
 */
data class AgenticSearchResult(
    @field:JsonPropertyDescription("The ID of the matched entity, or null if no match was found")
    val matchedEntityId: String?,
    @field:JsonPropertyDescription("Brief explanation of why this entity was selected or why no match was found")
    val reason: String,
)

/**
 * Agentic candidate searcher that uses [ToolishRag] to let an LLM drive the search process.
 *
 * Unlike heuristic searchers that apply fixed matching rules, this searcher gives the LLM
 * full control to craft queries, examine results, and iteratively refine searches.
 *
 * ## When to Use
 * Add this as the last searcher in [com.embabel.dice.common.resolver.EscalatingEntityResolver] when:
 * - Heuristic matching (exact, normalized, partial, fuzzy, vector) is insufficient
 * - Entities have many alternate names or translations (e.g., musical works, places)
 * - Semantic understanding is required to match entities
 *
 * ## How It Works
 * 1. Configures a [ToolishRag] with text and vector search for the entity type
 * 2. The LLM crafts search queries based on the entity name and summary
 * 3. The LLM examines search results and can refine queries iteratively
 * 4. Returns [SearchResult.confident] if LLM finds a match, otherwise candidates
 *
 * ## Trade-offs
 * - Slower than heuristic searchers (LLM calls per entity)
 * - Higher cost due to LLM usage
 * - Should be placed last in the searcher chain after cheaper options
 *
 * @param repository The entity repository providing search operations
 * @param ai The AI instance for running the agentic loop
 * @param llmOptions LLM configuration for the search agent
 *
 * @see VectorCandidateSearcher for embedding-based search without LLM
 * @see FuzzyNameCandidateSearcher for heuristic fuzzy matching
 */
class AgenticCandidateSearcher(
    private val repository: NamedEntityDataRepository,
    private val ai: Ai,
    private val llmOptions: LlmOptions,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(AgenticCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val entityType = suggested.labels.firstOrNull() ?: "Entity"

        logger.debug("Agentically searching for '{}' (type: {})", suggested.name, entityType)

        // Collect results from the agentic search
        val foundEntities = mutableListOf<NamedEntity>()
        val listener = ResultsListener { event: ResultsEvent ->
            event.results.forEach { result ->
                val match = result.match
                if (match is NamedEntity) {
                    foundEntities.add(match)
                    logger.debug("Found candidate entity: {} ({})", match.name, match.id)
                }
            }
        }

        // Configure ToolishRag for this entity type
        val searchTypes = getSearchTypesForLabel(entityType, schema)

        val rag = ToolishRag(
            name = "entity-searcher",
            description = "Search for existing entities in the knowledge base",
            searchOperations = repository,
            goal = buildGoal(suggested),
            textSearchFor = searchTypes,
            vectorSearchFor = searchTypes,
            listener = listener,
        )

        try {
            // Run the agentic search with structured output
            val result = ai
                .withLlm(llmOptions)
                .withId("agentic-candidate-searcher")
                .withReference(rag)
                .createObject(buildPrompt(suggested, entityType), AgenticSearchResult::class.java)

            logger.debug(
                "Agentic search result for '{}': id={}, reason={}",
                suggested.name, result.matchedEntityId, result.reason
            )

            // Check if LLM found a confident match
            if (result.matchedEntityId != null) {
                val matchedEntity = foundEntities.find { it.id == result.matchedEntityId }
                if (matchedEntity != null) {
                    // Verify label compatibility
                    val suggestedLabels = suggested.labels.map { it.substringAfterLast('.') }.toSet()
                    val matchedLabels = matchedEntity.labels().map { it.substringAfterLast('.') }.toSet()
                    if (suggestedLabels.intersect(matchedLabels).isNotEmpty()) {
                        val entityData = matchedEntity.toNamedEntityData()
                        logger.info(
                            "AGENTIC: '{}' -> '{}' ({})",
                            suggested.name, matchedEntity.name, result.reason
                        )
                        return SearchResult.confident(entityData)
                    } else {
                        logger.warn(
                            "LLM selected '{}' but labels don't match: suggested={}, matched={}",
                            matchedEntity.name, suggestedLabels, matchedLabels
                        )
                    }
                } else {
                    logger.warn("LLM selected entity id '{}' but not found in candidates", result.matchedEntityId)
                }
            } else {
                logger.debug("LLM found no match for '{}': {}", suggested.name, result.reason)
            }
        } catch (e: Exception) {
            logger.warn("Agentic search failed for '{}': {}", suggested.name, e.message)
        }

        // Return candidates without confident match
        val candidates = foundEntities.map { it.toNamedEntityData() }
        return SearchResult.candidates(candidates)
    }

    private fun buildGoal(suggested: SuggestedEntity): String {
        val contextHint = if (suggested.summary.isNotBlank()) {
            "\nContext: ${suggested.summary}"
        } else ""

        return """
            Find the entity "${suggested.name}" in the knowledge base.
            The entity should be of type: ${suggested.labels.joinToString(", ")}
            $contextHint

            If you find an exact or close match, report it.
            If after trying different searches you cannot find a match, report that no match was found.
            Be creative with search queries - try alternate names, partial names, related terms.
        """.trimIndent()
    }

    private fun buildPrompt(suggested: SuggestedEntity, entityType: String): String {
        val contextSection = if (suggested.summary.isNotBlank()) {
            """

            CONTEXT:
            ${suggested.summary}
            """.trimIndent()
        } else ""

        return """
            Find the entity "${suggested.name}" of type "$entityType" in the knowledge base.

            Entity details:
            - Name: ${suggested.name}
            - Type: $entityType
            $contextSection

            Search for this entity using text search. Try different queries:
            - The exact name
            - Partial name matches
            - Alternate names or spellings
            - For works, try "composer_name work_name" format
            - For composers, try their last name

            IMPORTANT: Only select an entity if it ACTUALLY matches what we're looking for.
            - For works: The work must be BY the correct composer.
            - Don't select a work just because it has a similar name if it's by a different composer.
            - If you can't find the exact entity, return null for matchedEntityId.

            After searching, return the ID of the best matching entity, or null if no good match exists.
        """.trimIndent()
    }

    private fun NamedEntity.toNamedEntityData(): NamedEntityData =
        if (this is NamedEntityData) this
        else SimpleNamedEntityData(
            id = id,
            name = name,
            description = description,
            labels = labels(),
            properties = emptyMap(),
        )

    private fun getSearchTypesForLabel(label: String, schema: DataDictionary): List<Class<out Retrievable>> {
        val domainType = schema.domainTypeForLabels(setOf(label))
        if (domainType != null) {
            val jvmType = schema.jvmTypes.find { it.ownLabel == label }
            if (jvmType != null && Retrievable::class.java.isAssignableFrom(jvmType.clazz)) {
                @Suppress("UNCHECKED_CAST")
                return listOf(jvmType.clazz as Class<out Retrievable>)
            }
        }
        return listOf(NamedEntityData::class.java)
    }

    companion object {
        /**
         * Create an agentic searcher.
         *
         * @param repository The entity repository
         * @param ai The AI instance
         * @param llmOptions LLM configuration
         */
        @JvmStatic
        fun create(
            repository: NamedEntityDataRepository,
            ai: Ai,
            llmOptions: LlmOptions,
        ): AgenticCandidateSearcher = AgenticCandidateSearcher(repository, ai, llmOptions)
    }
}
