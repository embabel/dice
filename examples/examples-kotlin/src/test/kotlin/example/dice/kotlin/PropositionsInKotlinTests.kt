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
package example.dice.kotlin

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * Stores a few facts by hand and reads them back the way Kotlin writes it, with a typed
 * [ContextId], a block that sets the values of a [Proposition] and the infix
 * `PropositionQuery forContextId`.
 */
class PropositionsInKotlinTests {

    private val acme = ContextId("acme")
    private val globex = ContextId("globex")
    private val recorded: Instant = Instant.parse("2026-11-01T00:00:00Z")

    private val repository = InMemoryPropositionRepository().apply {
        saveAll(
            listOf(
                fact(acme, "Acme is on the Team plan", confidence = 0.95, decay = 0.005),
                fact(acme, "CSV export times out at Acme for large reports", confidence = 0.85, decay = 0.05),
                fact(acme, "Acme is on the Starter plan", confidence = 0.9, decay = 0.0, status = PropositionStatus.SUPERSEDED),
                fact(globex, "Globex is on the Starter plan", confidence = 0.95, decay = 0.005),
            )
        )
    }

    @Test
    fun `a query scoped to a context returns only that context's active facts`() {

        val facts = repository.query(
            (PropositionQuery forContextId acme)
                .withStatus(PropositionStatus.ACTIVE)
                .orderedByEffectiveConfidence()
        )

        assertThat(facts.map { it.text }).containsExactlyInAnyOrder(
            "Acme is on the Team plan",
            "CSV export times out at Acme for large reports",
        )
    }

    @Test
    fun `confidence falls with the decay rate`() {

        val plan = repository.query(PropositionQuery forContextId globex).single()
        val thirtyDaysOn = recorded + Duration.ofDays(30)

        assertThat(plan.effectiveConfidenceAt(recorded)).isEqualTo(0.95)
        assertThat(plan.effectiveConfidenceAt(thirtyDaysOn)).isCloseTo(0.95 * exp(-0.3), within(1e-9))
    }

    /**
     * Builds a fact recorded at [recorded] that mentions no entities.
     *
     * @param contextId the context the fact belongs to
     * @param text the fact, as a sentence
     * @param confidence how sure we are, from 0 to 1
     * @param decay how fast the fact goes stale, from 0 to 1
     * @param status the fact's lifecycle status
     * @return the fact
     */
    private fun fact(
        contextId: ContextId,
        text: String,
        confidence: Double,
        decay: Double,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ) = Proposition(contextId, text, confidence) {
        decay(decay)
        created(recorded)
        status(status)
    }
}
