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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.dice.projection.prolog.PrologEngine
import com.embabel.dice.projection.prolog.PrologProjectionResult
import com.embabel.dice.projection.prolog.PrologSchema
import com.embabel.dice.projection.prolog.QueryResult
import org.slf4j.LoggerFactory

/**
 * Prolog tools that can be invoked by an LLM to query a knowledge base.
 *
 * @param prologResult Prolog facts from projection
 * @param prologSchema Schema with predicate mappings and rules
 * @param entityNames Mapping of entity IDs to human-readable names
 */
class PrologTools(
    private val prologResult: PrologProjectionResult,
    prologSchema: PrologSchema,
    private val entityNames: Map<String, String> = emptyMap(),
) {
    private val logger = LoggerFactory.getLogger(PrologTools::class.java)
    private val engine: PrologEngine = PrologEngine.fromProjection(prologResult, prologSchema)
    private val predicates: List<String> = prologSchema.allPredicates()

    // Reverse mapping: name -> ID (normalized)
    private val nameToId: Map<String, String> = entityNames.entries
        .associate { (id, name) -> name.lowercase() to id.replace("-", "_") }

    /**
     * Query the Prolog knowledge base.
     * Returns all matching results with variable bindings.
     *
     * @param query Prolog query (e.g., "expert_in(X, Y)")
     * @return Human-readable results
     */
    @LlmTool(
        name = "query_prolog",
        description = "Query the Prolog knowledge base. Use uppercase variables (X, Y) to find values. " +
            "IMPORTANT: First call show_facts to see the actual data format. " +
            "Use variable queries like expert_in(X, Y) to find all matches."
    )
    fun queryProlog(
        @LlmTool.Param(description = "Prolog query with uppercase variables, e.g., expert_in(X, Y) or works_at(Person, Company)")
        query: String,
    ): String {
        logger.info("Prolog query: {}", query)

        // Try to translate any entity names in the query to IDs
        val translatedQuery = translateNamesToIds(query)
        logger.info("Translated query: {}", translatedQuery)

        val results = engine.queryAll(translatedQuery)
        val successResults = results.filter { it.success }

        if (successResults.isEmpty()) {
            // Try a more general query if specific one failed
            val generalQuery = makeQueryGeneral(translatedQuery)
            if (generalQuery != translatedQuery) {
                logger.info("Trying general query: {}", generalQuery)
                val generalResults = engine.queryAll(generalQuery).filter { it.success }
                if (generalResults.isNotEmpty()) {
                    val formatted = generalResults.map { formatBindings(it) }
                    return "Found ${formatted.size} result(s) (using general query):\n" +
                        formatted.joinToString("\n") { "- $it" }
                }
            }
            return "No results found for query: $query"
        }

        // Format results with human-readable names
        val formatted = successResults.map { result ->
            formatBindings(result)
        }

        return "Found ${formatted.size} result(s):\n" + formatted.joinToString("\n") { "- $it" }
    }

    /**
     * Translate entity names in a query to their IDs.
     */
    private fun translateNamesToIds(query: String): String {
        var result = query
        // Find quoted strings and try to translate them
        val pattern = Regex("'([^']+)'")
        pattern.findAll(query).forEach { match ->
            val name = match.groupValues[1]
            val normalizedName = name.lowercase()
            nameToId[normalizedName]?.let { id ->
                result = result.replace("'$name'", "'$id'")
            }
        }
        return result
    }

    /**
     * Make a query more general by replacing specific values with variables.
     */
    private fun makeQueryGeneral(query: String): String {
        // If query has quoted strings that aren't IDs, replace with variables
        return query.replace(Regex("'[a-z][^']*'")) { match ->
            if (match.value.contains("_") && match.value.length > 30) {
                match.value // Keep UUIDs
            } else {
                "X" // Replace names with variable
            }
        }
    }

    /**
     * List available predicates in the knowledge base.
     */
    @LlmTool(
        name = "list_predicates",
        description = "List all available predicates (relationship types) in the knowledge base."
    )
    fun listPredicates(): String {
        return "Available predicates:\n" + predicates.joinToString("\n") { "- $it(X, Y)" }
    }

    /**
     * List all known entities with their names.
     * Use this to find the correct entity name to use in queries.
     */
    @LlmTool(
        name = "list_entities",
        description = "List all known entities (people, companies, technologies, places) in the knowledge base. " +
            "Use this to find the correct entity name before querying."
    )
    fun listEntities(): String {
        if (entityNames.isEmpty()) {
            return "No entities found in the knowledge base."
        }

        return "Known entities:\n" + entityNames.values.distinct().sorted().joinToString("\n") { "- $it" }
    }

    /**
     * Show sample facts from the knowledge base.
     * Helps understand the structure and available data.
     */
    @LlmTool(
        name = "show_facts",
        description = "Show sample facts from the knowledge base to understand what data is available."
    )
    fun showFacts(): String {
        if (prologResult.facts.isEmpty()) {
            return "No facts in the knowledge base."
        }

        // Show facts with human-readable names
        val readableFacts = prologResult.facts.take(10).map { fact ->
            val args = fact.args.map { arg -> resolveEntityName(arg) }
            "${fact.predicate}(${args.joinToString(", ")})"
        }

        return "Sample facts (${prologResult.facts.size} total):\n" +
            readableFacts.joinToString("\n") { "- $it" }
    }

    /**
     * Check if a specific fact is true.
     *
     * @param query Prolog query to check
     * @return Whether the fact is true
     */
    @LlmTool(
        name = "check_fact",
        description = "Check if a specific fact is true in the knowledge base. " +
            "Returns true/false. Example: check_fact(\"friend_of('alice', 'bob')\")"
    )
    fun checkFact(
        @LlmTool.Param(description = "Prolog fact to check, e.g., friend_of('alice', 'bob')")
        query: String,
    ): String {
        logger.info("Checking fact: {}", query)
        val result = engine.query(query)
        return if (result) "Yes, this is true." else "No, this is not found in the knowledge base."
    }

    private fun formatBindings(result: QueryResult): String {
        if (result.bindings.isEmpty()) {
            return "true"
        }

        return result.bindings.entries.joinToString(", ") { (varName, value) ->
            val humanName = resolveEntityName(value)
            "$varName = $humanName"
        }
    }

    private fun resolveEntityName(entityId: String): String {
        // Try direct lookup
        entityNames[entityId]?.let { return it }

        // Try with underscores replaced by dashes
        entityNames[entityId.replace("_", "-")]?.let { return it }

        // Try to find partial match (entity IDs are often truncated in Prolog)
        val normalizedId = entityId.replace("'", "")
        entityNames.entries.find { (key, _) ->
            key.replace("-", "_").startsWith(normalizedId) ||
                normalizedId.startsWith(key.replace("-", "_").take(36))
        }?.let { return it.value }

        return entityId
    }

    companion object {
        /**
         * Create Tool instances from this PrologTools object.
         */
        fun asTools(
            prologResult: PrologProjectionResult,
            prologSchema: PrologSchema,
            entityNames: Map<String, String> = emptyMap(),
        ): List<Tool> {
            val instance = PrologTools(prologResult, prologSchema, entityNames)
            return Tool.fromInstance(instance)
        }
    }
}
