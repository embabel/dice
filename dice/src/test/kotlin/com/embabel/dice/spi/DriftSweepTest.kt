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
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.JvmType
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.SafeDiceEventListener
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.GovernedTypeSelector
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionLineageStaleCascade
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The deliberate half: [PropositionStoreDriftSweep], the reference [DriftSweepCapable], driven the
 * way a host drives it — check first, decide, then sweep one context at a time, then mark the
 * baseline.
 *
 * The real [MentionTypeDriftQuarantinePolicy] and [StructuralMetamodelDiffer] are used throughout,
 * so these exercise the actual collaboration.
 */
class DriftSweepTest {

    private val contextId = ContextId("context-a")
    private val otherContextId = ContextId("context-b")
    private val schemaName = "test-schema"

    private lateinit var propositions: InMemoryPropositionRepository
    private lateinit var policy: MentionTypeDriftQuarantinePolicy

    @BeforeEach
    fun setUp() {
        propositions = InMemoryPropositionRepository()
        policy = MentionTypeDriftQuarantinePolicy()
    }

    private fun proposition(
        text: String,
        vararg mentionTypes: String,
        inContext: ContextId = contextId,
        id: String? = null,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        pinned: Boolean = false,
    ): Proposition {
        val base = Proposition(
            contextId = inContext,
            text = text,
            mentions = mentionTypes.map { type ->
                EntityMention(span = type.lowercase(), type = type, role = MentionRole.SUBJECT)
            },
            confidence = 0.9,
        ).withStatus(status).withPinned(pinned)
        return if (id == null) base else base.copy(id = id)
    }

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

