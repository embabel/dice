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

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** The topic a memory tool describes when the host does not name one. */
internal const val DEFAULT_MEMORY_TOPIC = "the user & context"

/** The decay memory tools rank by, the same default `Memory` and `PropositionQuery` use. */
internal const val MEMORY_RANKING_DECAY_K = 2.0

/**
 * What the model sent to a memory tool, after checking it.
 *
 * Bad input is the model's mistake, not a scope violation, so it comes back as [Invalid]
 * with a message telling the model what to send instead of throwing.
 */
internal sealed interface MemoryToolInput {

    /**
     * Input the tool can act on.
     *
     * @property query the trimmed search text; empty means list memories
     * @property limit how many results to return, already capped at the host's maximum
     */
    data class Valid(val query: String, val limit: Int) : MemoryToolInput

    /**
     * Input the tool refuses. Nothing is read.
     *
     * @property message what to tell the model so it can retry
     */
    data class Invalid(val message: String) : MemoryToolInput

    companion object {
        /** The message every refused input gets back. */
        const val USAGE = "Send a JSON object with an optional text \"query\" and an optional positive whole-number \"limit\"."

        // Trailing tokens fail the parse so input like `{} {}` is refused rather than half read.
        private val mapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        private val FIELDS = setOf("query", "limit")

        /**
         * Checks raw tool input. Blank input counts as an empty object.
         *
         * Only a JSON object with an optional text `query` and an optional positive whole-number
         * `limit` is accepted; anything else, including extra fields, is [Invalid].
         *
         * @param input the raw text the model sent
         * @param maxResults the host's cap on results; larger limits are cut down to it
         * @return [Valid] with the query and capped limit, or [Invalid] with [USAGE]
         */
        fun parse(input: String, maxResults: Int): MemoryToolInput {
            val node = try {
                mapper.readTree(input.ifBlank { "{}" })
            } catch (_: Exception) {
                return Invalid(USAGE)
            }
            if (node == null || !node.isObject || node.fieldNames().asSequence().any { it !in FIELDS }) {
                return Invalid(USAGE)
            }
            val query = node.get("query")
            val limit = node.get("limit")
            if ((query != null && !query.isTextual) ||
                (limit != null && (!limit.isIntegralNumber || !limit.canConvertToLong() || limit.longValue() <= 0))
            ) return Invalid(USAGE)
            val requested = limit?.longValue() ?: Memory.DEFAULT_LIMIT.toLong()
            return Valid(query?.textValue()?.trim().orEmpty(), minOf(requested, maxResults.toLong()).toInt())
        }
    }
}

/**
 * The tool definition memory tools share: an optional `query` and an optional `limit`.
 *
 * A tool that can only list leaves `query` out, so the model is not invited to search.
 *
 * @param name the tool name the model calls
 * @param description what the tool tells the model it holds
 * @param searchable whether to offer the `query` parameter
 */
internal fun memoryToolDefinition(name: String, description: String, searchable: Boolean = true): Tool.Definition {
    val limit = Tool.Parameter.integer("limit", "Maximum number of results", required = false)
    val parameters = if (searchable) {
        listOf(Tool.Parameter.string("query", "What to recall; omit to list memories", required = false), limit)
    } else {
        listOf(limit)
    }
    return Tool.Definition(name = name, description = description, inputSchema = Tool.InputSchema.of(*parameters.toTypedArray()))
}

/**
 * Builds the text a memory reference adds to the prompt: its name, its description with the
 * memory count, then the body if there is one, then the usage notes.
 *
 * @param name the reference name
 * @param description the reference description
 * @param count how many memories the tool can reach
 * @param body the memories shown up front, without a trailing newline; empty for none
 * @param notes when to use the tool
 */
internal fun memoryReferenceText(name: String, description: String, count: Long, body: String, notes: String): String =
    buildString {
        appendLine("Reference: $name")
        appendLine("Description: $description. $count memories available.")
        if (body.isNotEmpty()) appendLine(body)
        append(notes)
    }
