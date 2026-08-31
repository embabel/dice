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
package com.embabel.dice.metamodel

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.ContextId

class DriftQuarantinePolicyTest {

    private val contextId = ContextId("test-context")
    private lateinit var policy: MentionTypeDriftQuarantinePolicy
    private lateinit var differ: MetamodelDiffer

    @BeforeEach
    fun setUp() {
        policy = MentionTypeDriftQuarantinePolicy()
        differ = StructuralMetamodelDiffer()
    }

    private fun schemaWith(vararg typeNames: String): DataDictionary =
        DataDictionary.fromDomainTypes("test", typeNames.map { DynamicType(name = it) })

    private fun proposition(
        text: String,
        vararg mentionTypes: String,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = mentionTypes.map { type ->
            EntityMention(span = type.lowercase(), type = type, role = MentionRole.SUBJECT)
        },
        confidence = 0.9,
    ).withStatus(status)

    private fun reasonOf(decision: QuarantineDecision.Quarantined): String =
        decision.proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON] as String

    @Nested
    inner class NothingLossy {

        @Test
        fun `an empty diff leaves everything conforming`() {
            val diff = differ.diff(schemaWith("Person", "Company"), schemaWith("Person", "Company"))

            val result = policy.evaluate(
                diff,
                listOf(
                    proposition("Alice works at Acme", "Person", "Company"),
                    proposition("Bob likes coffee", "Person"),
                ),
            )

            assertEquals(2, result.conforming.size)
            assertEquals(0, result.quarantined.size)
            result.conforming.forEach { assertEquals(PropositionStatus.ACTIVE, it.proposition.status) }
        }

        @Test
        fun `an empty proposition list produces an empty result`() {
            val diff = differ.diff(schemaWith("Person"), schemaWith())

            assertEquals(0, policy.evaluate(diff, emptyList()).total)
        }
    }

    @Nested
    inner class RemovedTypes {

        @Test
        fun `a proposition mentioning a removed type is quarantined`() {
            val diff = differ.diff(schemaWith("Person", "LegacyType"), schemaWith("Person"))

            val result = policy.evaluate(
                diff,
                listOf(proposition("legacy entity stuff", "LegacyType"), proposition("Alice is a person", "Person")),
            )

            assertEquals(1, result.conforming.size)
            assertEquals(1, result.quarantined.size)
            val decision = result.quarantined.single()
            assertEquals(PropositionStatus.STALE, decision.proposition.status)
            assertTrue(decision.affectedMentionTypes.contains("LegacyType"))
            assertNotNull(decision.proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `a conforming proposition stays ACTIVE and unannotated`() {
            val diff = differ.diff(schemaWith("Person", "Removed"), schemaWith("Person"))

            val result = policy.evaluate(diff, listOf(proposition("Alice is active", "Person")))

            assertEquals(1, result.conforming.size)
            val kept = result.conforming.single().proposition
            assertEquals(PropositionStatus.ACTIVE, kept.status)
            assertNull(kept.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `one bad mention among several is enough`() {
            val diff = differ.diff(schemaWith("Person", "Company", "OldType"), schemaWith("Person", "Company"))

            val result = policy.evaluate(diff, listOf(proposition("Alice at Acme via OldType", "Person", "OldType")))

            assertEquals(1, result.quarantined.size)
            assertTrue(result.quarantined.single().affectedMentionTypes.contains("OldType"))
        }

        @Test
        fun `the reason names the removed type`() {
            val diff = differ.diff(schemaWith("Person", "DeprecatedEntity"), schemaWith("Person"))

            val result = policy.evaluate(diff, listOf(proposition("something deprecated", "DeprecatedEntity")))

            val reason = reasonOf(result.quarantined.single())
            assertTrue(reason.contains("DeprecatedEntity"), "the reason should name the type: $reason")
        }

        @Test
        fun `quarantine never touches the original`() {
            val diff = differ.diff(schemaWith("Person", "Removed"), schemaWith("Person"))
            val original = proposition("entity with removed type", "Removed")

            val result = policy.evaluate(diff, listOf(original))

            assertEquals(PropositionStatus.STALE, result.quarantined.single().proposition.status)
            assertEquals(PropositionStatus.ACTIVE, original.status, "the caller's copy must be untouched")
            assertNull(original.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `every proposition seen comes back somewhere`() {
            val diff = differ.diff(schemaWith("Person", "Removed"), schemaWith("Person"))

            val result = policy.evaluate(
                diff,
                listOf(proposition("safe", "Person"), proposition("affected", "Removed")),
            )

            assertEquals(2, result.allPropositions.size)
            assertEquals(2, result.total)
        }
    }

    @Nested
    inner class LostShape {

        private fun personWith(parents: List<String> = emptyList(), props: List<String> = emptyList()): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        parents = parents.map { DynamicType(name = it) },
                        ownProperties = props.map { ValuePropertyDefinition(it) },
                    ),
                ),
            )

        @Test
        fun `losing a label quarantines`() {
            val diff = differ.diff(personWith(parents = listOf("Agent")), personWith())

            val result = policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))

            assertEquals(1, result.quarantined.size)
            val decision = result.quarantined.single()
            assertEquals(PropositionStatus.STALE, decision.proposition.status)
            assertTrue(decision.affectedMentionTypes.contains("Person"))
            assertTrue(reasonOf(decision).contains("Agent"), "the reason should name the lost label")
        }

        @Test
        fun `losing a property quarantines, and the reason names it plainly`() {
            val diff = differ.diff(personWith(props = listOf("age", "email")), personWith(props = listOf("age")))

            val result = policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))

            assertEquals(1, result.quarantined.size)
            val reason = reasonOf(result.quarantined.single())
            assertTrue(reason.contains("email"), "the reason should name the lost property: $reason")
            assertTrue(
                !reason.contains("PropertySignature("),
                "a reason is read by a person; it should not render a signature's constructor: $reason",
            )
        }

        @Test
        fun `an additive change never quarantines`() {
            val diff = differ.diff(personWith(), personWith(parents = listOf("Agent"), props = listOf("age")))
            assertTrue(diff.modifiedEntityTypes.isNotEmpty(), "sanity: the type was seen as modified")

            val result = policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }
    }

    @Nested
    inner class NarrowedPropertySignatures {

        /** A `Person` whose single `age` property has the given shape. */
        private fun personAged(type: String, cardinality: Cardinality): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("age", type = type, cardinality = cardinality)),
                    ),
                ),
            )

        private fun evaluateShapeChange(
            fromType: String,
            fromCardinality: Cardinality,
            toType: String,
            toCardinality: Cardinality,
        ): QuarantineResult {
            val diff = differ.diff(personAged(fromType, fromCardinality), personAged(toType, toCardinality))
            assertTrue(
                diff.propertySignatureChanges.isNotEmpty(),
                "sanity: the differ should have reported a signature change",
            )
            return policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))
        }

        @Test
        fun `a changed value type quarantines`() {
            val result = evaluateShapeChange("string", Cardinality.ONE, "integer", Cardinality.ONE)

            assertEquals(1, result.quarantined.size)
            val reason = reasonOf(result.quarantined.single())
            assertTrue(reason.contains("age"), "the reason should name the property: $reason")
            assertTrue(reason.contains("string") && reason.contains("integer"), "and both shapes: $reason")
        }

        @Test
        fun `a shrinking cardinality quarantines`() {
            val result = evaluateShapeChange("string", Cardinality.LIST, "string", Cardinality.ONE)

            assertEquals(1, result.quarantined.size, "a list of values does not fit in a single one")
        }

        @Test
        fun `an optional turning required quarantines`() {
            val result = evaluateShapeChange("string", Cardinality.OPTIONAL, "string", Cardinality.ONE)

            assertEquals(1, result.quarantined.size, "data extracted without the value no longer satisfies it")
        }

        @Test
        fun `a list collapsing to a set quarantines, since duplicates are dropped`() {
            val result = evaluateShapeChange("string", Cardinality.LIST, "string", Cardinality.SET)

            assertEquals(1, result.quarantined.size)
        }

        @Test
        fun `a widening cardinality does not quarantine`() {
            assertEquals(
                0,
                evaluateShapeChange("string", Cardinality.ONE, "string", Cardinality.LIST).quarantined.size,
                "one value fits in a list",
            )
            assertEquals(
                0,
                evaluateShapeChange("string", Cardinality.ONE, "string", Cardinality.OPTIONAL).quarantined.size,
                "a required value fits where an optional one is allowed",
            )
            assertEquals(
                0,
                evaluateShapeChange("string", Cardinality.SET, "string", Cardinality.LIST).quarantined.size,
                "a set fits in a list",
            )
        }

        @Test
        fun `a narrowed property on a type nobody mentions leaves the proposition alone`() {
            val diff = differ.diff(personAged("string", Cardinality.LIST), personAged("string", Cardinality.ONE))

            val result = policy.evaluate(diff, listOf(proposition("Acme is a company", "Company")))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }
    }

    @Nested
    inner class Idempotency {

        @Test
        fun `a second sweep leaves an already-quarantined proposition exactly as it was`() {
            val diff = differ.diff(schemaWith("Person", "RemovedType"), schemaWith("Person"))

            val first = policy.evaluate(diff, listOf(proposition("entity with removed type", "RemovedType")))
            assertEquals(1, first.quarantined.size)
            val stale = first.quarantined.single().proposition

            val second = policy.evaluate(diff, listOf(stale))

            assertEquals(
                0,
                second.conforming.size,
                "an already-quarantined proposition is not clean and must not be reported as conforming",
            )
            assertEquals(0, second.quarantined.size)
            assertEquals(1, second.alreadyQuarantined.size)
            assertEquals(1, second.total)

            val decision = second.alreadyQuarantined.single()
            assertEquals(
                stale.metadata[DiceMetadataKeys.QUARANTINE_REASON],
                decision.proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON],
            )
            assertEquals(stale.metadata[DiceMetadataKeys.QUARANTINE_REASON], decision.originalReason)
            assertEquals(PropositionStatus.STALE, decision.proposition.status)
            assertTrue(second.allPropositions.contains(decision.proposition))
        }

        @Test
        fun `an empty diff still reports an already-quarantined proposition as quarantined`() {
            // Guards against a "nothing lossy, so everything conforms" shortcut, which would skip
            // the already-quarantined check. Drift checks run on a schedule and most runs find
            // nothing, so that path is the common one, and quarantined records would come back
            // Conforming on almost every run.
            val lossyDiff = differ.diff(schemaWith("Person", "RemovedType"), schemaWith("Person"))
            val stale = policy
                .evaluate(lossyDiff, listOf(proposition("entity with removed type", "RemovedType")))
                .quarantined.single().proposition

            val emptyDiff = differ.diff(schemaWith("Person"), schemaWith("Person"))
            assertTrue(emptyDiff.isEmpty, "sanity: the second check found nothing")

            val result = policy.evaluate(emptyDiff, listOf(stale))

            assertEquals(0, result.conforming.size, "a quarantined proposition is never clean")
            assertEquals(1, result.alreadyQuarantined.size)
            assertEquals(1, result.total)
            val decision = result.alreadyQuarantined.single()
            assertEquals(stale.id, decision.proposition.id)
            assertEquals(PropositionStatus.STALE, decision.proposition.status)
            assertEquals(stale.metadata[DiceMetadataKeys.QUARANTINE_REASON], decision.originalReason)
        }

        @Test
        fun `a purely additive diff still reports an already-quarantined proposition as quarantined`() {
            val lossyDiff = differ.diff(schemaWith("Person", "RemovedType"), schemaWith("Person"))
            val stale = policy
                .evaluate(lossyDiff, listOf(proposition("entity with removed type", "RemovedType")))
                .quarantined.single().proposition
            val clean = proposition("Alice is a person", "Person")

            val additiveDiff = differ.diff(schemaWith("Person"), schemaWith("Person", "NewType"))
            assertTrue(additiveDiff.changes.isNotEmpty(), "sanity: the diff is non-empty but additive")

            val result = policy.evaluate(additiveDiff, listOf(clean, stale))

            assertEquals(1, result.conforming.size, "only the genuinely clean one conforms")
            assertEquals(clean.id, result.conforming.single().proposition.id)
            assertEquals(1, result.alreadyQuarantined.size)
            assertEquals(0, result.quarantined.size)
        }

        @Test
        fun `a clean proposition and an already-quarantined one land in different buckets`() {
            val diff = differ.diff(schemaWith("Person", "RemovedType"), schemaWith("Person"))
            val stale = policy
                .evaluate(diff, listOf(proposition("entity with removed type", "RemovedType")))
                .quarantined.single().proposition
            val clean = proposition("Alice is a person", "Person")

            val result = policy.evaluate(diff, listOf(clean, stale))

            assertEquals(1, result.conforming.size)
            assertEquals(clean.id, result.conforming.single().proposition.id)
            assertEquals(1, result.alreadyQuarantined.size)
            assertEquals(stale.id, result.alreadyQuarantined.single().proposition.id)
            assertEquals(0, result.quarantined.size)
            assertEquals(2, result.total)
        }

        @Test
        fun `a proposition made stale by something other than quarantine is still evaluated`() {
            // STALE alone isn't enough to skip one, because decay also makes propositions stale and
            // those carry no quarantine reason. Skipping on status alone would let drifted data
            // through.
            val diff = differ.diff(schemaWith("Person", "RemovedType"), schemaWith("Person"))
            val staleByDecay = proposition("aged out", "RemovedType", status = PropositionStatus.STALE)

            val result = policy.evaluate(diff, listOf(staleByDecay))

            assertEquals(1, result.quarantined.size)
            assertEquals(0, result.alreadyQuarantined.size)
        }
    }

    @Nested
    inner class WithoutMentions {

        @Test
        fun `a proposition with no mentions conforms even when types were removed`() {
            val diff = differ.diff(schemaWith("Person", "RemovedType"), schemaWith("Person"))

            val result = policy.evaluate(diff, listOf(proposition("A fact with no entity mentions")))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
            assertEquals(PropositionStatus.ACTIVE, result.conforming.single().proposition.status)
        }
    }
}
