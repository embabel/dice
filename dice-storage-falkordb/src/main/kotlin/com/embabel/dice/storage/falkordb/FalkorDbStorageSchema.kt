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

import org.drivine.connection.ConnectionProvider
import org.drivine.connection.NativeSchemaCommandExecutor
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaStatement
import org.drivine.schema.UniquenessConstraintSpec
import java.time.Duration

/** The fixed identity constraints required by the two storage adapters. */
internal object FalkorDbStorageSchema {
    private val timeout = Duration.ofSeconds(10)
    private val interval = Duration.ofMillis(20)

    fun propositions(provider: ConnectionProvider) = ensure(provider, listOf(
        UniquenessConstraintSpec("DiceProposition", listOf("id")),
    ))

    fun chunks(provider: ConnectionProvider) = ensure(provider, listOf(
        UniquenessConstraintSpec("DiceChunkHash", listOf("context", "hash")),
        UniquenessConstraintSpec("DiceBookmark", listOf("context", "source")),
    ))

    private fun ensure(provider: ConnectionProvider, identities: List<UniquenessConstraintSpec>) {
        val access = FalkorDbAccess(provider, null)
        access.checkWriteAllowed()
        val grammar = provider.schemaGrammar
        val native = provider as? NativeSchemaCommandExecutor
            ?: error("FalkorDB identity constraints require native schema command support")
        fun execute(statements: List<SchemaStatement>) {
            statements.forEach { statement ->
                access.checkWriteAllowed()
                when (statement) {
                    is SchemaStatement.Cypher -> access.write(statement.statement)
                    is SchemaStatement.Native -> native.executeNativeSchemaCommand(statement.args)
                }
            }
        }
        identities.forEach { identity ->
            fun constraint() = grammar.parseConstraintRows(access.query(grammar.listConstraintsQuery()))
                .singleOrNull { grammar.matchesShape(it, identity) }
            val existingConstraint = constraint()
            if (existingConstraint == null || existingConstraint.status == "FAILED") {
                val index = RangeIndexSpec(identity.label, identity.properties)
                fun indexInfo() = access.query(
                    "CALL db.indexes() YIELD label, properties, status " +
                        "RETURN {label:label,properties:properties,status:status} AS result",
                ).singleOrNull { it["label"] == identity.label &&
                    (it["properties"] as? List<*>)?.containsAll(identity.properties) == true }
                val existingIndex = grammar.parseIndexRows(access.query(grammar.listIndexesQuery(SchemaItemKind.RANGE_INDEX)))
                    .singleOrNull { it.label == identity.label && it.kind == SchemaItemKind.RANGE_INDEX }
                try {
                    execute(grammar.createIndex(index, existingIndex))
                } catch (ex: Exception) {
                    // A lost create response is recoverable only when the required index exists.
                    val created = indexInfo()
                    if (created == null || created["status"] == "FAILED") throw ex
                }
                val indexDeadline = System.nanoTime() + timeout.toNanos()
                while (true) {
                    val status = indexInfo()?.get("status")
                    check(status != "FAILED") { "FalkorDB identity index failed for ${identity.label}" }
                    if (status == "OPERATIONAL") break
                    check(System.nanoTime() < indexDeadline) { "FalkorDB identity index did not become operational for ${identity.label}" }
                    Thread.sleep(interval.toMillis())
                }
                try {
                    execute(grammar.createConstraint(identity))
                } catch (ex: Exception) {
                    // A matching item may still be building; the readiness loop requires OPERATIONAL.
                    val created = constraint()
                    if (created == null || created.status == "FAILED") throw ex
                }
            }
            val deadline = System.nanoTime() + timeout.toNanos()
            while (true) {
                val status = constraint()?.status
                check(status != "FAILED") { "FalkorDB identity constraint failed for ${identity.label}" }
                if (status == "OPERATIONAL") break
                check(System.nanoTime() < deadline) { "FalkorDB identity constraint did not become operational for ${identity.label}" }
                Thread.sleep(interval.toMillis())
            }
        }
    }
}
