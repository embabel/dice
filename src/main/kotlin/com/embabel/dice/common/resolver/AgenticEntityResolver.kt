package com.embabel.dice.common.resolver

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
import com.embabel.dice.common.*
import org.slf4j.LoggerFactory

/**
 * Agentic entity resolver that uses ToolishRag to let an LLM drive the search process.
 *
 * For each suggested entity, creates a ToolishRag instance configured to search for
 * entities of the matching type, then lets the LLM craft queries, examine results,
 * and select the best match.
 *
 * This approach is more accurate than heuristic matching because:
 * - The LLM can craft better search queries based on context
 * - The LLM can iteratively refine searches
 * - The LLM understands semantic relationships (e.g., "The Ring" = "Der Ring des Nibelungen")
 *
 * @param repository The entity repository providing search operations
 * @param ai The AI instance for running the agentic loop
 * @param llmOptions LLM configuration for the search agent
 * @param dataDictionary Schema for resolving entity types
 */
class AgenticEntityResolver(
    private val repository: NamedEntityDataRepository,
    private val ai: Ai,
    private val llmOptions: LlmOptions = LlmOptions(model = "gpt-4.1-mini"),
    private val dataDictionary: DataDictionary? = null,
) : EntityResolver {

    private val logger = LoggerFactory.getLogger(AgenticEntityResolver::class.java)

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        logger.info(
            "Resolving {} suggested entities agentically from chunks {}",
            suggestedEntities.suggestedEntities.size,
            suggestedEntities.chunkIds
        )

        val effectiveSchema = dataDictionary ?: schema
        val sourceText = suggestedEntities.sourceText

        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            resolveEntity(suggested, effectiveSchema, sourceText)
        }

        val existingCount = resolutions.count { it is ExistingEntity }
        val newCount = resolutions.count { it is NewEntity }
        val vetoedCount = resolutions.count { it is VetoedEntity }
        logger.info(
            "Agentic entity resolution complete: {} matched existing, {} new, {} vetoed",
            existingCount,
            newCount,
            vetoedCount
        )

        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolutions,
        )
    }

    private fun resolveEntity(
        suggested: SuggestedEntity,
        schema: DataDictionary,
        sourceText: String?
    ): SuggestedEntityResolution {
        val entityType = suggested.labels.firstOrNull() ?: "Entity"
        val creationPermitted = isCreationPermitted(suggested, schema)

        logger.info("Agentically resolving '{}' (type: {})", suggested.name, entityType)

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
            name = "entity-resolver",
            description = "Search for existing entities in the knowledge base",
            searchOperations = repository,
            goal = buildGoal(suggested, sourceText),
            textSearchFor = searchTypes,
            vectorSearchFor = searchTypes,
            listener = listener,
        )

        try {
            // Run the agentic search - the listener collects results as they're found
            val result = ai
                .withLlm(llmOptions)
                .withReference(rag)
                .generateText(buildPrompt(suggested, entityType, sourceText))

            logger.debug("Agentic search result for '{}': {}", suggested.name, result)

            // Check if we found a match
            val bestMatch = findBestMatch(suggested, foundEntities, schema)
            if (bestMatch != null) {
                logger.info("Agentic resolver matched '{}' -> '{}'", suggested.name, bestMatch.name)
                return ExistingEntity(suggested, bestMatch)
            }
        } catch (e: Exception) {
            logger.warn("Agentic resolution failed for '{}': {}", suggested.name, e.message)
        }

        // No match found
        return if (!creationPermitted) {
            logger.info("No match found for '{}' (had {} candidates) and creation not permitted - vetoing",
                suggested.name, foundEntities.size)
            VetoedEntity(suggested)
        } else {
            logger.debug("No match found for '{}' (had {} candidates), creating new entity",
                suggested.name, foundEntities.size)
            NewEntity(suggested)
        }
    }

    private fun buildGoal(suggested: SuggestedEntity, sourceText: String?): String {
        val contextHint = if (!sourceText.isNullOrBlank()) {
            "\nUse the conversation context to understand what entity is being discussed."
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

    private fun buildPrompt(suggested: SuggestedEntity, entityType: String, sourceText: String?): String {
        val contextSection = if (!sourceText.isNullOrBlank()) {
            """

            CONVERSATION CONTEXT (use this to understand what's being discussed):
            $sourceText
            """.trimIndent()
        } else ""

        return """
            Find the entity "${suggested.name}" of type "$entityType" in the knowledge base.

            Entity details:
            - Name: ${suggested.name}
            - Type: $entityType
            - Context: ${suggested.summary.ifBlank { "No additional context" }}
            $contextSection

            Search for this entity using text search. Try different queries:
            - The exact name
            - Partial name matches
            - Alternate names or spellings
            - For works, include composer name if known from context

            When you find candidates, evaluate if they match the entity we're looking for.
            Report the best match or indicate no match was found.
        """.trimIndent()
    }

    private fun getSearchTypesForLabel(label: String, schema: DataDictionary): List<Class<out Retrievable>> {
        // Try to find the JVM type for this label
        val domainType = schema.domainTypeForLabels(setOf(label))
        if (domainType != null) {
            val jvmType = schema.jvmTypes.find { it.ownLabel == label }
            if (jvmType != null && Retrievable::class.java.isAssignableFrom(jvmType.clazz)) {
                @Suppress("UNCHECKED_CAST")
                return listOf(jvmType.clazz as Class<out Retrievable>)
            }
        }
        // Fall back to NamedEntityData
        return listOf(NamedEntityData::class.java)
    }

    private fun findBestMatch(
        suggested: SuggestedEntity,
        candidates: List<NamedEntity>,
        schema: DataDictionary
    ): NamedEntityData? {
        if (candidates.isEmpty()) {
            logger.debug("No candidates found for '{}'", suggested.name)
            return null
        }

        logger.debug("Evaluating {} candidates for '{}'", candidates.size, suggested.name)

        // Filter by label compatibility
        val suggestedLabels = suggested.labels.map { it.substringAfterLast('.') }.toSet()

        val compatible = candidates.filter { candidate ->
            val candidateLabels = candidate.labels().map { it.substringAfterLast('.') }.toSet()
            val hasMatch = suggestedLabels.intersect(candidateLabels).isNotEmpty()
            logger.debug("Candidate '{}' labels {} compatible with {}: {}",
                candidate.name, candidateLabels, suggestedLabels, hasMatch)
            hasMatch
        }

        if (compatible.isEmpty()) {
            logger.debug("No label-compatible candidates for '{}'", suggested.name)
            return null
        }

        // Prefer exact name match
        val exactMatch = compatible.find {
            it.name.equals(suggested.name, ignoreCase = true)
        }
        val bestMatch = exactMatch ?: compatible.first()

        // Convert NamedEntity to NamedEntityData
        return if (bestMatch is NamedEntityData) {
            bestMatch
        } else {
            SimpleNamedEntityData(
                id = bestMatch.id,
                name = bestMatch.name,
                description = bestMatch.description,
                labels = bestMatch.labels(),
                properties = emptyMap(),
            )
        }
    }

    private fun isCreationPermitted(suggested: SuggestedEntity, schema: DataDictionary): Boolean {
        val labels = suggested.labels.map { it.substringAfterLast('.') }.toSet()
        val domainType = schema.domainTypeForLabels(labels) ?: return true
        return try {
            domainType.creationPermitted
        } catch (e: Exception) {
            true
        }
    }
}
