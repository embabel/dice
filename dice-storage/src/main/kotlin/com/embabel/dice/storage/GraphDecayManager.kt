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
import org.drivine.query.QuerySpecification
import java.time.Instant

/**
 * Graph [DecayManager]: the storage-agnostic lifecycle sweep (inherited), plus materialisation of the
 * decayed `effectiveConfidence` onto `:Proposition` nodes so confidence-ranked reads push into Neo4j.
 *
 * Materialisation runs entirely in Neo4j — a `MATCH … SET` sweep, no store load — because pulling
 * the whole store into the JVM just to recompute a column and write it back is exactly the kind of
 * work a durable backend should push down. The `CASE` in [DECAY_EXPRESSION] is a faithful transcript
 * of [Proposition.effectiveConfidenceAt] at the default decay rate; the parity integration test
 * pins the two together so the Cypher can't drift from the Kotlin definition.
 *
 * The `SET` is wrapped in `CALL { … } IN TRANSACTIONS` so a whole-store sweep commits in batches
 * rather than holding write locks on every `:Proposition` for one long transaction that competes with
 * concurrent saves. The trade-off is that a mid-sweep failure leaves the column partially refreshed
 * instead of all-or-nothing — harmless for decay, since the stale rows simply carry their old value
 * into the next sweep. (This also means a sweep must run in its own implicit transaction, not inside
 * an enclosing one — `DecayManager` opens none, and callers must not wrap it in `@Transactional`.)
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
                .bind(extraParams + mapOf("now" to now))
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
         * Transcript of [Proposition.effectiveConfidenceAt] (at k = [DECAY_K]) as a Cypher `SET`.
         * Bound to a `MATCH … WITH p` prefix. `age` is whole elapsed days floored and clamped at 0 —
         * `Duration.between(from, to).toDays()` is `(toEpochSecond - fromEpochSecond) / 86400`, so
         * `floor(epochSeconds diff / 86400)` matches it except within a sub-second window of an exact
         * day boundary, where the two clocks can land on either side. The branch order mirrors the
         * Kotlin: explicit retraction first, then the dated (validFrom) window — out-of-window → 0,
         * closed window → undecayed confidence, open-ended window → decay from validFrom — then the
         * plain decaying case anchored on contentRevised.
         *
         * `decay` and `contentRevised` are coalesced because a node written before those columns
         * existed comes back with them absent, and any arithmetic touching a missing property yields
         * NULL — which would `SET p.effectiveConfidence = NULL`, erasing the value and hiding the node
         * from every effective-confidence filter (NULL fails all comparisons). The fallbacks are the
         * Kotlin defaults: `decay = 0.0` (no decay) and `contentRevised = created` (the anchor). `p.created`
         * is always present, and `confidence` is required too, so nothing else here can go NULL.
         *
         * Every temporal read is normalised through `datetime(toString(x))` before it's used, because
         * the store holds these fields inconsistently: some nodes carry a native Neo4j `datetime`,
         * others an ISO-8601 string (an older write path). `toString` renders either shape to an
         * ISO string and `datetime(...)` parses it back, so `.epochSeconds` and the window comparisons
         * work regardless of how the value was stored. Without this a string-typed `validFrom` /
         * `contentRevised` throws "expected a map but was String" and the whole sweep aborts.
         * `IS NULL` guards stay on the raw property so an absent field is still detected.
         */
        private val DECAY_EXPRESSION = """
            WITH p, datetime(${'$'}now) AS nowDt, datetime(${'$'}now).epochSeconds AS nowE
            WITH p, nowDt, nowE,
                 CASE WHEN p.validFrom IS NULL THEN null ELSE datetime(toString(p.validFrom)) END AS vf,
                 CASE WHEN p.validTo IS NULL THEN null ELSE datetime(toString(p.validTo)) END AS vt,
                 CASE WHEN p.invalidatedAt IS NULL THEN null ELSE datetime(toString(p.invalidatedAt)) END AS inv,
                 datetime(toString(coalesce(p.contentRevised, p.created))) AS revised
            WITH p, nowDt, nowE, vf, vt, inv,
                 CASE WHEN vf IS NULL THEN 0.0
                      WHEN nowE - vf.epochSeconds < 0 THEN 0.0
                      ELSE floor((nowE - vf.epochSeconds) / 86400.0) END AS ageValidFrom,
                 CASE WHEN nowE - revised.epochSeconds < 0 THEN 0.0
                      ELSE floor((nowE - revised.epochSeconds) / 86400.0) END AS ageRevised
            SET p.effectiveConfidence = CASE
                WHEN inv IS NOT NULL AND inv <= nowDt THEN 0.0
                WHEN vf IS NOT NULL AND nowDt < vf THEN 0.0
                WHEN vf IS NOT NULL AND vt IS NOT NULL AND nowDt >= vt THEN 0.0
                WHEN vf IS NOT NULL AND vt IS NOT NULL THEN p.confidence
                WHEN vf IS NOT NULL THEN p.confidence * exp(-coalesce(p.decay, 0.0) * $DECAY_K * ageValidFrom)
                ELSE p.confidence * exp(-coalesce(p.decay, 0.0) * $DECAY_K * ageRevised)
              END,
              p.decayUpdatedAt = nowDt
        """.trimIndent()
    }
}
