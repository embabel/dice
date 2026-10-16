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

import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.storage.model.Mention
import com.embabel.dice.storage.model.PropositionNode
import com.embabel.dice.storage.model.PropositionView
import org.neo4j.driver.Transaction
import org.neo4j.driver.Value
import org.neo4j.driver.types.Node
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Structured scoped reads have a five-second database allowance. Selection, ranking and limit
 * execute in Neo4j; only those selected composite keys are hydrated in the same transaction.
 */
internal class DrivineScopedReadOperations(private val executor: Neo4jScopedReadExecutor) {
    fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (limit == 0) return emptyList()
        return selected(scope, spec, queryStatement(scope, spec, order, limit))
    }

    fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long = executor.read(DATABASE_TIMEOUT) { tx ->
        val (statement, parameters) = countStatement(scope, spec)
        tx.run(statement, parameters).single()["count"].asLong()
    }

    fun keywordOverlap(
        scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int,
    ): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        val normalized = tokens.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
        if (limit == 0 || normalized.isEmpty()) return emptyList()
        return selected(scope, spec, keywordStatement(scope, spec, normalized, limit))
    }

    private fun selected(
        scope: ContextReadScope, spec: ScopedReadSpec, selection: Pair<String, Map<String, Any?>>,
    ): List<Proposition> = executor.read(DATABASE_TIMEOUT) { tx ->
        val (statement, parameters) = selection
        val keys = tx.run(statement, parameters).list {
            PropositionKey(it["contextId"].asString(), it["id"].asString())
        }
        hydrate(tx, scope, spec, keys)
    }

    /** Recheck every grant and predicate before returning any selected row. Never hydrate by ID alone. */
    fun hydrate(
        tx: Transaction, scope: ContextReadScope, spec: ScopedReadSpec, orderedKeys: List<PropositionKey>,
    ): List<Proposition> {
        if (orderedKeys.isEmpty()) return emptyList()
        if (orderedKeys.distinct().size != orderedKeys.size || orderedKeys.any { it.contextIdValue !in scope.contextIdValues }) {
            throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        }
        val (statement, parameters) = hydrationStatement(scope, spec, orderedKeys)
        val rows = tx.run(statement, parameters).list { record ->
            val proposition = node(record["p"].asNode())
            val mentions = record["mentions"].asList { value -> mention(value.asNode()) }
                .sortedWith(
                    compareBy<Mention> {
                        it.id.removePrefix("${proposition.id}#").substringBefore(':').toIntOrNull() ?: Int.MAX_VALUE
                    }.thenBy { it.id },
                )
            PropositionGraphMapper.toProposition(PropositionView(proposition, mentions))
        }
        val byKey = rows.associateBy { PropositionKey(it.contextId.value, it.id) }
        if (rows.size != orderedKeys.size || byKey.size != orderedKeys.size || orderedKeys.any { it !in byKey }) {
            throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        }
        return orderedKeys.map { byKey.getValue(it) }
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

    private fun Value.instant(): Instant? = if (isNull) null else asZonedDateTime().toInstant()
    private fun Value.nullableString(): String? = if (isNull) null else asString()
    private fun Value.strings(): List<String> = if (isNull) emptyList() else asList { it.asString() }

    companion object {
        private val DATABASE_TIMEOUT: Duration = Duration.ofSeconds(5)

        internal fun queryStatement(
            scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            return ("MATCH (p:Proposition) WHERE ${compiled.predicate} " +
                "RETURN p.contextId AS contextId, p.id AS id " +
                "ORDER BY ${ScopedReadCypher.order(order, spec)} LIMIT $" + "limit") to
                (compiled.parameters + ("limit" to limit))
        }

        internal fun hydrationStatement(
            scope: ContextReadScope, spec: ScopedReadSpec, orderedKeys: List<PropositionKey>,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            val parameters = compiled.parameters + ("selectedKeys" to orderedKeys.map {
                mapOf("contextId" to it.contextIdValue, "propositionId" to it.propositionId)
            })
            return ("UNWIND $" + "selectedKeys AS key " +
                "MATCH (p:Proposition {contextId: key.contextId, id: key.propositionId}) " +
                "WHERE ${compiled.predicate} OPTIONAL MATCH (p)-[:HAS_MENTION]->(m:Mention) " +
                "RETURN p, collect(m) AS mentions") to parameters
        }

        internal fun countStatement(scope: ContextReadScope, spec: ScopedReadSpec): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            return "MATCH (p:Proposition) WHERE ${compiled.predicate} RETURN count(p) AS count" to compiled.parameters
        }

        internal fun keywordStatement(
            scope: ContextReadScope, spec: ScopedReadSpec, normalizedTokens: List<String>, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            return ("MATCH (p:Proposition) WHERE ${compiled.predicate} " +
                "WITH p, size([token IN $" + "tokens WHERE toLower(p.text) CONTAINS token]) AS overlap " +
                "WHERE overlap > 0 RETURN p.contextId AS contextId, p.id AS id " +
                "ORDER BY overlap DESC, ${ScopedReadCypher.order(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, spec)} " +
                "LIMIT $" + "limit") to (compiled.parameters + mapOf("tokens" to normalizedTokens, "limit" to limit))
        }
    }
}
