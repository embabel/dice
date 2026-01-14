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
package com.embabel.dice.query.oracle

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.tool.Tool
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.projection.prolog.PrologProjectionResult
import com.embabel.dice.projection.prolog.PrologSchema
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory

/**
 * Oracle that uses LLM tool calling to answer questions.
 * The LLM decides when and how to query the Prolog knowledge base.
 *
 * @param ai AI service for LLM calls
 * @param prologResult Prolog facts from projection
 * @param prologSchema Schema with predicate mappings and rules
 * @param propositionRepository Optional store to search propositions
 * @param entityNames Mapping of entity IDs to human-readable names
 * @param llmOptions LLM configuration
 */
class ToolOracle(
    private val ai: Ai,
    prologResult: PrologProjectionResult,
    prologSchema: PrologSchema,
    private val propositionRepository: PropositionRepository? = null,
    entityNames: Map<String, String> = emptyMap(),
    private val llmOptions: LlmOptions = LlmOptions(),
) : Oracle {

    private val logger = LoggerFactory.getLogger(ToolOracle::class.java)

    private val prologTools = PrologTools(prologResult, prologSchema, entityNames)
    private val tools: List<Tool> = Tool.fromInstance(prologTools)

    private val systemPrompt = """
        You are a knowledge assistant that answers questions using a Prolog knowledge base.

        Available tools:
        - show_facts: CALL THIS FIRST to see what data exists and how it's structured
        - query_prolog: Query with variables like expert_in(X, Y) to find all matches
        - list_entities: See all known people, companies, technologies
        - list_predicates: See relationship types (expert_in, works_at, friend_of, etc.)
        - check_fact: Verify if a specific fact is true

        IMPORTANT: Always call show_facts first to see the actual data!

        When answering questions:
        1. Call show_facts to see what's in the knowledge base
        2. Use query_prolog with variables (X, Y) to find matches
        3. Interpret results and give a clear answer
        4. If no info found, say so

        Example workflow:
        - User: "Who knows Kubernetes?"
        - You: Call show_facts, see expert_in(Alice, Kubernetes)
        - You: Answer "Alice is an expert in Kubernetes"
    """.trimIndent()

    override fun ask(question: Question): Answer {
        logger.info("Answering question with tools: {}", question.text)

        val response = ai
            .withLlm(llmOptions)
            .withId("tool-oracle")
            .withTools(tools)
            .withSystemPrompt(systemPrompt)
            .generateText(question.text)

        logger.debug("LLM response: {}", response)

        // Determine the source based on whether we used Prolog
        val source = if (response.contains("query_prolog") ||
            response.contains("Found") ||
            response.contains("result")
        ) {
            AnswerSource.PROLOG
        } else if (propositionRepository != null) {
            AnswerSource.PROPOSITIONS
        } else {
            AnswerSource.NONE
        }

        return Answer(
            text = response,
            confidence = 0.85,
            source = source,
            reasoning = "Answered using LLM with Prolog tools",
        )
    }
}
