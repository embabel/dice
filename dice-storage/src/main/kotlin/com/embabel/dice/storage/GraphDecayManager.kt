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
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import java.time.Instant

/**
 * Graph [DecayManager]: the storage-agnostic lifecycle sweep (inherited), plus materialisation of the
 * decayed `effectiveConfidence` onto `:Proposition` nodes so confidence-ranked reads push into Neo4j.
 *
 * Materialisation recomputes via [Proposition.effectiveConfidenceAt] — the single source of truth —
 * and batch-writes the result in one statement, rather than re-encoding the decay formula in Cypher
 * (which would silently drift from the Kotlin definition). The read is acceptable because the sweep
 * is periodic, not on the query path; loading only the decay-relevant columns is a possible
 * optimisation if stores get large.
 */
class GraphDecayManager(
    repository: PropositionRepository,
    private val persistenceManager: PersistenceManager,
) : DecayManager(repository) {

    override fun materialize(contextId: ContextId) =
        writeBack(repository.query(PropositionQuery(contextId = contextId)))

    override fun materializeAll() = writeBack(repository.findAll())

    private fun writeBack(propositions: List<Proposition>) {
        if (propositions.isEmpty()) return
        val now = Instant.now()
        val rows = propositions.map { mapOf("id" to it.id, "ec" to it.effectiveConfidenceAt(now)) }
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    "UNWIND \$rows AS r MATCH (p:Proposition {id: r.id}) " +
                        "SET p.effectiveConfidence = r.ec, p.decayUpdatedAt = \$now"
                )
                .bind(mapOf("rows" to rows, "now" to now))
        )
    }
}
