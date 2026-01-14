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

import com.embabel.dice.proposition.Projection
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/**
 * Well-known path for DICE Prolog rules on the classpath.
 */
const val DICE_RULES_RESOURCE_PATH = "prolog/dice-rules.pl"

/**
 * Loads Prolog rules from files or classpath resources.
 */
object PrologRuleLoader {

    private val logger = LoggerFactory.getLogger(PrologRuleLoader::class.java)

    /**
     * Load rules from a file path.
     */
    fun loadFromFile(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Prolog file not found: $filePath")
        }
        logger.debug("Loaded Prolog rules from file: {}", filePath)
        return file.readText()
    }

    /**
     * Load rules from a classpath resource.
     * @param resourcePath Path to the resource, e.g., "prolog/dice-rules.pl"
     * @param classLoader ClassLoader to use for loading the resource
     */
    fun loadFromResource(
        resourcePath: String = DICE_RULES_RESOURCE_PATH,
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    ): String {
        val normalizedPath = resourcePath.removePrefix("/")
        val stream = classLoader.getResourceAsStream(normalizedPath)
            ?: throw IllegalArgumentException("Prolog resource not found: $resourcePath")

        logger.debug("Loaded Prolog rules from resource: {}", resourcePath)
        return stream.use { it.bufferedReader().readText() }
    }

    /**
     * Try to load rules from a classpath resource, returning null if not found.
     */
    fun tryLoadFromResource(
        resourcePath: String = DICE_RULES_RESOURCE_PATH,
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    ): String? {
        return try {
            loadFromResource(resourcePath, classLoader)
        } catch (e: IllegalArgumentException) {
            logger.debug("Prolog resource not found: {}", resourcePath)
            null
        }
    }

    /**
     * Load rules from an input stream.
     */
    fun loadFromStream(stream: InputStream): String {
        return stream.use { it.bufferedReader().readText() }
    }

    /**
     * Load and combine multiple rule files.
     */
    fun loadMultiple(vararg paths: String): String {
        return paths.map { loadFromFile(it) }.joinToString("\n\n")
    }
}

/**
 * A Prolog fact projected from a proposition.
 * Facts are ground terms (no variables) that represent knowledge.
 *
 * @property predicate The predicate name (e.g., "expert_in")
 * @property args The arguments as strings (e.g., ["alice_id", "kubernetes"])
 * @property confidence The confidence from the source proposition
 * @property decay The decay rate (defaults to 0.0 for Prolog facts)
 * @property sourcePropositionIds Provenance tracking
 */
data class PrologFact(
    val predicate: String,
    val args: List<String>,
    override val confidence: Double,
    override val decay: Double = 0.0,
    override val sourcePropositionIds: List<String>,
) : Projection {

    /**
     * Format as Prolog syntax: predicate('arg1', 'arg2').
     */
    fun toProlog(): String {
        val quotedArgs = args.joinToString(", ") { quoteAtom(it) }
        return "$predicate($quotedArgs)."
    }

    /**
     * Format without trailing period (for embedding in rules).
     */
    fun toPrologTerm(): String {
        val quotedArgs = args.joinToString(", ") { quoteAtom(it) }
        return "$predicate($quotedArgs)"
    }

    companion object {
        /**
         * Quote an atom for Prolog. Atoms with special chars or starting with uppercase need quotes.
         */
        fun quoteAtom(value: String): String {
            val normalized = value.lowercase().replace(Regex("[^a-z0-9_]"), "_")
            return "'$normalized'"
        }
    }
}

/**
 * A confidence fact that accompanies a main fact.
 * Allows Prolog rules to filter by confidence threshold.
 */
data class ConfidenceFact(
    val fact: PrologFact,
) {
    fun toProlog(): String {
        return "confidence(${fact.toPrologTerm()}, ${fact.confidence})."
    }
}

/**
 * A grounding fact that links a Prolog fact back to its source proposition.
 * Enables provenance queries.
 */
data class GroundingFact(
    val fact: PrologFact,
    val propositionId: String,
) {
    fun toProlog(): String {
        return "grounded_by(${fact.toPrologTerm()}, '${propositionId}')."
    }
}

/**
 * Maps a relationship type from the schema to a Prolog predicate.
 *
 * @property relationshipType The relationship type name (e.g., "EXPERT_IN")
 * @property predicate The Prolog predicate name (e.g., "expert_in")
 * @property subjectArgIndex Which argument position is the subject (default 0)
 * @property objectArgIndex Which argument position is the object (default 1)
 */
data class PredicateMapping(
    val relationshipType: String,
    val predicate: String,
    val subjectArgIndex: Int = 0,
    val objectArgIndex: Int = 1,
)

