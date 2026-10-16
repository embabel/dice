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
package com.embabel.dice.storage

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.ScopedReadRules
import com.embabel.dice.storage.model.Mention
import com.embabel.dice.storage.model.PropositionNode
import com.embabel.dice.storage.model.PropositionView
import org.neo4j.driver.Value
import org.neo4j.driver.exceptions.value.ValueException
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.TypeSystem
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Runs scoped structured reads. Each read selects, ranks, limits and loads its rows in one statement,
 * and gives up after [timeout], which the repository supplies from its own setting.
 */
internal class DrivineScopedReadOperations(
    private val executor: Neo4jScopedReadExecutor,
    private val timeout: Duration,
) {
    fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (limit == 0) return emptyList()
        return rows(queryStatement(spec, clock, order, limit), timeout)
    }

    fun count(spec: ScopedReadSpec, clock: ReadClock): Long = executor.read(timeout) { tx ->
        val (statement, parameters) = countStatement(spec, clock)
        tx.run(statement, parameters).single()["count"].asLong()
    }

    fun keywordOverlap(
        spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int,
    ): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        val normalized = ScopedReadRules.keywordTokens(tokens)
        if (limit == 0 || normalized.isEmpty()) return emptyList()
        return rows(keywordStatement(spec, clock, normalized, limit), timeout)
    }

    /**
     * Runs one statement that returns each row's node as `p` and its mention nodes as `mentions`,
     * and keeps the rows in the order the statement returned them.
     */
    internal fun rows(selection: Pair<String, Map<String, Any?>>, timeout: Duration): List<Proposition> =
        executor.read(timeout) { tx ->
            val (statement, parameters) = selection
            tx.run(statement, parameters).list { proposition(it["p"], it["mentions"]) }
        }

    companion object {
        /**
         * Turns a proposition node and its mention nodes into a proposition. Mentions come back in
         * the order of the index encoded in their ids, so the list matches what was saved.
         */
        internal fun proposition(node: Value, mentions: Value): Proposition {
            val proposition = node(node.asNode())
            val sorted = mentions.asList { value -> mention(value.asNode()) }
                .sortedWith(
                    compareBy<Mention> {
                        it.id.removePrefix("${proposition.id}#").substringBefore(':').toIntOrNull() ?: Int.MAX_VALUE
                    }.thenBy { it.id },
                )
            return PropositionGraphMapper.toProposition(PropositionView(proposition, sorted))
        }

        private fun mention(n: Node) = Mention(
            id = n["id"].asString(), resolvedId = n["resolvedId"].nullableString(),
            span = n["span"].asString(), type = n["type"].asString(), role = n["role"].asString(),
        )

        private fun node(n: Node): PropositionNode {
            val created = n["created"].instant()!!
            val content = n["contentRevised"].instant() ?: created
            val metadata = n["metadataRevised"].instant() ?: created
            return PropositionNode(
                id = n["id"].asString(), contextId = n["contextId"].asString(), text = n["text"].asString(),
                confidence = n["confidence"].asDouble(), decay = n["decay"].asDouble(0.0),
                importance = n["importance"].asDouble(0.5), reasoning = n["reasoning"].nullableString(),
                grounding = n["grounding"].strings(), sourceIds = n["sourceIds"].strings(), uri = n["uri"].nullableString(),
                created = created, contentRevised = content, metadataRevised = metadata,
                lastTouched = n["lastTouched"].instant() ?: maxOf(content, metadata),
                lastAccessed = n["lastAccessed"].instant() ?: created, status = n["status"].asString("ACTIVE"),
                pinned = n["pinned"].asBoolean(false), level = n["level"].asInt(0),
                reinforceCount = n["reinforceCount"].asInt(0),
                effectiveConfidence = if (n["effectiveConfidence"].isNull) null else n["effectiveConfidence"].asDouble(),
                decayUpdatedAt = n["decayUpdatedAt"].instant(), validFrom = n["validFrom"].instant(),
                validTo = n["validTo"].instant(), invalidatedAt = n["invalidatedAt"].instant(),
                observedAt = n["observedAt"].instant(), supersedes = n["supersedes"].strings(),
                contradicts = n["contradicts"].strings(),
                metadata = n.asMap().filterKeys { it.startsWith("metadata.") }.mapKeys { it.key.removePrefix("metadata.") },
            )
        }

        /**
         * Reads a stored date as an instant. An older write path saved some dates as ISO strings, so a
         * string is read the way Cypher's `datetime(toString(x))` reads it in the filters: with its own
         * offset or zone when it has one, and as UTC when it has none.
         */
        private fun Value.instant(): Instant? = when {
            isNull -> null
            hasType(TypeSystem.getDefault().STRING()) -> isoInstant(asString())
            else -> asZonedDateTime().toInstant()
        }

        private fun isoInstant(text: String): Instant = try {
            when (val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from)) {
                is ZonedDateTime -> parsed.toInstant()
                else -> (parsed as LocalDateTime).toInstant(ZoneOffset.UTC)
            }
        } catch (e: DateTimeParseException) {
            // A driver value error, so the executor reports it as a failed scoped read like any other bad row.
            throw ValueException("Cannot read a stored date string as an ISO date-time", e)
        }

        private fun Value.nullableString(): String? = if (isNull) null else asString()
        private fun Value.strings(): List<String> = if (isNull) emptyList() else asList { it.asString() }

        internal fun queryStatement(
            spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(spec, clock)
            return """
                MATCH (p:Proposition) WHERE ${compiled.predicate}
                WITH p ORDER BY ${compiled.orderBy(order)} LIMIT ${'$'}limit
                RETURN p, [(p)-[:HAS_MENTION]->(m:Mention) | m] AS mentions
            """.trimIndent() to (compiled.parameters + ("limit" to limit))
        }

        internal fun countStatement(spec: ScopedReadSpec, clock: ReadClock): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(spec, clock)
            return """
                MATCH (p:Proposition) WHERE ${compiled.predicate}
                RETURN count(p) AS count
            """.trimIndent() to compiled.parameters
        }

        internal fun keywordStatement(
            spec: ScopedReadSpec, clock: ReadClock, normalizedTokens: List<String>, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(spec, clock)
            val order = compiled.orderBy(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC)
            return """
                MATCH (p:Proposition) WHERE ${compiled.predicate}
                WITH p, size([token IN ${'$'}tokens WHERE toLower(p.text) CONTAINS token]) AS overlap
                WHERE overlap > 0
                WITH p, overlap ORDER BY overlap DESC, $order LIMIT ${'$'}limit
                RETURN p, [(p)-[:HAS_MENTION]->(m:Mention) | m] AS mentions
            """.trimIndent() to (compiled.parameters + mapOf("tokens" to normalizedTokens, "limit" to limit))
        }
    }
}
