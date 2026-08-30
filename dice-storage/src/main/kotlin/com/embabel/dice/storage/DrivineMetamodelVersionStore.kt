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

import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Drivine / Neo4j implementation of [MetamodelVersionStore]: keeps every schema stamp as a
 * `(:MetamodelVersion)` node.
 *
 * The write MERGEs on the natural key `(schemaName, contentHash)`, so a retry or a re-stamp of an
 * unchanged schema updates the node that's already there instead of adding a duplicate. That's only
 * race-free under a uniqueness constraint on the same pair of properties — without one, concurrent
 * MERGEs all miss, all take the CREATE branch, and history fills with copies of one version.
 *
 * Every statement is parameterized — nothing user-derived is ever interpolated into Cypher. Ordering
 * and the keyed lookup both run in the database rather than over an in-memory list.
 *
 * **Order comes from a counter, not from a clock.** The contract says "most recent" means logical
 * write order, and a wall clock can't express that: two saves can land in the same millisecond, and
 * an NTP correction or a failover to a differently-skewed node can make the clock run *backwards*
 * between them. Either way the newest stamp stops being the one that comes back. So each schema owns
 * a `(:MetamodelSchemaCounter)` node, and a version gets the next value off it when — and only
 * when — its node is first created. That sequence is what every ordered read sorts on. `savedAt` and
 * `savedAtEpochMillis` are still written, but they are now informational metadata: useful when you
 * are staring at a node wondering when it landed, and sorted on by nothing.
 *
 * The counter is bumped in the same statement, and so the same transaction, as the MERGE that
 * creates the version — there is no window in which a version node exists without its place in the
 * order. A re-save of a version that already exists neither bumps the counter nor reassigns the
 * sequence, which is what keeps an idempotent write idempotent and stops an old stamp jumping to the
 * head of the history. That mirrors the in-memory reference implementation, where a re-save keeps
 * its original position in the list.
 *
 * **Three constraints are required**, and all are the host's job to declare in a `SchemaCatalog`
 * bean (the module's `TestApplication` shows the shape):
 * - `UniquenessConstraintSpec("MetamodelVersion", listOf("schemaName", "contentHash"))` — makes the
 *   version MERGE race-free, as above.
 * - `UniquenessConstraintSpec("MetamodelSchemaCounter", "schemaName")` — makes the counter MERGE
 *   race-free, so a schema can't end up with two counters handing out the same numbers.
 * - `UniquenessConstraintSpec("MetamodelVersion", listOf("schemaName", "sequence"))` — makes two
 *   versions sharing a position in the order unstorable, so a lost counter update fails loudly and
 *   retryably instead of quietly making "newest first" arbitrary again.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 * @param clock supplies the instant a version is stamped as saved at. Injectable so a test can place
 *   two saves at instants it chooses rather than at whatever the wall clock happened to say.
 */
open class DrivineMetamodelVersionStore(
    private val persistenceManager: PersistenceManager,
    private val clock: Clock = Clock.systemUTC(),
) : MetamodelVersionStore {

    private val logger = LoggerFactory.getLogger(DrivineMetamodelVersionStore::class.java)

    private companion object {

        /**
         * Upsert the version node and, if this is the first time we've seen it, give it the next
         * number off its schema's counter. One statement, so one transaction: a version node never
         * exists without its place in the write order.
         *
         * The two halves are separated by `WITH n WHERE n.sequence IS NULL`. On a re-save that
         * filters the row away, so the counter is never bumped and the existing sequence is never
         * reassigned — the content is refreshed and the version keeps the position it has always
         * had.
         *
         * `SET c.lockedBy = $contentHash` writes a property nobody reads, to take the exclusive lock
         * on the counter before the increment below reads it. On its own,
         * `SET c.sequence = coalesce(c.sequence, 0) + 1` is a read-modify-write, and the textbook
         * failure is two concurrent saves both reading 5, both writing 6, and two versions claiming
         * one position. This is the documented Neo4j idiom for avoiding that.
         *
         * Being honest about how well that's established: at 12 and at 48 concurrent savers this
         * store's own tests could not tell the locked and unlocked versions apart, so Neo4j appears
         * to serialise the increment on its own here. The line is kept as cheap insurance, not
         * because a failing test demanded it — don't read it as load-bearing.
         *
         * What *is* load-bearing is the uniqueness constraint on `(schemaName, sequence)`. It makes
         * a duplicate position impossible to store rather than merely unlikely: if the increment
         * ever did lose an update — a Neo4j version with different locking, a cluster, contention
         * beyond what's been tried — the second writer fails loudly with a constraint violation and
         * the caller retries, instead of silently corrupting the order. Correctness rests on that,
         * not on a lock this code can't verify.
         */
        private val SAVE_VERSION = """
            MERGE (n:MetamodelVersion {schemaName: ${'$'}schemaName, contentHash: ${'$'}contentHash})
            ON CREATE SET n.savedAt            = ${'$'}savedAt,
                          n.savedAtEpochMillis = ${'$'}savedAtEpochMillis
            SET n.entityTypeNames       = ${'$'}entityTypeNames,
                n.entityTypeLabels      = ${'$'}entityTypeLabels,
                n.entityTypeProperties  = ${'$'}entityTypeProperties,
                n.relationshipNames     = ${'$'}relationshipNames
            WITH n
            WHERE n.sequence IS NULL
            MERGE (c:MetamodelSchemaCounter {schemaName: ${'$'}schemaName})
            SET c.lockedBy = ${'$'}contentHash
            WITH n, c
            SET c.sequence = coalesce(c.sequence, 0) + 1
            WITH n, c
            SET n.sequence = c.sequence
        """.trimIndent()

        /**
         * Every stamp for one schema, newest first.
         *
         * `coalesce(n.sequence, -1)` rather than a bare `n.sequence` because Neo4j sorts null as the
         * *largest* value, so a node that somehow has no sequence would sort to the front of a DESC
         * order and be handed back as the newest. A node with no sequence never took a place in the
         * write order at all, so last is the honest position for it.
         */
        private val VERSIONS_NEWEST_FIRST = """
            MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName})
            RETURN n
            ORDER BY coalesce(n.sequence, -1) DESC
        """.trimIndent()
    }

    @Transactional
    override fun saveVersion(version: MetamodelVersion) {
        logger.debug(
            "Saving metamodel version schemaName={} contentHash={}",
            version.schemaName,
            version.contentHash.take(8),
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(SAVE_VERSION)
                .bind(MetamodelVersionRowMapper.bindMap(version, clock.instant())),
        )
    }

    @Transactional(readOnly = true)
    override fun latestVersion(schemaName: String): MetamodelVersion? =
        readVersions(VERSIONS_NEWEST_FIRST, mapOf("schemaName" to schemaName)).firstOrNull()

    @Transactional(readOnly = true)
    override fun versionHistory(schemaName: String): List<MetamodelVersion> =
        readVersions(VERSIONS_NEWEST_FIRST, mapOf("schemaName" to schemaName))

    /**
     * Overridden so resolving a recorded hash is a single keyed `MATCH` rather than a read of the
     * schema's whole history followed by an in-memory filter. Both halves of the natural key are in
     * the pattern, which is exactly what the uniqueness constraint indexes.
     */
    @Transactional(readOnly = true)
    override fun findVersion(schemaName: String, contentHash: String): MetamodelVersion? = readVersions(
        """
        MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName, contentHash: ${'$'}contentHash})
        RETURN n
        LIMIT 1
        """.trimIndent(),
        mapOf("schemaName" to schemaName, "contentHash" to contentHash),
    ).firstOrNull()

    /**
     * Run one of the version queries and turn its rows into stamps, dropping any row that won't
     * deserialize.
     *
     * A single corrupt or tampered node shouldn't take down a whole history read, so it's warned
     * about and skipped — see [MetamodelVersionRowMapper], which throws rather than inventing
     * defaults precisely so this can happen. The warning names the property or the failed integrity
     * check, which is what an operator needs to go find the node.
     *
     * Note what [latestVersion] does *not* do: push a `LIMIT 1` into Cypher. If the newest node were
     * the corrupt one, that would read it, drop it, and answer "this schema has no versions" —
     * hiding the perfectly good history behind it, and disagreeing with [versionHistory], whose
     * first element is meant to be the same stamp. It sorts in the database and takes the first
     * survivor instead, so a bad node hides only itself.
     */
    private fun readVersions(statement: String, bindings: Map<String, Any?>): List<MetamodelVersion> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).bind(bindings) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>().mapNotNull { row ->
            runCatching { MetamodelVersionRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelVersion row: {}", it.message) }
                .getOrNull()
        }
    }
}