/**
 * Schema defining how relationships map to Prolog predicates.
 * Also holds base inference rules.
 */
class PrologSchema(
    private val mappings: List<PredicateMapping>,
    private val baseRules: String = "",
) {
    private val mappingsByType = mappings.associateBy { it.relationshipType.uppercase() }

    /**
     * Get the predicate mapping for a relationship type.
     * Handles both UPPER_SNAKE_CASE and camelCase input.
     */
    fun getMapping(relationshipType: String): PredicateMapping? {
        // Try direct uppercase lookup first
        val directMatch = mappingsByType[relationshipType.uppercase()]
        if (directMatch != null) return directMatch

        // Try converting camelCase to UPPER_SNAKE_CASE
        val snakeCase = camelToUpperSnakeCase(relationshipType)
        return mappingsByType[snakeCase]
    }

    /**
     * Get the predicate name for a relationship type, or derive one.
     * Converts camelCase (e.g., "expertIn") to snake_case (e.g., "expert_in").
     */
    fun getPredicate(relationshipType: String): String =
        getMapping(relationshipType)?.predicate
            ?: camelToSnakeCase(relationshipType)

    private fun camelToUpperSnakeCase(input: String): String =
        input.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    private fun camelToSnakeCase(input: String): String =
        input.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    /**
     * Get the base inference rules.
     */
    fun getBaseRules(): String = baseRules

    /**
     * All defined predicates.
     */
    fun allPredicates(): List<String> = mappings.map { it.predicate }

    companion object {
        private val logger = LoggerFactory.getLogger(PrologSchema::class.java)

        /**
         * Create a schema with default mappings and rules loaded from classpath.
         * Looks for rules at [DICE_RULES_RESOURCE_PATH] (prolog/dice-rules.pl).
         * Falls back to empty rules if not found.
         *
         * @param additionalMappings Extra predicate mappings to include
         * @param rulesPath Classpath path to rules file (default: prolog/dice-rules.pl)
         */
        fun withDefaults(
            additionalMappings: List<PredicateMapping> = emptyList(),
            rulesPath: String = DICE_RULES_RESOURCE_PATH,
        ): PrologSchema {
            val rules = PrologRuleLoader.tryLoadFromResource(rulesPath)
            if (rules == null) {
                logger.warn(
                    "No Prolog rules found at '{}'. Create this file to add inference rules.",
                    rulesPath
                )
            }
            return PrologSchema(
                mappings = DEFAULT_MAPPINGS + additionalMappings,
                baseRules = rules ?: "",
            )
        }

        /**
         * Create a schema with explicit rules string (for testing or inline rules).
         */
        fun withRules(
            rules: String,
            additionalMappings: List<PredicateMapping> = emptyList(),
        ): PrologSchema = PrologSchema(
            mappings = DEFAULT_MAPPINGS + additionalMappings,
            baseRules = rules,
        )

        /**
         * Default relationship-to-predicate mappings.
         */
        val DEFAULT_MAPPINGS = listOf(
            PredicateMapping("EXPERT_IN", "expert_in"),
            PredicateMapping("KNOWS", "knows"),
            PredicateMapping("WORKS_AT", "works_at"),
            PredicateMapping("LIVES_IN", "lives_in"),
            PredicateMapping("OWNS", "owns"),
            PredicateMapping("FRIEND_OF", "friend_of"),
            PredicateMapping("COLLEAGUE_OF", "colleague_of"),
            PredicateMapping("REPORTS_TO", "reports_to"),
            PredicateMapping("MANAGES", "manages"),
            PredicateMapping("MEMBER_OF", "member_of"),
            PredicateMapping("CREATED", "created"),
            PredicateMapping("USES", "uses"),
        )
    }
}

/**
 * Result of projecting propositions to Prolog.
 *
 * @property facts The main facts
 * @property confidenceFacts Confidence metadata for each fact
 * @property groundingFacts Provenance metadata for each fact
 */
data class PrologProjectionResult(
    val facts: List<PrologFact>,
    val confidenceFacts: List<ConfidenceFact>,
    val groundingFacts: List<GroundingFact>,
) {
    /**
     * Generate complete Prolog theory with all facts.
     */
    fun toTheory(schema: PrologSchema): String = buildString {
        appendLine("% Base inference rules")
        appendLine(schema.getBaseRules())
        appendLine()
        appendLine("% Projected facts")
        facts.forEach { appendLine(it.toProlog()) }
        appendLine()
        appendLine("% Confidence metadata")
        confidenceFacts.forEach { appendLine(it.toProlog()) }
        appendLine()
        appendLine("% Grounding/provenance")
        groundingFacts.forEach { appendLine(it.toProlog()) }
    }

    val factCount: Int get() = facts.size
}
