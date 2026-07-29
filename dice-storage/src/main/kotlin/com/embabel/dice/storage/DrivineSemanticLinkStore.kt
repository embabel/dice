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

import com.embabel.agent.core.ContextId
import com.embabel.dice.report.LinkKind
import com.embabel.dice.report.ReviewStatus
import com.embabel.dice.report.SemanticLink
import com.embabel.dice.report.SemanticLinkStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Drivine / Neo4j [SemanticLinkStore]: persists reviewable semantic links as flat
 * `(:SemanticLink)` nodes so review decisions survive process restarts.
 *
 * A natural link id is unique within its context; both values form the persistence key.
 * Every value, including free-text rationale, travels as a query parameter. Rediscovery
 * refreshes the evidence properties while leaving an existing review status untouched.
 * Reads are corrupt-row-tolerant: an unreadable node is logged and skipped.
 */
@ApiStatus.Experimental
@Transactional
class DrivineSemanticLinkStore(
    private val persistenceManager: PersistenceManager,
) : SemanticLinkStore {

    private val logger = LoggerFactory.getLogger(DrivineSemanticLinkStore::class.java)

    @ApiStatus.Experimental
    @Transactional
    override fun record(contextId: ContextId, link: SemanticLink): SemanticLink {
        val id = idOf(link)
        val now = Instant.now().toString()
        logger.debug("Recording semantic link {} for context {}", id, contextId.value)

        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (l:SemanticLink {id: ${'$'}id, contextId: ${'$'}contextId})
                ON CREATE SET l += ${'$'}props,
                              l.reviewStatus = ${'$'}status,
                              l.createdAt = ${'$'}now
                ON MATCH SET l += ${'$'}props,
                             l.revisedAt = ${'$'}now
                """.trimIndent(),
            ).bind(
                mapOf(
                    "id" to id,
                    "contextId" to contextId.value,
                    "props" to bindProperties(link),
                    "status" to link.reviewStatus.name,
                    "now" to now,
                ),
            ),
        )

        return checkNotNull(findById(contextId, id)) {
            "SemanticLink $id was not readable after it was recorded"
        }
    }

    @ApiStatus.Experimental
    @Transactional(readOnly = true)
    override fun find(contextId: ContextId, status: ReviewStatus?): List<SemanticLink> {
        val statement = if (status == null) {
            """
            MATCH (l:SemanticLink {contextId: ${'$'}contextId})
            RETURN l
            ORDER BY l.id
            """.trimIndent()
        } else {
            """
            MATCH (l:SemanticLink {contextId: ${'$'}contextId})
            WHERE l.reviewStatus = ${'$'}status
            RETURN l
            ORDER BY l.id
            """.trimIndent()
        }
        val parameters = buildMap<String, Any?> {
            put("contextId", contextId.value)
            status?.let { put("status", it.name) }
        }
        return readableLinks(statement, parameters)
    }

    @ApiStatus.Experimental
    @Transactional
    override fun updateReviewStatus(
        contextId: ContextId,
        id: String,
        status: ReviewStatus,
    ): SemanticLink? {
        logger.debug(
            "Updating semantic link {} to review status {} for context {}",
            id,
            status,
            contextId.value,
        )
        val rows = queryRows(
            """
            MATCH (l:SemanticLink {id: ${'$'}id, contextId: ${'$'}contextId})
            SET l.reviewStatus = ${'$'}status,
                l.revisedAt = ${'$'}now
            RETURN l
            """.trimIndent(),
            mapOf(
                "id" to id,
                "contextId" to contextId.value,
                "status" to status.name,
                "now" to Instant.now().toString(),
            ),
        )
        return rows.firstNotNullOfOrNull(::readableLink)
    }

    private fun bindProperties(link: SemanticLink): Map<String, Any?> =
        mapOf(
            "sourceEntityId" to link.sourceEntityId,
            "targetEntityId" to link.targetEntityId,
            "kind" to link.kind.name,
            "confidence" to link.confidence,
            "rationale" to link.rationale,
            "connectingEntityIds" to link.connectingEntityIds,
            "sourcePropositionIds" to link.sourcePropositionIds,
        )

    private fun findById(contextId: ContextId, id: String): SemanticLink? =
        readableLinks(
            """
            MATCH (l:SemanticLink {id: ${'$'}id, contextId: ${'$'}contextId})
            RETURN l
            """.trimIndent(),
            mapOf("id" to id, "contextId" to contextId.value),
        ).firstOrNull()

    private fun readableLinks(statement: String, parameters: Map<String, Any?>): List<SemanticLink> =
        queryRows(statement, parameters).mapNotNull(::readableLink)

    private fun readableLink(row: Map<*, *>): SemanticLink? =
        runCatching {
            SemanticLink(
                sourceEntityId = row.requiredString("sourceEntityId"),
                targetEntityId = row.requiredString("targetEntityId"),
                connectingEntityIds = row.requiredStringList("connectingEntityIds"),
                kind = LinkKind.valueOf(row.requiredString("kind")),
                sourcePropositionIds = row.requiredStringList("sourcePropositionIds"),
                reviewStatus = ReviewStatus.valueOf(row.requiredString("reviewStatus")),
                confidence = row.requiredNumber("confidence").toDouble(),
                rationale = row.optionalString("rationale"),
            )
        }.onFailure {
            logger.warn("Skipping unreadable SemanticLink row: {}", it.message)
        }.getOrNull()

    private fun Map<*, *>.requiredString(property: String): String =
        (this[property] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: error("SemanticLink row missing or invalid $property")

    private fun Map<*, *>.requiredStringList(property: String): List<String> {
        val values = this[property] as? List<*>
            ?: error("SemanticLink row missing or invalid $property")
        return values.map { value ->
            value as? String ?: error("SemanticLink row has non-string value in $property")
        }
    }

    private fun Map<*, *>.requiredNumber(property: String): Number =
        this[property] as? Number
            ?: error("SemanticLink row missing or invalid $property")

    private fun Map<*, *>.optionalString(property: String): String? =
        when (val value = this[property]) {
            null -> null
            is String -> value
            else -> error("SemanticLink row has invalid $property")
        }

    private fun queryRows(statement: String, parameters: Map<String, Any?>): List<Map<*, *>> {
        val specification = QuerySpecification.withStatement(statement).let {
            if (parameters.isEmpty()) it else it.bind(parameters)
        }
        return persistenceManager.query(specification).filterIsInstance<Map<*, *>>()
    }
}
