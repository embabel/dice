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
import com.embabel.dice.metamodel.SweptBaselineStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Drivine/Neo4j implementation of [SweptBaselineStore]: a [MetamodelVersionStore] that also keeps a
 * durable pointer at the declaration a sweep last finished reconciling. Every schema stamp is a
 * `(:MetamodelVersion)` node.
 *
 * The write MERGEs on the natural key `(schemaName, contentHash)`, so a retry or a re-stamp of an
 * unchanged schema updates the node that's already there. That is race-free only under a uniqueness
 * constraint on the same pair of properties: without one, concurrent MERGEs all miss, all take the
 * CREATE branch, and history fills with copies of one version.
 *
 * Every statement is parameterized; nothing user-derived is interpolated into Cypher. Ordering and
 * the keyed lookup both run in the database.
 *
 * Ordered reads sort on a per-schema counter. "Most recent" in the contract means logical write
 * order, which a wall clock can't express: two saves can land in the same millisecond, and an NTP
 * correction or a failover to a differently-skewed node can make the clock run backwards between
 * them. Each schema owns a `(:MetamodelSchemaCounter)` node, and a version takes the next value off
 * it when its node is first created. `savedAt` and `savedAtEpochMillis` are informational; nothing
 * sorts on them.
 *
 * The counter is bumped in the same statement, and so the same transaction, as the MERGE that
 * creates the version, so a version node always carries its place in the order. A re-save of an
 * existing version leaves the counter and the sequence alone, which keeps the write idempotent and
 * holds an old stamp at its original position. The in-memory reference implementation behaves the
 * same way.
 *
 * Three uniqueness constraints are required, and the host declares them in a `SchemaCatalog` bean
 * (the module's `TestApplication` shows the shape):
 * - `UniquenessConstraintSpec("MetamodelVersion", listOf("schemaName", "contentHash"))` makes the
 *   version MERGE race-free, as above.
 * - `UniquenessConstraintSpec("MetamodelSchemaCounter", "schemaName")` makes the counter MERGE
 *   race-free, so one schema can only have one counter handing out numbers.
 * - `UniquenessConstraintSpec("MetamodelVersion", listOf("schemaName", "sequence"))` makes two
 *   versions sharing a position in the order unstorable, so a lost counter update fails with a
 *   constraint violation the caller can retry.
 *
 * **Retrying a failed save.** If the counter's read-modify-write ever does lose an update, the
 * second writer fails with a uniqueness-constraint violation on `(schemaName, sequence)`.
 * [saveVersion] just lets that exception propagate; it doesn't retry internally, because the
 * failure has already ended the surrounding Neo4j transaction, and a retry needs a new
 * transaction, which only the caller can open. That's safe to do: the write is an idempotent
 * upsert, so retrying a failed save never produces a duplicate or a wrong result. The drift check
 * re-stamps its schema on every pass anyway, so for that caller the next pass already is the
 * retry.
 *
 * The reconciled baseline [sweptVersion] answers is tracked apart from that write-order history, the
 * same way [InMemoryMetamodelVersionStore][com.embabel.dice.metamodel.InMemoryMetamodelVersionStore]
 * keeps a separate `swept` map: as `sweptContentHash`, a property on the schema's own
 * `(:MetamodelSchemaCounter)` node, moved only by [markSwept]. Nothing about an ordinary [saveVersion]
 * touches it, which is what makes a dry run, a scoped run, or a crash mid-sweep leave the baseline
 * exactly where it was. Tracking the pointer durably is what earns this store the right to declare
 * [SweptBaselineStore] at all: a backend that could only answer the question from write order keeps
 * the plain [MetamodelVersionStore] contract, and `DriftCheckRunner` then stays silent about a
 * baseline nobody tracks. See [SweptBaselineStore.sweptVersion] for what write order gets wrong once
 * a declaration cycles back to a stamp it already used.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 * @param clock supplies the instant a version is stamped as saved at. Injectable so a test can pin
 *   the instants of two saves.
 */
@Transactional
class DrivineMetamodelVersionStore(
    private val persistenceManager: PersistenceManager,
    private val clock: Clock = Clock.systemUTC(),
) : SweptBaselineStore {

    private val logger = LoggerFactory.getLogger(DrivineMetamodelVersionStore::class.java)

    private companion object {

        /**
         * Upsert the version node and, on first insert, give it the next number off its schema's
         * counter. One statement, so one transaction: a version node always carries its place in
         * the write order.
         *
         * `WITH n WHERE n.sequence IS NULL` separates the two halves. A re-save filters the row
         * away, so the counter stays put and the existing sequence is kept; only the content is
         * refreshed.
         *
         * `entityTypeAliases` binds null when the version declares no former names. Setting a
         * property to null removes it, so an alias-free stamp leaves a node with no such property,
         * which is what a writer from before aliases existed left.
         *
         * `SET c.lockedBy = $contentHash` writes a property nobody reads. It takes the exclusive
         * lock on the counter before `SET c.sequence = coalesce(c.sequence, 0) + 1` reads it. That
         * increment is a read-modify-write, whose textbook failure is two concurrent saves both
         * reading 5, both writing 6, and two versions claiming one position; the lock write is the
         * documented Neo4j idiom for avoiding it. This store's own tests at 12 and at 48 concurrent
         * savers could not tell the locked and unlocked statements apart, so Neo4j appears to
         * serialise the increment here anyway, and the line is cheap insurance.
         *
         * Correctness rests on the uniqueness constraint on `(schemaName, sequence)`, which makes a
         * duplicate position impossible to store. If the increment ever did lose an update (a Neo4j
         * version with different locking, a cluster, contention beyond what has been tried) the
         * second writer fails with a constraint violation and the caller retries.
         */
        private val SAVE_VERSION = """
            MERGE (n:MetamodelVersion {schemaName: ${'$'}schemaName, contentHash: ${'$'}contentHash})
            ON CREATE SET n.savedAt            = ${'$'}savedAt,
                          n.savedAtEpochMillis = ${'$'}savedAtEpochMillis
            SET n.entityTypeNames       = ${'$'}entityTypeNames,
                n.entityTypeLabels      = ${'$'}entityTypeLabels,
                n.entityTypeProperties  = ${'$'}entityTypeProperties,
                n.relationshipNames     = ${'$'}relationshipNames,
                n.entityTypeAliases     = ${'$'}entityTypeAliases
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
         * The sort key is `coalesce(n.sequence, -1)`: Neo4j sorts null as the largest value, so a
         * node that somehow has no sequence would sort to the front of a DESC order and be handed
         * back as the newest. A node with no sequence never took a place in the write order, so it
         * belongs last.
         */
        private val VERSIONS_NEWEST_FIRST = """
            MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName})
            RETURN n
            ORDER BY coalesce(n.sequence, -1) DESC
        """.trimIndent()

        /**
         * Move the reconciled baseline. MERGEs the schema's counter node in case a schema's very
         * first save is also its first sweep, though the ordinary [saveVersion] call [markSwept]
         * makes first will normally have already created it.
         */
        private val MARK_SWEPT = """
            MERGE (c:MetamodelSchemaCounter {schemaName: ${'$'}schemaName})
            SET c.sweptContentHash = ${'$'}contentHash
        """.trimIndent()

        /**
         * The reconciled baseline's content hash, or no row at all when the schema has never been
         * swept. Neo4j never stores an explicit null property, so `sweptContentHash IS NOT NULL`
         * reads as "the counter node exists and carries this property" — true only once [markSwept]
         * has run for the schema. The Cypher `WHERE` filter answers this in the database itself, so a
         * schema with no counter node at all and one whose counter exists but has never been swept
         * both come back the same way: no row.
         */
        private val SWEPT_CONTENT_HASH = """
            MATCH (c:MetamodelSchemaCounter {schemaName: ${'$'}schemaName})
            WHERE c.sweptContentHash IS NOT NULL
            RETURN c.sweptContentHash AS sweptContentHash
        """.trimIndent()
    }

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
     * Overridden to resolve a recorded hash with a single keyed `MATCH`; the interface default
     * reads the schema's whole history and filters it in memory. Both halves of the natural key are
     * in the pattern, which is what the uniqueness constraint indexes.
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
     * Moves the reconciled baseline to [version], and saves the stamp into the ordinary history on
     * the way, so a caller that only ever calls this for a brand-new declaration still gets it stored.
     * Both writes run in the one transaction, so a reader never observes the pointer moved without the
     * stamp it names being resolvable.
     */
    @Transactional
    override fun markSwept(version: MetamodelVersion) {
        saveVersion(version)
        logger.debug(
            "Marking metamodel version schemaName={} contentHash={} as the reconciled baseline",
            version.schemaName,
            version.contentHash.take(8),
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(MARK_SWEPT)
                .bind(mapOf("schemaName" to version.schemaName, "contentHash" to version.contentHash)),
        )
    }

    /**
     * Reads [markSwept]'s own pointer and resolves the hash it holds back into the stamp it names.
     * A schema no sweep has ever completed for carries no such pointer, so this answers `null`; see
     * this class's own doc for why write order cannot stand in for it.
     */
    @Transactional(readOnly = true)
    override fun sweptVersion(schemaName: String): MetamodelVersion? {
        val sweptContentHash = persistenceManager.maybeGetOne(
            QuerySpecification.withStatement(SWEPT_CONTENT_HASH)
                .bind(mapOf("schemaName" to schemaName))
                .transform(String::class.java),
        ) ?: return null
        return findVersion(schemaName, sweptContentHash)
    }

    /**
     * Run one of the version queries and turn its rows into stamps, dropping any row that won't
     * deserialize.
     *
     * A single corrupt or tampered node shouldn't take down a whole history read, so the row is
     * logged at warn and skipped. [MetamodelVersionRowMapper] throws on bad data so that this can
     * happen; the warning names the missing property or the failed integrity check, which is what
     * an operator needs to go find the node. A row that isn't even a `Map` is logged and skipped
     * the same way, naming its runtime class, so a count mismatch against what was expected still
     * shows up in the log.
     *
     * [latestVersion] deliberately keeps `LIMIT 1` out of the Cypher. If the newest node were the
     * corrupt one, a database-side limit would read it, drop it, and answer "this schema has no
     * versions", hiding the good history behind it and disagreeing with [versionHistory], whose
     * first element is meant to be the same stamp. It sorts in the database and takes the first
     * survivor here.
     */
    private fun readVersions(statement: String, bindings: Map<String, Any?>): List<MetamodelVersion> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).bind(bindings) as QuerySpecification<Any>
        return persistenceManager.query(spec).mapNotNull { row ->
            if (row !is Map<*, *>) {
                logger.warn(
                    "Skipping MetamodelVersion row: expected a Map, got {}",
                    row?.javaClass?.name ?: "null",
                )
                return@mapNotNull null
            }
            runCatching { MetamodelVersionRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelVersion row: {}", it.message) }
                .getOrNull()
        }
    }
}