    private fun diffOf(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff =
        StructuralMetamodelDiffer().diff(from, to)

    /** A canned snapshot of what a graph reports: labels, simple, with no package on them. */
    private fun observing(entityTypeNames: Set<String>): ObservedSchemaSource =
        object : ObservedSchemaSource {
            override fun observe(contextId: ContextId?): ObservedSchema = ObservedSchema(
                entityTypeNames = entityTypeNames,
                relationshipTypeNames = emptySet(),
                capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }

    /** `Person` loses its `age` property: the plainest lossy change there is. */
    private fun personLostAge(): MetamodelDiff = diffOf(
        versionOf(
            listOf("Person", "Company"),
            mapOf("Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE))),
        ),
        versionOf(listOf("Person", "Company")),
    )

    @Nested
    inner class Scoping {

        @Test
        fun `a sweep scoped to one context leaves every other context untouched`() {
            // Both propositions mention the affected type, so a sweep that reached across contexts
            // would flag both. Confining it to one must reach exactly one.
            val inScope = propositions.save(proposition("Alice is 40", "Person", inContext = contextId))
            val outOfScope = propositions.save(proposition("Bob is 50", "Person", inContext = otherContextId))
            val sweep = PropositionStoreDriftSweep(propositions)

            val result = sweep.sweep(personLostAge(), policy, contextId)

            assertEquals(1, result.quarantined.size, "only context A holds a candidate")
            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(inScope.id)!!.status)

            val untouched = propositions.findById(outOfScope.id)!!
            assertEquals(
                PropositionStatus.ACTIVE,
                untouched.status,
                "a sweep scoped to one context must have no way to reach another's propositions",
            )
            assertNull(untouched.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `the candidate read never leaves the context it was given`() {
            propositions.save(proposition("Alice is 40", "Person", inContext = contextId))
            propositions.save(proposition("Bob is 50", "Person", inContext = otherContextId))
            val recording = RecordingPropositionStore(propositions)
            val sweep = PropositionStoreDriftSweep(recording)

            val candidates = sweep.quarantineCandidates(contextId, setOf("Person"), limit = 10)

            assertEquals(listOf(contextId), recording.contextReads)
            assertFalse(recording.readEverything, "a whole-store read would materialise every tenant")
            assertEquals(listOf(contextId), candidates.map { it.contextId })
        }

        @Test
        fun `sweeping each context in turn reaches all of them`() {
            val inA = propositions.save(proposition("Alice is 40", "Person", inContext = contextId))
            val inB = propositions.save(proposition("Bob is 50", "Person", inContext = otherContextId))
            val sweep = PropositionStoreDriftSweep(propositions)
            val diff = personLostAge()

            sweep.sweep(diff, policy, contextId)
            sweep.sweep(diff, policy, otherContextId)

            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(inA.id)!!.status)
            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(inB.id)!!.status)
        }
    }

    @Nested
    inner class BoundedSelection {

        @Test
        fun `a page never exceeds the limit and the cursor walks the rest`() {
            (1..5).forEach { propositions.save(proposition("p$it", "Person", id = "id-$it")) }
            val sweep = PropositionStoreDriftSweep(propositions)

            val first = sweep.quarantineCandidates(contextId, setOf("Person"), limit = 2)
            val second = sweep.quarantineCandidates(contextId, setOf("Person"), limit = 2, afterId = first.last().id)
            val third = sweep.quarantineCandidates(contextId, setOf("Person"), limit = 2, afterId = second.last().id)

            assertEquals(listOf("id-1", "id-2"), first.map { it.id })
            assertEquals(listOf("id-3", "id-4"), second.map { it.id })
            assertEquals(listOf("id-5"), third.map { it.id })
        }

        @Test
        fun `a sweep pages through every candidate when the batch is smaller than the context`() {
            (1..5).forEach { propositions.save(proposition("p$it", "Person", id = "id-$it")) }
            val sweep = PropositionStoreDriftSweep(propositions)

            val result = sweep.sweep(personLostAge(), policy, contextId, batchSize = 2)

            assertEquals(5, result.quarantined.size, "paging must reach the whole context: $result")
            assertTrue(
                propositions.findAll().all { it.status == PropositionStatus.QUARANTINED },
                "every candidate was quarantined",
            )
        }

        @Test
        fun `only the mention types the policy asks for are ever read`() {
            propositions.save(proposition("Alice is 40", "Person"))
            propositions.save(proposition("Acme is a company", "Company"))
            val recording = RecordingSweep(PropositionStoreDriftSweep(propositions))

            recording.sweep(personLostAge(), policy, contextId)

            assertEquals(
                listOf(setOf("Person")),
                recording.requestedMentionTypes,
                "a change touching Person must never ask the store for Company",
            )
            assertEquals(PropositionStatus.ACTIVE, propositions.findAll().single { it.text.startsWith("Acme") }.status)
        }

        @Test
        fun `a change that can strand nothing reads no propositions at all`() {
            propositions.save(proposition("Alice is 40", "Person"))
            val recording = RecordingSweep(PropositionStoreDriftSweep(propositions))
            val additive = diffOf(versionOf(listOf("Person")), versionOf(listOf("Person", "Company")))

            val result = recording.sweep(additive, policy, contextId)

            assertTrue(recording.requestedMentionTypes.isEmpty(), "no page should have been requested")
            assertEquals(0, result.total, "and nothing was decided")
            assertEquals(PropositionStatus.ACTIVE, propositions.findAll().single().status)
        }

        @Test
        fun `an empty mention-type set returns nothing`() {
            propositions.save(proposition("Alice is 40", "Person"))
            val sweep = PropositionStoreDriftSweep(propositions)

            assertEquals(emptyList<Proposition>(), sweep.quarantineCandidates(contextId, emptySet(), limit = 10))
        }

        @Test
        fun `a non-positive limit is refused`() {
            val sweep = PropositionStoreDriftSweep(propositions)

            assertThrows(IllegalArgumentException::class.java) {
                sweep.quarantineCandidates(contextId, setOf("Person"), limit = 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                sweep.sweep(personLostAge(), policy, contextId, batchSize = 0)
            }
        }
    }

    @Nested
    inner class Quarantining {

        @Test
        fun `a sweep persists what the policy flags and leaves the rest alone`() {
            val affected = propositions.save(proposition("Alice is 40", "Person"))
            val safe = propositions.save(proposition("Acme is a company", "Company"))
            val sweep = PropositionStoreDriftSweep(propositions)

            val result = sweep.sweep(personLostAge(), policy, contextId)

            assertEquals(1, result.quarantined.size)
            val quarantined = propositions.findById(affected.id)!!
            assertEquals(PropositionStatus.QUARANTINED, quarantined.status)
            assertNotNull(quarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON])
            assertEquals(PropositionStatus.ACTIVE, propositions.findById(safe.id)!!.status)
        }

        @Test
        fun `a pinned proposition is reported and never moved`() {
            val pinned = propositions.save(proposition("Alice is 40", "Person", pinned = true))
            val sweep = PropositionStoreDriftSweep(propositions)

            val result = sweep.sweep(personLostAge(), policy, contextId)

            assertEquals(1, result.protected.size)
            assertEquals(0, result.quarantined.size)
            assertEquals(PropositionStatus.ACTIVE, propositions.findById(pinned.id)!!.status)
        }

        @Test
        fun `a second sweep leaves an already-quarantined proposition exactly as it was`() {
            val affected = propositions.save(proposition("Alice is 40", "Person"))
            val sweep = PropositionStoreDriftSweep(propositions)
            val diff = personLostAge()
            sweep.sweep(diff, policy, contextId)
            val afterFirst = propositions.findById(affected.id)!!

            val second = sweep.sweep(diff, policy, contextId)

            assertEquals(1, second.alreadyQuarantined.size)
            assertEquals(0, second.quarantined.size)
            assertEquals(afterFirst, propositions.findById(affected.id)!!, "the record must be untouched")
        }

        @Test
        fun `a quarantine's status change reaches projection lineage through the listener`() {
            // ProjectionLineageStaleCascade is how a proposition leaving ordinary use marks its
            // projection records stale in turn; it reacts to PropositionStatusChanged, and
            // QUARANTINED counts as leaving ordinary use.
            val affected = propositions.save(proposition("Alice is 40", "Person"))
            val recordStore = InMemoryProjectionRecordStore()
            recordStore.record(
                ProjectionRecord(
                    propositionId = affected.id,
                    target = "test-target",
                    lifecycle = ProjectionLifecycle.PROJECTED,
                    runId = "run-1",
                ),
            )
            val cascade = ProjectionLineageStaleCascade(recordStore)
            val sweep = PropositionStoreDriftSweep(propositions, SafeDiceEventListener(cascade))

            sweep.sweep(personLostAge(), policy, contextId)

            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(affected.id)!!.status)
            assertEquals(
                ProjectionLifecycle.STALE,
                recordStore.findByProposition(affected.id).single().lifecycle,
                "the cascade heard about the transition and marked its record stale in turn",
            )
        }

        @Test
        fun `the emitted event carries the reason and the status it came from`() {
            propositions.save(proposition("Alice is 40", "Person"))
            val recording = RecordingDiceEventListener()
            val sweep = PropositionStoreDriftSweep(propositions, recording)

            sweep.sweep(personLostAge(), policy, contextId)

            val event = recording.events.filterIsInstance<PropositionStatusChanged>().single()
            assertEquals(PropositionStatus.ACTIVE, event.previousStatus)
            assertEquals(PropositionStatus.QUARANTINED, event.newStatus)
            assertTrue(event.reason!!.contains("age"), event.reason)
        }

        @Test
        fun `a conforming proposition emits nothing`() {
            propositions.save(proposition("Acme is a company", "Company"))
            val recording = RecordingDiceEventListener()
            val sweep = PropositionStoreDriftSweep(propositions, recording)

            sweep.sweep(personLostAge(), policy, contextId)

            assertTrue(recording.events.isEmpty())
        }

        @Test
        fun `a proposition already STALE from decay is quarantined out of that status`() {
            // The idempotency check skips one whose status is already QUARANTINED. Ordinary decay
            // reaches STALE, which is a different place, so a decayed proposition is a fresh
            // candidate here: it moves to QUARANTINED, the move is announced, and STALE is what a
            // release will put it back to.
            val decayed = propositions.save(
                proposition("Alice is 40", "Person", status = PropositionStatus.STALE),
            )
            val recording = RecordingDiceEventListener()
            val sweep = PropositionStoreDriftSweep(propositions, recording)

            val result = sweep.sweep(personLostAge(), policy, contextId)

            assertEquals(1, result.quarantined.size, "sanity: it was quarantined")
            assertEquals(PropositionStatus.STALE, result.quarantined.single().previousStatus)
            val held = propositions.findById(decayed.id)!!
            assertEquals(PropositionStatus.QUARANTINED, held.status)
            assertNotNull(held.metadata[DiceMetadataKeys.QUARANTINE_REASON])
            assertEquals(
                PropositionStatus.STALE.name,
                held.metadata[DriftQuarantineKeys.PREVIOUS_STATUS],
            )
            val event = recording.events.filterIsInstance<PropositionStatusChanged>().single()
            assertEquals(PropositionStatus.STALE, event.previousStatus)
            assertEquals(PropositionStatus.QUARANTINED, event.newStatus)

            assertEquals(
                PropositionStatus.STALE,
                sweep.releaseFromQuarantine(decayed.id)!!.status,
                "release puts it back where decay had left it",
            )
        }

        @Test
        fun `a failing write stops the sweep and leaves the baseline alone`() {
            // markSwept belongs to the host, and the host never reaches it when the sweep throws,
            // so an interrupted sweep can never look like a finished reconciliation.
            val versionStore = InMemoryMetamodelVersionStore()
            val baseline = versionOf(listOf("Person"))
            versionStore.markSwept(baseline)
            propositions.save(proposition("Alice is 40", "Person"))
            val crashing = object : PropositionStore by propositions {
                override fun save(proposition: Proposition): Proposition =
                    throw IllegalStateException("simulated crash mid-sweep")
            }
            val sweep = PropositionStoreDriftSweep(crashing)

            assertThrows(IllegalStateException::class.java) {
                sweep.sweep(personLostAge(), policy, contextId)
                versionStore.markSwept(versionOf(listOf("Person", "Company")))
            }

            assertEquals(baseline, versionStore.sweptVersion(schemaName))
            assertEquals(PropositionStatus.ACTIVE, propositions.findAll().single().status)
        }
    }

    @Nested
    inner class Releasing {

        @Test
        fun `release restores the status the proposition came from and clears the reason`() {
            val original = propositions.save(proposition("Alice is 40", "Person"))
            val sweep = PropositionStoreDriftSweep(propositions)
            sweep.sweep(personLostAge(), policy, contextId)
            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(original.id)!!.status, "sanity")

            val released = sweep.releaseFromQuarantine(original.id)

            assertNotNull(released)
            assertEquals(
                PropositionStatus.ACTIVE,
                released!!.status,
                "clearing the reason alone would leave it held with nothing on it saying why",
            )
            assertNull(released.metadata[DiceMetadataKeys.QUARANTINE_REASON])
            assertNull(released.metadata[DriftQuarantineKeys.PREVIOUS_STATUS])
            assertEquals(released, propositions.findById(original.id), "and the release was persisted")
        }

        @Test
        fun `release puts a proposition back where it was, even when that was not ACTIVE`() {
            val promoted = propositions.save(
                proposition("Alice is 40", "Person", status = PropositionStatus.PROMOTED),
            )
            val sweep = PropositionStoreDriftSweep(propositions)
            sweep.sweep(personLostAge(), policy, contextId)

            val released = sweep.releaseFromQuarantine(promoted.id)!!

            assertEquals(PropositionStatus.PROMOTED, released.status)
        }

        @Test
        fun `a released proposition is a fresh candidate again`() {
            val original = propositions.save(proposition("Alice is 40", "Person"))
            val sweep = PropositionStoreDriftSweep(propositions)
            val diff = personLostAge()
            sweep.sweep(diff, policy, contextId)
            sweep.releaseFromQuarantine(original.id)

            val second = sweep.sweep(diff, policy, contextId)

            assertEquals(1, second.quarantined.size, "the reason is gone, so the policy judges it afresh")
            assertEquals(0, second.alreadyQuarantined.size)
        }

        @Test
        fun `release announces the transition it made`() {
            val original = propositions.save(proposition("Alice is 40", "Person"))
            val recording = RecordingDiceEventListener()
            val sweep = PropositionStoreDriftSweep(propositions, recording)
            sweep.sweep(personLostAge(), policy, contextId)
            recording.events.clear()

            sweep.releaseFromQuarantine(original.id)

            val event = recording.events.filterIsInstance<PropositionStatusChanged>().single()
            assertEquals(PropositionStatus.QUARANTINED, event.previousStatus)
            assertEquals(PropositionStatus.ACTIVE, event.newStatus)
        }

        @Test
        fun `releasing something that was never quarantined answers null and changes nothing`() {
            val untouched = propositions.save(proposition("Alice is 40", "Person"))
            val sweep = PropositionStoreDriftSweep(propositions)

            assertNull(sweep.releaseFromQuarantine(untouched.id))
            assertNull(sweep.releaseFromQuarantine("no-such-id"))
            assertEquals(untouched, propositions.findById(untouched.id))
        }

        @Test
        fun `releasing twice is safe`() {
            val original = propositions.save(proposition("Alice is 40", "Person"))
            val sweep = PropositionStoreDriftSweep(propositions)
            sweep.sweep(personLostAge(), policy, contextId)

            assertNotNull(sweep.releaseFromQuarantine(original.id))
            assertNull(sweep.releaseFromQuarantine(original.id), "the second call finds nothing quarantined")
        }

        @Test
        fun `a quarantine with no recorded previous status is released to ACTIVE`() {
            // What a quarantine whose previousStatus metadata a person edited away looks like.
            val legacy = propositions.save(
                proposition("Alice is 40", "Person", status = PropositionStatus.QUARANTINED)
                    .withMetadataValue(DiceMetadataKeys.QUARANTINE_REASON, "quarantined by an earlier build"),
            )
            val sweep = PropositionStoreDriftSweep(propositions)

            val released = sweep.releaseFromQuarantine(legacy.id)!!

            assertEquals(PropositionStatus.ACTIVE, released.status)
            assertNull(released.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }
    }

    /**
     * The case carried over from the review of the diff slice: a dictionary declaring a governed
     * `Person` and an ungoverned `Sighting`, a graph holding both, and a full pass — check, then
     * deliberate sweep — that has to leave the `Sighting` proposition alone.
     *
     * The types are real JVM classes, so the declaration holds fully qualified names while the graph
     * and the extraction hold simple ones. That is the spelling mismatch this whole path has to
     * survive, on both halves at once.
     */
    @Nested
    inner class GovernedPersonAndUngovernedSighting {

        private val personName = Person::class.java.name
        private val sightingName = Sighting::class.java.name

        private fun dictionary(): DataDictionary = DataDictionary.fromDomainTypes(
            schemaName,
            listOf(JvmType(Person::class.java), JvmType(Sighting::class.java)),
        )

        private fun declaredSchema(): DeclaredSchema =
            DeclaredSchema.from(dictionary(), GovernedTypeSelector { it.name == personName })

        private fun runCheck(versionStore: MetamodelVersionStore): DriftCheckResult {
            val differ = StructuralMetamodelDiffer()
            val declared = declaredSchema()
            return DefaultDriftCheckRunner(
                declaredSchemaSource = { declared },
                versionStore = versionStore,
                observedSchemaSource = observing(setOf("Person", "Sighting")),
                differ = differ,
                metamodelDiffer = differ,
                driftReportStore = InMemoryDriftReportStore(),
            ).run()
        }

        @Test
        fun `an ungoverned type observed in the graph survives a full check-and-sweep pass`() {
            assertTrue(sightingName.contains('.'), "sanity: the declaration is fully qualified")
            assertEquals(setOf("Sighting"), declaredSchema().ungovernedEntityTypeNames.map {
                DeclaredSchema.ownLabelOf(it)
            }.toSet())

            val sighting = propositions.save(proposition("a sighting was reported", "Sighting"))
            val person = propositions.save(proposition("Alice is a person", "Person"))
            val versionStore = InMemoryMetamodelVersionStore()

            val result = runCheck(versionStore)

            assertTrue(
                result.driftedEntityTypes.isEmpty(),
                "an ungoverned type is a known type, so it is no drift: ${result.driftedEntityTypes}",
            )
            assertFalse(result.hasAnyChange)

            // Now the deliberate half, exactly as a host would run it.
            val sweep = PropositionStoreDriftSweep(propositions)
            val swept = sweep.sweep(result.quarantineDiff, policy, contextId)
            versionStore.markSwept(result.declaredVersion)

            assertEquals(0, swept.quarantined.size, "the sweep had nothing to act on: $swept")
            assertEquals(
                PropositionStatus.ACTIVE,
                propositions.findById(sighting.id)!!.status,
                "a proposition mentioning a known-but-ungoverned type must survive a real sweep",
            )
            assertNull(propositions.findById(sighting.id)!!.metadata[DiceMetadataKeys.QUARANTINE_REASON])
            assertEquals(PropositionStatus.ACTIVE, propositions.findById(person.id)!!.status)
            assertEquals(
                result.declaredVersion,
                versionStore.sweptVersion(schemaName),
                "and the host's completed sweep is what moved the baseline",
            )
        }

        @Test
        fun `the governed type is still reachable when its declaration really does lose something`() {
            // The other side of the same spelling problem: a lossy change on the fully qualified
            // `Person` has to reach a proposition whose mention says plain `Person`. Without that,
            // the case above would pass for the wrong reason -- nothing would ever match.
            val mentioning = propositions.save(proposition("Alice is 40", "Person"))
            val before = MetamodelVersion(
                schemaName = schemaName,
                entityTypeNames = listOf(personName),
                entityTypeLabels = mapOf(personName to setOf("Person")),
                entityTypeProperties = mapOf(
                    personName to setOf(
                        PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                    ),
                ),
                relationshipNames = emptyList(),
            )
            val after = MetamodelVersion(
                schemaName = schemaName,
                entityTypeNames = listOf(personName),
                entityTypeLabels = mapOf(personName to setOf("Person")),
                entityTypeProperties = mapOf(personName to emptySet()),
                relationshipNames = emptyList(),
            )
            val sweep = PropositionStoreDriftSweep(propositions)

            val result = sweep.sweep(diffOf(before, after), policy, contextId)

            assertEquals(1, result.quarantined.size, "the simple mention must match the qualified declaration")
            assertEquals(PropositionStatus.QUARANTINED, propositions.findById(mentioning.id)!!.status)
        }
    }

    @Nested
    inner class WhatAReportPromises {

        @Test
        fun `the comparison a report carries is the one the sweep evaluates`() {
            // The report's whole purpose after the live path went away: a person reads it, decides,
            // and the sweep then acts on exactly those facts.
            val versionStore = InMemoryMetamodelVersionStore()
            versionStore.markSwept(
                versionOf(
                    listOf("Person", "Company"),
                    mapOf(
                        "Person" to setOf(
                            PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                        ),
                    ),
                ),
            )
            val declared = DeclaredSchema(
                version = versionOf(listOf("Person", "Company")),
                relationshipTypeNames = emptySet(),
            )
            val differ = StructuralMetamodelDiffer()
            val result = DefaultDriftCheckRunner(
                declaredSchemaSource = { declared },
                versionStore = versionStore,
                observedSchemaSource = observing(setOf("Person", "Company", "GhostType")),
                differ = differ,
                metamodelDiffer = differ,
                driftReportStore = InMemoryDriftReportStore(),
            ).run()

            propositions.save(proposition("Alice is 40", "Person"))
            propositions.save(proposition("a ghost was mentioned", "GhostType"))
            val recordingPolicy = RecordingPolicy()

            val swept = PropositionStoreDriftSweep(propositions)
                .sweep(result.quarantineDiff, recordingPolicy, contextId)

            assertNotNull(result.declaredDiff, "the report saw the declared change")
            assertEquals(
                result.report.quarantineDiff(result.declaredVersion),
                recordingPolicy.evaluatedDiff,
                "the sweep evaluated exactly the comparison the report carries",
            )
            assertEquals(2, swept.quarantined.size, "both halves of that comparison found something")
        }
    }

    /** Records which reads a sweep made of the underlying store. */
    private class RecordingPropositionStore(
        private val delegate: PropositionStore,
    ) : PropositionStore by delegate {

        val contextReads = mutableListOf<ContextId>()
        var readEverything: Boolean = false
            private set

        override fun findAll(): List<Proposition> {
            readEverything = true
            return delegate.findAll()
        }

        override fun findByContextId(contextId: ContextId): List<Proposition> {
            contextReads += contextId
            return delegate.findByContextId(contextId)
        }
    }

    /**
     * Wraps a real [DriftSweepCapable] and remembers what the interface's own `sweep` asked its
     * store for, so a test can assert on the bound and the mention types directly.
     *
     * Written out member by member, with no Kotlin interface delegation, on purpose: a delegating
     * wrapper would forward `sweep` to the delegate, whose `this` is the delegate, and the recording
     * overrides below would never be reached.
     */
    private class RecordingSweep(
        private val delegate: DriftSweepCapable,
    ) : DriftSweepCapable {

        val requestedMentionTypes = mutableListOf<Set<String>>()

        override fun quarantineCandidates(
            contextId: ContextId,
            mentionTypes: Set<String>,
            limit: Int,
            afterId: String?,
        ): List<Proposition> {
            requestedMentionTypes += mentionTypes
            return delegate.quarantineCandidates(contextId, mentionTypes, limit, afterId)
        }

        override fun applyQuarantine(decision: QuarantineDecision.Quarantined): Proposition =
            delegate.applyQuarantine(decision)

        override fun releaseFromQuarantine(propositionId: String): Proposition? =
            delegate.releaseFromQuarantine(propositionId)
    }

    /** Remembers the comparison a sweep handed the policy. */
    private class RecordingPolicy(
        private val delegate: DriftQuarantinePolicy = MentionTypeDriftQuarantinePolicy(),
    ) : DriftQuarantinePolicy {

        var evaluatedDiff: MetamodelDiff? = null
            private set

        override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult {
            evaluatedDiff = diff
            return delegate.evaluate(diff, propositions)
        }

        override fun candidateMentionTypes(diff: MetamodelDiff): Set<String> =
            delegate.candidateMentionTypes(diff)
    }

    /** Captures every event handed to it, in order. */
    private class RecordingDiceEventListener : DiceEventListener {
        val events = mutableListOf<DiceEvent>()
        override fun onEvent(event: DiceEvent) {
            events += event
        }
    }

    /**
     * Somewhere for a drift check to write its report. These tests read what the sweep did, so a
     * plain list is enough; `dice-metamodel`'s own suite is where the store contract is exercised.
     */
    private class InMemoryDriftReportStore : DriftReportStore {

        private val reports = mutableListOf<DriftReport>()

        override fun saveDriftReport(report: DriftReport) {
            reports += report
        }

        override fun driftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
            page(limit, since) { it.schemaName == schemaName }

        override fun globalDriftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
            page(limit, since) { it.schemaName == schemaName && it.contextId == null }

        override fun driftReportsInContext(
            schemaName: String,
            contextId: ContextId,
            limit: Int,
            since: Instant?,
        ): List<DriftReport> = page(limit, since) { it.schemaName == schemaName && it.contextId == contextId }

        private fun page(limit: Int, since: Instant?, scope: (DriftReport) -> Boolean): List<DriftReport> {
            require(limit > 0) { "limit must be positive, but was $limit" }
            return reports
                .filter(scope)
                .filter { since == null || !it.capturedAt.isBefore(since) }
                .sortedByDescending { it.capturedAt }
                .take(limit)
        }
    }
}

/**
 * A governed domain type. Declared through `JvmType`, so its declared name is the fully qualified
 * class name while a graph writes the simple label `Person`.
 */
private data class Person(val name: String, val age: Int)

/** The ungoverned counterpart: the host's dictionary names it and the selector leaves it out. */
private data class Sighting(val where: String, val about: Person)
