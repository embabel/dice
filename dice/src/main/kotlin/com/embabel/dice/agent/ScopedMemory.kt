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
package com.embabel.dice.agent

import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.store.ReadBudget
import com.embabel.dice.store.ReadBudgetExceededException
import com.embabel.dice.store.ReadCriteria
import com.embabel.dice.store.ReadKeywordQuery
import com.embabel.dice.store.ReadOrder
import com.embabel.dice.store.ReadQuery
import com.embabel.dice.store.ReadRefusedException
import com.embabel.dice.store.ReadVectorSearchRequest
import com.embabel.dice.store.ScopedReadSpec
import com.embabel.dice.store.ScopedReads
import com.embabel.dice.store.ScopedVectorDimensionMismatchException
import com.embabel.dice.store.ScopedVectorSearch
import com.embabel.dice.store.StoreCapability
import com.embabel.dice.store.UnsupportedCapabilityException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.annotations.ApiStatus
import java.util.Locale
import java.util.function.UnaryOperator

/**
 * A memory tool and prompt reference over a host-supplied spec and budget. Each returned
 * proposition is one JSON line with `contextId` and `text` fields. The host creates the spec
 * after authorizing the contexts. Read operations through this tool leave access timestamps alone.
 *
 * @see Memory for single-context memory.
 */
