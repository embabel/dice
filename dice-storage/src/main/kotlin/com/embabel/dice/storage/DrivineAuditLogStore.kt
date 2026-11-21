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

import com.embabel.dice.audit.AbstractAuditLog
import com.embabel.dice.audit.AuditActorRef
import com.embabel.dice.audit.AuditAttribution
import com.embabel.dice.audit.AuditCoverageException
import com.embabel.dice.audit.AuditEntry
import com.embabel.dice.audit.AuditKind
import com.embabel.dice.audit.AuditRecord
import com.embabel.dice.audit.AuditTransactionParticipation
import com.embabel.dice.audit.AuditedProposition
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.manager.PersistenceManager
import org.jetbrains.annotations.ApiStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * Durable audit for a Drivine proposition repository. Declare [AuditLogSchema] as a
 * [DiceStorageSchema] and use the same datasource and transaction manager as the repository.
 *
 * Every mutation locks the context header before reading it. Sequence assignment, baseline
 * installation, and erasure then share that transaction with the proposition write. On SQLite the
 * host configures the usual IMMEDIATE transaction mode. Replay takes the same fence on an
 * existing header to keep the baseline and records consistent with concurrent erasure.
 */
@ApiStatus.Experimental
class DrivineAuditLogStore @JvmOverloads constructor(
    private val persistenceManager: PersistenceManager,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC(),
) : AbstractAuditLog() {

    override val transactionParticipation = AuditTransactionParticipation.JOINS_HOST_TRANSACTION
    private val transactions = TransactionTemplate(transactionManager)

    override fun initializeBaseline(contextId: String, cutover: Instant, completeSnapshot: Collection<Proposition>) {
        val claims = baselineOf(contextId, completeSnapshot)
        require(cutover <= clock.instant()) { "Audit cutover cannot be in the future" }
        locked(contextId) { state ->
            check(state.cutover == null) { "Audit baseline is already initialized" }
            check(state.lastRecordedAt == null || cutover >= state.lastRecordedAt) {
                "Audit cutover cannot precede existing records"
            }
            update(
                """
                MATCH (c:$CONTEXT {contextId: ${'$'}contextId})
                SET c.cutover = ${'$'}cutover, c.baseline = ${'$'}baseline, c.baselineSize = ${'$'}size,
                    c.baselineSequence = ${'$'}sequence, c.invalid = false
                """.trimIndent(),
                bindings(contextId) + mapOf(
                    "cutover" to cutover.toString(),
                    "baseline" to mapper.writeValueAsString(claims),
                    "size" to claims.size,
                    "sequence" to state.sequence,
                ),
            )
        }
    }

    override fun append(contextId: String, entry: AuditEntry): AuditRecord {
        requireAppend(contextId, entry)
        return locked(contextId) { state -> appendLocked(contextId, state, entry) }
    }

    override fun eraseContext(contextId: String, actor: AuditActorRef?): AuditRecord = locked(contextId) { state ->
        if (state.cutover == null && state.sequence > 0 && state.lastErasure == state.sequence) {
            return@locked records(contextId, state.sequence - 1, 1).single().also {
                check(it.kind == AuditKind.ERASURE)
            }
        }
        val entry = AuditEntry(
            AuditKind.ERASURE,
            attribution = AuditAttribution(actor),
            erasedRecordCount = state.sequence - state.lastErasure,
            erasedBaselineCount = state.baselineSize,
        )
        val parameters = bindings(contextId)
        update("MATCH (n:$RECORD {contextId: ${'$'}contextId}) DELETE n", parameters)
        val tombstone = appendLocked(contextId, state, entry)
        update(
            """
            MATCH (c:$CONTEXT {contextId: ${'$'}contextId})
            SET c.cutover = null, c.baseline = null, c.baselineSize = 0, c.baselineSequence = 0,
                c.invalid = false, c.lastErasure = ${'$'}sequence
            """.trimIndent(),
            parameters + ("sequence" to tombstone.sequence),
        )
        tombstone
    }

    override fun <T> duringWrite(contextId: String, action: Supplier<T>): T =
        locked(contextId) { action.get() }

    override fun priorCanonicalId(contextId: String, exactText: String): String? {
        requireContext(contextId)
        return persistenceManager.maybeGetOne(
            cypherQuery("MATCH (p:Proposition {contextId: ${'$'}contextId, text: ${'$'}text}) RETURN p.id AS id LIMIT 1")
                .bind(bindings(contextId) + ("text" to exactText))
                .transform(String::class.java),
        )
    }

    override fun invalidateCoverage(contextId: String) {
        locked(contextId) {
            update("MATCH (c:$CONTEXT {contextId: ${'$'}contextId}) SET c.invalid = true", bindings(contextId))
        }
    }

    override fun history(contextId: String, propositionId: String): List<AuditRecord> {
        requireContext(contextId)
        require(propositionId.isNotBlank())
        return readRecords(
            """
            MATCH (n:$RECORD {contextId: ${'$'}contextId, propositionId: ${'$'}proposition})
            $RECORD_PROJECTION
            ORDER BY n.sequence
            """.trimIndent(),
            bindings(contextId) + ("proposition" to propositionId),
            contextId,
        )
    }

    override fun query(contextId: String, afterSequence: Long, limit: Int): List<AuditRecord> {
        requirePage(contextId, afterSequence, limit)
        return records(contextId, afterSequence, limit)
    }

    override fun timeline(contextId: String): AuditTimeline = transaction {
        val state = lock(contextId, create = false)
        if (state == null || state.cutover == null) {
            return@transaction AuditTimeline(null, null, clock.instant(), 0, 0, emptyList(), emptyList())
        }
        val baseline: List<AuditedProposition> = decode(requireNotNull(state.baseline))
        if (baseline.size.toLong() != state.baselineSize) throw AuditCoverageException()
        AuditTimeline(
            state.cutover, state.cutover.takeIf { state.invalid }, recordedAt(state),
            state.baselineSequence, state.sequence, baseline, records(contextId, state.baselineSequence, null),
        )
    }

    private fun appendLocked(contextId: String, state: ContextState, entry: AuditEntry): AuditRecord {
        val sequence = Math.addExact(state.sequence, 1)
        val time = recordedAt(state)
        val params = bindings(contextId) + mapOf(
            "sequence" to sequence, "recordedAt" to time.toString(),
            "propositionId" to entry.propositionId, "payload" to mapper.writeValueAsString(entry),
        )
        update(
            """
            MATCH (c:$CONTEXT {contextId: ${'$'}contextId})
            SET c.sequence = ${'$'}sequence, c.lastRecordedAt = ${'$'}recordedAt
            CREATE (n:$RECORD {
                contextId: ${'$'}contextId, sequence: ${'$'}sequence, recordedAt: ${'$'}recordedAt, payload: ${'$'}payload
            })
            SET n.propositionId = ${'$'}propositionId
            """.trimIndent(),
            params,
        )
        return AuditRecord(contextId, sequence, time, entry)
    }

    private fun recordedAt(state: ContextState): Instant =
        maxOf(clock.instant(), state.cutover ?: Instant.MIN, state.lastRecordedAt ?: Instant.MIN)

    private fun records(contextId: String, after: Long, limit: Int?): List<AuditRecord> {
        val query = """
            MATCH (n:$RECORD {contextId: ${'$'}contextId})
            WHERE n.sequence > ${'$'}after
            $RECORD_PROJECTION
            ORDER BY n.sequence
        """.trimIndent()
        val params = bindings(contextId) + ("after" to after)
        return if (limit == null) readRecords(query, params, contextId)
        else readRecords("$query LIMIT ${'$'}limit", params + ("limit" to limit), contextId)
    }

    private fun readRecords(query: String, params: Map<String, Any?>, contextId: String): List<AuditRecord> =
        rows(query, params).map { row ->
            val storedContext = row["contextId"] as String
            if (storedContext != contextId) throw AuditCoverageException()
            AuditRecord(
                storedContext, row.long("sequence"), Instant.parse(row["recordedAt"] as String),
                decode(row["payload"] as String),
            ).also { record ->
                if (record.propositionId != row["propositionId"] ||
                    (record.before != null && record.before?.contextId != contextId) ||
                    (record.after != null && record.after?.contextId != contextId)
                ) throw AuditCoverageException()
            }
        }

    private fun <T> locked(contextId: String, action: (ContextState) -> T): T {
        requireContext(contextId)
        return transaction { action(checkNotNull(lock(contextId, create = true))) }
    }

    private fun lock(contextId: String, create: Boolean): ContextState? {
        val pattern = "(c:$CONTEXT {contextId: ${'$'}contextId})"
        val match = if (create) {
            "MERGE $pattern ON CREATE SET c.sequence = 0, c.baselineSequence = 0, c.baselineSize = 0, c.lastErasure = 0"
        } else "MATCH $pattern"
        return rows(
            """
            $match
            SET c.lockToken = ${'$'}lockToken
            RETURN {sequence: c.sequence, cutover: c.cutover, baseline: c.baseline, baselineSize: c.baselineSize,
                baselineSequence: c.baselineSequence, lastErasure: c.lastErasure, invalid: c.invalid,
                lastRecordedAt: c.lastRecordedAt}
            """.trimIndent(),
            bindings(contextId) + ("lockToken" to UUID.randomUUID().toString()),
        ).singleOrNull()?.let { row ->
            ContextState(
                row.long("sequence"), row.instant("cutover"), row["baseline"] as String?,
                row.long("baselineSize"), row.long("baselineSequence"), row.long("lastErasure"),
                row["invalid"] == true || (row["invalid"] as? Number)?.toInt() == 1,
                row.instant("lastRecordedAt"),
            )
        }
    }

    private fun <T> transaction(action: () -> T): T =
        checkNotNull(transactions.execute { TransactionResult(action()) }).value

    private fun update(query: String, params: Map<String, Any?>) =
        persistenceManager.execute(cypherQuery(query).bind(params))

    private fun rows(query: String, params: Map<String, Any?>): List<Map<*, *>> =
        persistenceManager.query(cypherQuery(query).bind(params)).map { it as Map<*, *> }

    private fun bindings(contextId: String) = mapOf("contextId" to contextId)

    private fun Map<*, *>.long(field: String): Long = (get(field) as Number).toLong()

    private fun Map<*, *>.instant(field: String): Instant? = (get(field) as String?)?.let(Instant::parse)

    private class TransactionResult<T>(val value: T)

    private data class ContextState(
        val sequence: Long,
        val cutover: Instant?,
        val baseline: String?,
        val baselineSize: Long,
        val baselineSequence: Long,
        val lastErasure: Long,
        val invalid: Boolean,
        val lastRecordedAt: Instant?,
    )

    private companion object {
        const val CONTEXT = AuditLogSchema.CONTEXT
        const val RECORD = AuditLogSchema.RECORD

        const val RECORD_PROJECTION =
            "RETURN {contextId: n.contextId, sequence: n.sequence, recordedAt: n.recordedAt, " +
                "propositionId: n.propositionId, payload: n.payload}"

        val mapper = jacksonObjectMapper().findAndRegisterModules()
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)

        inline fun <reified T> decode(payload: String): T =
            try { mapper.readValue<T>(payload) } catch (_: Exception) { throw AuditCoverageException() }
    }
}
