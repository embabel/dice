package com.embabel.dice.query.oracle

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.projection.prolog.PrologEngine
import com.embabel.dice.projection.prolog.PrologProjectionResult
import com.embabel.dice.projection.prolog.PrologSchema
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * LLM-based Oracle that answers questions using Prolog reasoning
 * with fallback to proposition search.
 *
 * Strategy:
 * 1. Use LLM to translate question to Prolog query
 * 2. Run Prolog query against the knowledge base
 * 3. If no results, fall back to searching propositions
 * 4. Use LLM to formulate natural language answer
 *
 * @param ai AI service for LLM calls
 * @param prologResult Prolog facts from projection
 * @param prologSchema Schema with predicate mappings and rules
 * @param propositionRepository Store to search propositions (for fallback)
 * @param entityNames Mapping of entity IDs to human-readable names
 * @param llmOptions LLM configuration
 */
class LlmOracle(
    private val ai: Ai,
    private val prologResult: PrologProjectionResult,
    private val prologSchema: PrologSchema,
    private val propositionRepository: PropositionRepository? = null,
    private val entityNames: Map<String, String> = emptyMap(),
    private val llmOptions: LlmOptions = LlmOptions(),
) : Oracle {

    private val logger = LoggerFactory.getLogger(LlmOracle::class.java)
    private val engine: PrologEngine by lazy {
        PrologEngine.fromProjection(prologResult, prologSchema)
    }

    override fun ask(question: Question): Answer {
        logger.info("Answering question: {}", question.text)

        // Step 1: Try Prolog-based answer
        val prologAnswer = tryPrologAnswer(question)
        if (prologAnswer != null && !prologAnswer.negative) {
            logger.info("Answered via Prolog: {}", prologAnswer.text)
            return prologAnswer
        }

        // Step 2: Fall back to proposition search
        if (propositionRepository != null) {
            val propAnswer = tryPropositionAnswer(question)
            if (propAnswer != null) {
                logger.info("Answered via propositions: {}", propAnswer.text)
                return propAnswer
            }
        }

        // Step 3: Return negative/unknown answer
        logger.info("Could not answer question")
        return prologAnswer ?: Answer.unknown(question)
    }

    private fun tryPrologAnswer(question: Question): Answer? {
        // Generate Prolog query from question
        val queryPlan = generateQueryPlan(question)
        logger.debug("Query plan: {}", queryPlan)

        if (queryPlan.prologQuery == null) {
            logger.debug("Could not generate Prolog query")
            return null
        }

        // Execute Prolog query
        val results = engine.queryAll(queryPlan.prologQuery)
        val successResults = results.filter { it.success }

        if (successResults.isEmpty()) {
            // Prolog found no results - this is a negative answer
            return Answer.negativeFromProlog(
                text = queryPlan.negativeAnswer ?: "No, I couldn't find information about that.",
                reasoning = "Prolog query '${queryPlan.prologQuery}' returned no results",
            )
        }

        // Format the results with entity names
        val formattedResults = successResults.map { result ->
            result.bindings.mapValues { (_, value) ->
                entityNames[value] ?: entityNames[value.replace("_", "-")] ?: value
            }
        }

        // Collect grounding from matching facts
        val grounding = collectGrounding(queryPlan.prologQuery)

        // Generate natural language answer
        val answerText = generateAnswer(question, queryPlan, formattedResults)

        return Answer.fromProlog(
            text = answerText,
            confidence = 0.9,
            grounding = grounding,
            reasoning = "Prolog query: ${queryPlan.prologQuery}",
        )
    }

    private fun tryPropositionAnswer(question: Question): Answer? {
        val store = propositionRepository ?: return null

        // Search propositions for relevant information
        val relevantProps = store.findSimilar(
            TextSimilaritySearchRequest(
                query = question.text,
                similarityThreshold = 0.0,
                topK = 5
            )
        )

        if (relevantProps.isEmpty()) {
            return null
        }

        // Use LLM to synthesize answer from propositions
        val answer = synthesizeFromPropositions(question, relevantProps)

        return Answer.fromPropositions(
            text = answer,
            confidence = relevantProps.maxOfOrNull { it.confidence } ?: 0.7,
            grounding = relevantProps.map { it.id },
            reasoning = "Synthesized from ${relevantProps.size} propositions",
        )
    }

    private fun generateQueryPlan(question: Question): QueryPlan {
        val availablePredicates = prologSchema.allPredicates()

        return ai
            .withLlm(llmOptions)
            .withId("oracle-query-plan")
            .creating(QueryPlan::class.java)
            .fromTemplate(
                "oracle_query_plan",
                mapOf(
                    "question" to question.text,
                    "predicates" to availablePredicates,
                    "sampleFacts" to prologResult.facts.take(5).map { it.toProlog() },
                )
            )
    }

    private fun generateAnswer(
        question: Question,
        queryPlan: QueryPlan,
        results: List<Map<String, String>>,
    ): String {
        return ai
            .withLlm(llmOptions)
            .withId("oracle-answer")
            .creating(GeneratedAnswer::class.java)
            .fromTemplate(
                "oracle_answer",
                mapOf(
                    "question" to question.text,
                    "queryPlan" to queryPlan,
                    "results" to results,
                )
            ).answer
    }

    private fun synthesizeFromPropositions(
        question: Question,
        propositions: List<Proposition>,
    ): String {
        return ai
            .withLlm(llmOptions)
            .withId("oracle-synthesize")
            .creating(GeneratedAnswer::class.java)
            .fromTemplate(
                "oracle_synthesize",
                mapOf(
                    "question" to question.text,
                    "propositions" to propositions.map { it.text },
                )
            ).answer
    }

    private fun collectGrounding(prologQuery: String): List<String> {
        // Extract grounding from matching facts
        // This is a simplified version - could be more sophisticated
        return prologResult.groundingFacts
            .filter { gf ->
                // Check if this grounding fact matches our query predicate
                prologQuery.substringBefore("(").let { predicate ->
                    gf.fact.predicate == predicate
                }
            }
            .map { it.propositionId }
            .distinct()
    }
}

/**
 * LLM output for query planning.
 */
internal data class QueryPlan(
    @param:JsonPropertyDescription("The Prolog query to execute, or null if not possible")
    val prologQuery: String?,
    @param:JsonPropertyDescription("Variable names in the query that should be returned")
    val resultVariables: List<String> = emptyList(),
    @param:JsonPropertyDescription("Template for negative answer if query fails")
    val negativeAnswer: String?,
    @param:JsonPropertyDescription("Brief reasoning for the query plan")
    val reasoning: String?,
)

/**
 * LLM output for answer generation.
 */
internal data class GeneratedAnswer(
    @param:JsonPropertyDescription("The natural language answer")
    val answer: String,
)

