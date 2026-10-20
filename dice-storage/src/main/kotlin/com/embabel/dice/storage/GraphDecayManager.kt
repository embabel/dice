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
import com.embabel.dice.proposition.DecayManager
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import org.drivine.manager.PersistenceManager
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import java.time.Instant

/**
 * Graph [DecayManager]: the storage-agnostic lifecycle sweep (inherited), plus a sweep that writes each
 * `:Proposition` node's decayed `effectiveConfidence` onto the node so confidence-ranked reads can run in Neo4j.
 *
 * The sweep runs entirely in Neo4j as a `MATCH ... SET`, without loading the store, because pulling every
 * row into the JVM just to recompute one column and write it back is work the database should do itself.
 * It computes the value with the same Cypher expression the scoped reads use for live confidence, so the
 * stored column and a live read always agree with [Proposition.effectiveConfidenceAt] at the default
 * decay rate. The integration tests compare the stored column against the Kotlin method.
 *
 * The `SET` runs inside `CALL { ... } IN TRANSACTIONS` so a whole-store sweep commits in batches
 * rather than holding write locks on every `:Proposition` for one long transaction that competes with
 * concurrent saves. The trade-off is that a mid-sweep failure leaves the column partially refreshed
 * instead of all-or-nothing. That is harmless for decay, since the stale rows simply carry their old value
 * into the next sweep. It also means a sweep must run in its own implicit transaction, not inside an
 * enclosing one: `DecayManager` opens none, and callers must not wrap it in `@Transactional`.
 */
class GraphDecayManager(
    repository: PropositionRepository,
    private val persistenceManager: PersistenceManager,
) : DecayManager(repository) {

    override fun materialize(contextId: ContextId) =
        sweep("MATCH (p:Proposition) WHERE p.contextId = \$contextId", mapOf("contextId" to contextId.value))

    override fun materializeAll() = sweep("MATCH (p:Proposition)", emptyMap())

    private fun sweep(match: String, extraParams: Map<String, Any>) {
        val now = Instant.now()
        persistenceManager.execute(
            QuerySpecification
                .withStatement("$match\nCALL {\n  WITH p\n$DECAY_EXPRESSION\n} IN TRANSACTIONS OF $BATCH_ROWS ROWS")
                .bind(extraParams + mapOf("now" to now, "decayK" to DECAY_K))
        )
    }

    private companion object {
        /**
         * Decay rate the materialised column is computed at — the default `k` of
         * [Proposition.effectiveConfidenceAt], matching `PropositionQuery.decayK`. A query using a
         * different `k` falls back to live decay rather than reading this column.
         */
        private const val DECAY_K = 2.0

        /** Rows per committed batch in the `IN TRANSACTIONS` sweep. */
        private const val BATCH_ROWS = 10_000

        /**
         * The `SET` each batch runs, bound to a `MATCH ... WITH p` prefix. The new value comes from
         * [ScopedConfidenceCypher.expression], the same live confidence the scoped reads compute, so the
         * stored column decays from the later of `contentRevised` and `lastAccessed` just as the Kotlin
         * method does. That expression also copes with missing `decay`, `contentRevised` and `lastAccessed`
         * properties and with timestamps an older write path stored as ISO strings.
         */
        private val DECAY_EXPRESSION = """
            SET p.effectiveConfidence = ${ScopedConfidenceCypher.expression("p", "now", "decayK")},
              p.decayUpdatedAt = datetime(${'$'}now),
              ${Stamps.setClause("p")}
        """.trimIndent()
    }
}
