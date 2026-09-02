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
package com.embabel.dice.spi

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.DecaySweepResult
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Two sweeps run over the same store on their own schedules: the decay sweep, which decides what has
 * lost its usefulness, and the drift sweep, which holds back what a schema change stranded. This
 * pins down what happens where they meet.
 *
 * The failure this exists to prevent: quarantine used to be a `STALE` proposition with a metadata
 * note, and `STALE` is exactly where the decay policy looks for something to revive. A confident
 * proposition quarantined for schema drift would be handed back to `ACTIVE` by the next decay sweep,
 * quarantined again by the next drift sweep, and the two would take turns indefinitely. Recovery
 * from quarantine was an accident of that overlap; here it is an operation with a name.
 *
 * The decay sweeps below are configured with every status as a target, which is wider than the
 * shipped default of `{ACTIVE}`. A host that widens its sweep is the case where the overlap bites,
 * so that is the case worth holding.
 */
class QuarantineDecayInteractionTest {

    private val contextId = ContextId("context-a")
    private val schemaName = "test-schema"

    private lateinit var propositions: InMemoryPropositionRepository
    private lateinit var decay: InMemoryDecayManager
    private lateinit var sweep: PropositionStoreDriftSweep
    private lateinit var policy: MentionTypeDriftQuarantinePolicy

    /** A decay sweep that looks at everything, so nothing is skipped by the status filter alone. */
    private val sweepEverything = DecaySweepConfig(
        policy = DecayStatusPolicy(),
        targetStatuses = PropositionStatus.entries.toSet(),
    )

    @BeforeEach
    fun setUp() {
        propositions = InMemoryPropositionRepository()
        decay = InMemoryDecayManager(propositions)
        sweep = PropositionStoreDriftSweep(propositions)
        policy = MentionTypeDriftQuarantinePolicy()
    }

    /**
     * A proposition the decay policy would happily call healthy: `decay = 0.0` fixes its effective
     * confidence at the raw value, and 0.9 sits well above the 0.2 recovery ceiling.
     */
    private fun confidentPerson(): Proposition = propositions.save(
        Proposition(
            contextId = contextId,
            text = "Alice is 40",
            mentions = listOf(EntityMention(span = "alice", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
            decay = 0.0,
        ),
    )

    private fun versionOf(
        types: List<String>,
        properties: Map<String, Set<PropertySignature>> = emptyMap(),
    ): MetamodelVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = types,
        entityTypeLabels = types.associateWith { setOf(it) },
        entityTypeProperties = types.associateWith { properties[it].orEmpty() },
        relationshipNames = emptyList(),
    )

    /** `Person` loses its `age` property, which is enough to strand a proposition mentioning it. */
    private fun personLostAge(): MetamodelDiff = StructuralMetamodelDiffer().diff(
        versionOf(
            listOf("Person"),
            mapOf("Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE))),
        ),
        versionOf(listOf("Person")),
    )

    @Test
    fun `a decay sweep leaves a quarantined proposition exactly where the drift sweep put it`() {
        val original = confidentPerson()
        assertEquals(
            PropositionStatus.ACTIVE,
            DecayStatusPolicy().evaluate(original.withStatus(PropositionStatus.STALE)),
            "sanity: this proposition is confident enough that a decay sweep revives it out of STALE",
        )

        sweep.sweep(personLostAge(), policy, contextId)
        val quarantined = propositions.findById(original.id)!!
        assertEquals(PropositionStatus.QUARANTINED, quarantined.status)
        val reason = quarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON]
        assertNotNull(reason, "sanity: the quarantine recorded why")

        val result = decay.sweepAll(sweepEverything)

        val held = propositions.findById(original.id)!!
        assertEquals(
            PropositionStatus.QUARANTINED,
            held.status,
            "a decay sweep must have no way to lift a schema-governance hold",
        )
        assertEquals(reason, held.metadata[DiceMetadataKeys.QUARANTINE_REASON], "and the reason survives it")
        assertEquals(
            PropositionStatus.ACTIVE.name,
            held.metadata[DriftQuarantineKeys.PREVIOUS_STATUS],
            "as does the record of where to put it back",
        )

        val swept = assertSwept(result)
        assertTrue(swept.revived.isEmpty(), "nothing was revived: ${swept.revived}")
        assertTrue(swept.transitioned.isEmpty(), "and nothing was transitioned: ${swept.transitioned}")
    }

    @Test
    fun `repeated decay sweeps never start the alternation`() {
        // The original symptom was a loop, so one pass is a weak check: the two sweeps took turns,
        // and each turn looked locally correct. Run both on their schedules and watch the record.
        val original = confidentPerson()
        val diff = personLostAge()

        val first = sweep.sweep(diff, policy, contextId)
        assertEquals(1, first.quarantined.size, "sanity: the schema change stranded it")

        repeat(3) {
            decay.sweepAll(sweepEverything)
            val swept = sweep.sweep(diff, policy, contextId)
            assertEquals(0, swept.quarantined.size, "a held proposition is never re-quarantined")
            assertEquals(1, swept.alreadyQuarantined.size, "it is reported as already held, every time")
            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(original.id)!!.status)
        }
    }

    @Test
    fun `after release the proposition is back under ordinary decay`() {
        val original = confidentPerson()
        sweep.sweep(personLostAge(), policy, contextId)

        val released = sweep.releaseFromQuarantine(original.id)!!

        assertEquals(PropositionStatus.ACTIVE, released.status, "release restores the status it came from")
        assertNull(
            released.metadata[DiceMetadataKeys.QUARANTINE_REASON],
            "release clears the reason, so nothing is left claiming the proposition is held",
        )
        assertNull(released.metadata[DriftQuarantineKeys.PREVIOUS_STATUS])
        assertEquals(released, propositions.findById(original.id), "and the release was persisted")

        // Ordinary decay applies again: drop the confidence under the staleness floor and the sweep
        // that would not touch it a moment ago now moves it, which is the whole point of releasing.
        propositions.save(released.copy(confidence = 0.05))

        val swept = assertSwept(decay.sweepAll(sweepEverything))

        assertEquals(1, swept.transitioned.size, "a released proposition is an ordinary decay candidate")
        assertEquals(PropositionStatus.STALE, propositions.findById(original.id)!!.status)
    }

    @Test
    fun `a decay sweep cannot revive a proposition quarantined out of STALE`() {
        // The nastiest shape of the original bug: decay had already made this one STALE, so the
        // recorded previous status is STALE too. Reviving it would look locally reasonable and would
        // still be lifting a hold nobody released.
        val stale = propositions.save(
            Proposition(
                contextId = contextId,
                text = "Alice is 40",
                mentions = listOf(EntityMention(span = "alice", type = "Person", role = MentionRole.SUBJECT)),
                confidence = 0.9,
                decay = 0.0,
                status = PropositionStatus.STALE,
            ),
        )

        sweep.sweep(personLostAge(), policy, contextId)
        decay.sweepAll(sweepEverything)

        val held = propositions.findById(stale.id)!!
        assertEquals(PropositionStatus.QUARANTINED, held.status)
        assertEquals(PropositionStatus.STALE.name, held.metadata[DriftQuarantineKeys.PREVIOUS_STATUS])
        assertEquals(
            PropositionStatus.STALE,
            sweep.releaseFromQuarantine(stale.id)!!.status,
            "and release puts it back in the status decay had left it in",
        )
    }

    private fun assertSwept(result: DecaySweepResult): DecaySweepResult.Swept {
        assertTrue(result is DecaySweepResult.Swept, "expected a completed sweep, got $result")
        return result as DecaySweepResult.Swept
    }
}