@ApiStatus.Experimental
class ScopedMemory private constructor(
    private val reads: ScopedReads,
    private val spec: ScopedReadSpec,
    private val budget: ReadBudget,
    private val vectorSearch: ScopedVectorSearch?,
    private val eagerQuery: ReadQuery? = null,
) : Tool, LlmReference {

    override val name: String = "memory"

    override val description: String = "Recall memories from the authorized contexts"

    override fun notes(): String = "Use when you need to recall prior information."

    override fun tools(): List<Tool> = listOf(this)

    /**
     * Adds criteria to every read. The operator receives empty criteria and the result is
     * intersected with the host's existing filters.
     */
    fun narrowedBy(narrowing: UnaryOperator<ReadCriteria>): ScopedMemory =
        ScopedMemory(reads, spec.narrow(narrowing), budget, vectorSearch, eagerQuery)

    /**
     * Preloads facts from the same scope. A refused query leaves its facts out of the prompt
     * and reports the refusal. A query without a limit is subject to the row cap.
     */
    fun withEagerQuery(query: ReadQuery): ScopedMemory =
        ScopedMemory(reads, spec, budget, vectorSearch, query)

    override fun contribution(): String = buildString {
        val count = try {
            "${reads.count(spec, ReadCriteria.empty(), budget)} memories available."
        } catch (_: ReadBudgetExceededException) {
            "more than ${budget.maxRows} memories available."
        } catch (failure: UnsupportedCapabilityException) {
            "Memory count unavailable. ${failure.message}"
        }
        appendLine("Reference: $name")
        appendLine("Description: $description. $count")
        val eager = try {
            eagerQuery?.let { reads.query(spec, it, budget) }.orEmpty().distinctBy { it.id }
        } catch (failure: ReadRefusedException) {
            appendLine("Key memories unavailable. ${failure.message}")
            emptyList()
        } catch (failure: UnsupportedCapabilityException) {
            appendLine("Key memories unavailable. ${failure.message}")
            emptyList()
        }
        if (eager.isNotEmpty()) {
            appendLine()
            appendLine("Key memories:")
            eager.forEach { appendLine(renderFact(it)) }
        }
        append(notes())
    }

    override val definition: Tool.Definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("query", "Search text. Omit to list memories.", required = false),
            Tool.Parameter.integer("limit", "Maximum number of results, up to ${budget.maxRows}", required = false),
        ),
    )

    override fun call(input: String): Tool.Result {
        val params = if (input.isBlank()) null else try {
            inputReader.readTree(input)
        } catch (_: JsonProcessingException) {
            return Tool.Result.error("Memory arguments must be a JSON object.")
        }
        if (params != null && !params.isObject) {
            return Tool.Result.error("Memory arguments must be a JSON object.")
        }
        val query = params?.get("query")?.let {
            if (!it.isTextual) return Tool.Result.error("The query must be text.")
            it.asText().trim().takeIf(String::isNotEmpty)
        }
        val limit = params?.get("limit")?.let {
            if (!it.isIntegralNumber || !it.canConvertToLong() || it.longValue() <= 0) {
                return Tool.Result.error("The result limit must be a positive integer within the host's row budget.")
            }
            it.longValue()
        } ?: minOf(Memory.DEFAULT_LIMIT, budget.maxRows).toLong()

        val propositions = try {
            budget.requireRows(limit)
            val rows = limit.toInt()
            if (query == null) {
                reads.query(spec, ReadQuery.of(ReadCriteria.empty()).orderedByCreated().withLimit(rows), budget)
            } else {
                search(query, rows)
            }
        } catch (failure: ReadBudgetExceededException) {
            return Tool.Result.error(failure.message ?: "The memory read exceeds its budget.")
        } catch (failure: UnsupportedCapabilityException) {
            return Tool.Result.error(failure.message ?: "The memory view cannot apply this request.")
        } catch (_: ScopedVectorDimensionMismatchException) {
            return Tool.Result.error(
                "Stored embeddings use a different dimension. Re-embed the repository with the configured embedding model.",
            )
        }
        return Tool.Result.text(
            if (propositions.isEmpty()) "No memories matched."
            else propositions.joinToString("\n", transform = ::renderFact)
        )
    }

    /** Keeps every fact on one line, including text containing Unicode line separators. */
    private fun renderFact(proposition: Proposition): String =
        factWriter.writeValueAsString(mapOf("contextId" to proposition.contextIdValue, "text" to proposition.text))
            .replace("\u0085", "\\u0085")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    private fun search(query: String, limit: Int): List<Proposition> {
        val vectors = vectorSearch?.findNearest(
            spec, ReadVectorSearchRequest.forText(query).withTopK(limit), budget,
        ).orEmpty().map { it.proposition }
        val tokens = query.lowercase(Locale.ROOT).split(tokenSeparator).filter { it.isNotEmpty() }
        val keywords = if (tokens.isEmpty()) emptyList() else reads.matchKeywords(
            spec, ReadKeywordQuery.forTokens(tokens).withOrder(ReadOrder.CREATED_DESC).withLimit(limit), budget,
        )
        val scores = mutableMapOf<String, Double>()
        for (ranked in listOf(vectors, keywords)) {
            ranked.forEachIndexed { index, proposition ->
                scores.merge(proposition.id, 1.0 / (61 + index), Double::plus)
            }
        }
        return (vectors + keywords).distinctBy { it.id }
            .sortedByDescending { scores.getValue(it.id) }.take(limit)
    }

    companion object {
        private val json = jacksonObjectMapper()
        private val inputReader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val factWriter = json.writer()
        private val tokenSeparator = Regex("""[^\p{L}\p{N}]+""")

        /**
         * Creates read-only memory. A row-only budget enables keyword search. A budget allowing
         * vector candidates also enables vector search and requires a view that supports it.
         *
         * @param reads an opted-in repository view
         * @param spec authorized contexts and any host filters
         * @param budget limits on returned rows and vector work
         */
        @JvmStatic
        fun forReads(reads: ScopedReads, spec: ScopedReadSpec, budget: ReadBudget): ScopedMemory {
            val vectors = if (budget.isVectorSearchAllowed) {
                reads as? ScopedVectorSearch ?: throw UnsupportedCapabilityException(
                    StoreCapability.SCOPED_VECTOR_SEARCH,
                    "use a row-only budget or provide a view that supports scoped vector search",
                )
            } else null
            return ScopedMemory(reads, spec.narrow(ReadCriteria.empty()
                .withStatus(PropositionStatus.ACTIVE)
                .withMinConfidence(Memory.DEFAULT_MIN_CONFIDENCE)), budget, vectors)
        }
    }
}
