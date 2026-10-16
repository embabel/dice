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
package com.embabel.dice.storage.falkordb

import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.temporal.TemporalMetadata
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.query.QuerySpecification
import org.drivine.transaction.TransactionContextHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.Locale

internal object FalkorDbPropositionCodec {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .addMixIn(TemporalMetadata::class.java, TemporalMetadataJson::class.java)

    @JsonIgnoreProperties("isCurrent")
    private abstract class TemporalMetadataJson

    fun encode(proposition: Proposition): String = mapper.writeValueAsString(proposition)
    fun decode(json: String): Proposition = mapper.readValue(json)

    // ASCII hex preserves JVM UTF-16 ordering, including supplementary characters.
    fun orderKey(value: String): String = value.map { it.code.toString(16).padStart(4, '0') }.joinToString("")

    fun foldCase(value: String): String = buildString {
        value.codePoints().forEach { appendCodePoint(Character.toLowerCase(Character.toUpperCase(it))) }
    }

    fun properties(p: Proposition, version: String): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to p.id,
        "context" to p.contextId.value,
        "contextOrder" to orderKey(p.contextId.value),
        "idOrder" to orderKey(p.id),
        "aggregate" to encode(p),
        "version" to version,
        "text" to p.text.lowercase(Locale.ROOT),
        "legacyText" to foldCase(p.text),
        "entities" to p.mentions.mapNotNull { it.resolvedId }.distinct(),
        "grounding" to p.grounding,
        "confidence" to p.confidence,
        "decay" to p.decay,
        "importance" to p.importance,
        "status" to p.status.name,
        "level" to p.level,
        "reinforceCount" to p.reinforceCount,
        "pinned" to p.pinned,
        "trust" to (p.metadata[DiceMetadataKeys.TRUST_SCORE] as? Number)?.toDouble()?.takeIf { it.isFinite() },
    ).apply {
        instant("created", p.created)
        instant("revised", p.lastTouched)
        instant("accessed", p.lastAccessed)
        instant("anchor", maxOf(p.contentRevised, p.lastAccessed))
        instant("validFrom", p.temporal?.validFrom)
        instant("validTo", p.temporal?.validTo)
        instant("invalidated", p.temporal?.invalidatedAt)
    }

    private fun MutableMap<String, Any?>.instant(name: String, value: Instant?) {
        put(name + "S", value?.epochSecond)
        put(name + "N", value?.nano)
    }
}

internal class FalkorDbAccess(
    private val provider: ConnectionProvider,
    private val transactions: TransactionContextHolder?,
) {
    init {
        require(provider.type == DatabaseType.FALKORDB) { "A FalkorDB connection provider is required" }
    }

    fun isConstraintViolation(error: Throwable): Boolean = provider.schemaGrammar.isConstraintViolation(error)

    fun checkWriteAllowed() {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && transactions?.currentTransaction == null) {
            "FalkorDB writes cannot join caller transactions; each mutation is one atomic statement"
        }
    }

    fun query(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> {
        val connection = provider.connect()
        var failure: Throwable? = null
        try {
            @Suppress("UNCHECKED_CAST")
            return connection.query(QuerySpecification.withStatement(statement).bind(parameters).transform(Map::class.java))
                as List<Map<String, Any?>>
        } catch (ex: Throwable) {
            failure = ex
            throw ex
        } finally {
            connection.release(failure)
        }
    }

    fun write(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> {
        checkWriteAllowed()
        return query(statement, parameters)
    }
}
