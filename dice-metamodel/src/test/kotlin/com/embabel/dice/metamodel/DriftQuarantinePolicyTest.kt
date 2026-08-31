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
import com.embabel.agent.core.JvmType
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
import org.junit.jupiter.api.Assertions.assertThrows
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

    /**
     * A stamp built field by field. The rename cases need declared former names and exact property
     * signatures, which a `DynamicType` round-trip can't express. A type's own name is one of its
     * labels, which is what a real stamp holds and what makes a rename's own-name label swap appear.
     */
    private fun versionOf(
        types: List<String>,
        properties: Map<String, Set<PropertySignature>> = emptyMap(),
        aliases: Map<String, Set<String>> = emptyMap(),
    ): MetamodelVersion = MetamodelVersion(
        schemaName = "test",
        entityTypeNames = types,
        entityTypeLabels = types.associateWith { setOf(it) },
        entityTypeProperties = types.associateWith { properties[it].orEmpty() },
        relationshipNames = emptyList(),
        entityTypeAliases = aliases,
    )

    /** The same stamp, given as `typeName to signatures` pairs. */
    private fun versionOf(vararg types: Pair<String, Set<PropertySignature>>): MetamodelVersion =
        versionOf(types.map { it.first }, types.toMap())

    private fun valueProperty(
        name: String,
        type: String = "string",
        cardinality: Cardinality = Cardinality.ONE,
        aliases: Set<String> = emptySet(),
    ): PropertySignature = PropertySignature(name, PropertySignature.Kind.VALUE, type, cardinality, aliases)

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

    /**
     * A declared rename strands nothing on its own, and whatever else moved on a renamed type is
     * reported under the new name while the data still carries the old one.
     */
    @Nested
    inner class DeclaredRenames {

        @Test
        fun `a pure type rename quarantines nothing, under either name`() {
            val diff = differ.diff(
                versionOf(listOf("Person")),
                versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person"))),
            )
            assertEquals(
                listOf(MetamodelChange.EntityTypeRenamed("Person", "Human")),
                diff.changes,
                "sanity: the rename is the only change",
            )

            val result = policy.evaluate(
                diff,
                listOf(proposition("written before the rename", "Person"), proposition("written after", "Human")),
            )

            assertEquals(2, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }

        @Test
        fun `a pure property rename quarantines nothing`() {
            val diff = differ.diff(
                versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("age")))),
                versionOf(
                    listOf("Person"),
                    properties = mapOf("Person" to setOf(valueProperty("years", aliases = setOf("age")))),
                ),
            )
            assertEquals(1, diff.renamedProperties.size, "sanity: the rename paired: ${diff.changes}")

            val result = policy.evaluate(diff, listOf(proposition("Alice is 40", "Person")))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }

        @Test
        fun `an alias-only change never quarantines`() {
            val diff = differ.diff(
                versionOf(listOf("Person")),
                versionOf(listOf("Person"), aliases = mapOf("Person" to setOf("Individual"))),
            )
            assertEquals(1, diff.entityTypeAliasChanges.size, "sanity: ${diff.changes}")

            val result = policy.evaluate(
                diff,
                listOf(proposition("Alice is a person", "Person"), proposition("Bob", "Individual")),
            )

            assertEquals(2, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }

        @Test
        fun `a retired alias never quarantines`() {
            val diff = differ.diff(
                versionOf(listOf("Person"), aliases = mapOf("Person" to setOf("Individual"))),
                versionOf(listOf("Person")),
            )
            assertEquals(1, diff.entityTypeAliasChanges.size, "sanity: ${diff.changes}")

            val result = policy.evaluate(diff, listOf(proposition("Bob", "Individual")))

            assertEquals(1, result.conforming.size)
        }

        @Test
        fun `a lossy change on a renamed type quarantines the old name and the new one`() {
            val diff = differ.diff(
                versionOf(
                    listOf("Person"),
                    properties = mapOf("Person" to setOf(valueProperty("age"), valueProperty("email"))),
                ),
                versionOf(
                    listOf("Human"),
                    properties = mapOf("Human" to setOf(valueProperty("age"))),
                    aliases = mapOf("Human" to setOf("Person")),
                ),
            )

            val result = policy.evaluate(
                diff,
                listOf(
                    proposition("old data", "Person"),
                    proposition("new data", "Human"),
                    proposition("untouched", "Company"),
                ),
            )

            assertEquals(2, result.quarantined.size)
            assertEquals(1, result.conforming.size)

            val underOldName = result.quarantined.first { it.affectedMentionTypes.contains("Person") }
            val reason = reasonOf(underOldName)
            assertTrue(reason.contains("email"), "the reason should name the lost property: $reason")
            assertTrue(
                reason.contains("Person") && reason.contains("Human"),
                "and should say which current type the old name resolves to: $reason",
            )
        }

        @Test
        fun `a lossy change two renames deep still catches the oldest name`() {
            // A became B became C, and the stamp for B was never diffed against. Former names
            // accumulate, so C declares both and every name the type has gone by is checked.
            val diff = differ.diff(
                versionOf("A" to setOf(valueProperty("x"), valueProperty("y"))),
                versionOf(
                    listOf("C"),
                    properties = mapOf("C" to setOf(valueProperty("x"))),
                    aliases = mapOf("C" to setOf("A", "B")),
                ),
            )
            assertEquals(1, diff.renamedEntityTypes.size, "sanity: A paired with C: ${diff.changes}")

            val result = policy.evaluate(
                diff,
                listOf(
                    proposition("oldest", "A"),
                    proposition("intermediate", "B"),
                    proposition("current", "C"),
                ),
            )

            assertEquals(3, result.quarantined.size, "every former name labels data the change stranded")
            assertEquals(0, result.conforming.size)
        }

        @Test
        fun `a lossy change in a later diff still reaches data under the name the type dropped`() {
            // The rename and the loss land in different releases. Stamp 2 renamed Person to Human;
            // stamp 3 only drops a property, so this diff carries no rename at all — while the graph
            // still holds nodes labelled Person and the declaration still says Human used to be one.
            // Matching reads the declared alias map, so it doesn't depend on the rename being here.
            val renamed = versionOf(
                listOf("Human"),
                properties = mapOf("Human" to setOf(valueProperty("age"), valueProperty("email"))),
                aliases = mapOf("Human" to setOf("Person")),
            )
            val trimmed = versionOf(
                listOf("Human"),
                properties = mapOf("Human" to setOf(valueProperty("age"))),
                aliases = mapOf("Human" to setOf("Person")),
            )
            val diff = differ.diff(renamed, trimmed)
            assertTrue(diff.renamedEntityTypes.isEmpty(), "sanity: no rename in this diff: ${diff.changes}")

            val result = policy.evaluate(
                diff,
                listOf(proposition("written before the rename", "Person"), proposition("written after", "Human")),
            )

            assertEquals(2, result.quarantined.size)
            assertTrue(reasonOf(result.quarantined.first { it.affectedMentionTypes.contains("Person") })
                .contains("Human"), "the reason should still resolve the old name")
        }

        @Test
        fun `a later diff reaches every former name, however many renames deep`() {
            val settled = { properties: Set<PropertySignature> ->
                versionOf(
                    listOf("C"),
                    properties = mapOf("C" to properties),
                    aliases = mapOf("C" to setOf("A", "B")),
                )
            }
            val diff = differ.diff(
                settled(setOf(valueProperty("x"), valueProperty("y"))),
                settled(setOf(valueProperty("x"))),
            )
            assertTrue(diff.renamedEntityTypes.isEmpty(), "sanity: no rename in this diff: ${diff.changes}")

            val result = policy.evaluate(
                diff,
                listOf(proposition("oldest", "A"), proposition("intermediate", "B"), proposition("current", "C")),
            )

            assertEquals(3, result.quarantined.size)
        }

        @Test
        fun `a later diff with nothing lossy leaves former-name data alone`() {
            val settled = { properties: Set<PropertySignature> ->
                versionOf(
                    listOf("Human"),
                    properties = mapOf("Human" to properties),
                    aliases = mapOf("Human" to setOf("Person")),
                )
            }
            val diff = differ.diff(
                settled(setOf(valueProperty("age"))),
                settled(setOf(valueProperty("age"), valueProperty("email"))),
            )
            assertTrue(diff.changes.isNotEmpty(), "sanity: the diff is non-empty but additive")

            val result = policy.evaluate(diff, listOf(proposition("old data", "Person")))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }

        @Test
        fun `a removed type takes its former names down with it`() {
            // A became C two stamps ago, so C carries {A, B}. Now C is deleted outright. The newer
            // version has no entry for C at all, so its former names have to come off the older
            // side; otherwise data labelled A or B conforms while nothing declared describes it.
            val from = versionOf(
                listOf("C", "Keep"),
                aliases = mapOf("C" to setOf("A", "B")),
            )
            val diff = differ.diff(from, versionOf(listOf("Keep")))
            assertEquals(setOf("C"), diff.removedEntityTypes, "sanity: only C is named as removed")

            val result = policy.evaluate(
                diff,
                listOf(
                    proposition("oldest", "A"),
                    proposition("intermediate", "B"),
                    proposition("current", "C"),
                    proposition("unaffected", "Keep"),
                ),
            )

            assertEquals(3, result.quarantined.size)
            assertEquals(1, result.conforming.size)

            val underOldestName = result.quarantined.first { it.affectedMentionTypes.contains("A") }
            val reason = reasonOf(underOldestName)
            assertTrue(reason.contains("C"), "the reason should name the removed type: $reason")
            assertTrue(reason.contains("A"), "and the former name it was matched under: $reason")
        }

        @Test
        fun `a former name the newer version reuses as a live type is not swept by the removal`() {
            // Retiring C frees the name A, and declaring a fresh type called A is legal from then
            // on. Something declared does describe data labelled A, so the removal doesn't reach it.
            val from = versionOf(listOf("C", "Keep"), aliases = mapOf("C" to setOf("A", "B")))
            val diff = differ.diff(from, versionOf(listOf("A", "Keep")))
            assertEquals(setOf("C"), diff.removedEntityTypes, "sanity: C is removed, not renamed")
            assertEquals(setOf("A"), diff.addedEntityTypes)

            val result = policy.evaluate(
                diff,
                listOf(proposition("reused name", "A"), proposition("intermediate", "B")),
            )

            assertEquals(1, result.conforming.size, "A names a type the schema declares")
            assertEquals(1, result.quarantined.size)
            assertTrue(result.quarantined.single().affectedMentionTypes.contains("B"))
        }

        @Test
        fun `a removed type with no former names behaves as it always did`() {
            val diff = differ.diff(versionOf(listOf("Gone", "Keep")), versionOf(listOf("Keep")))

            val result = policy.evaluate(
                diff,
                listOf(proposition("stranded", "Gone"), proposition("fine", "Keep")),
            )

            assertEquals(1, result.quarantined.size)
            assertEquals(1, result.conforming.size)
        }

        @Test
        fun `a deliberately retired former name stops matching`() {
            // Retiring an alias is a declaration that the schema no longer claims the name. From
            // then on, data still carrying it is reported by the observed-side comparison as
            // ordinary undeclared drift rather than resolved back to the type that dropped it.
            val settled = { formerNames: Set<String>, properties: Set<PropertySignature> ->
                versionOf(
                    listOf("Human"),
                    properties = mapOf("Human" to properties),
                    aliases = if (formerNames.isEmpty()) emptyMap() else mapOf("Human" to formerNames),
                )
            }
            val diff = differ.diff(
                settled(setOf("Person"), setOf(valueProperty("age"), valueProperty("email"))),
                settled(emptySet(), setOf(valueProperty("age"))),
            )
            assertEquals(1, diff.modifiedEntityTypes.size, "sanity: email was dropped: ${diff.changes}")

            val result = policy.evaluate(
                diff,
                listOf(proposition("old data", "Person"), proposition("new data", "Human")),
            )

            assertEquals(1, result.quarantined.size)
            assertTrue(result.quarantined.single().affectedMentionTypes.contains("Human"))
            assertEquals(1, result.conforming.size, "the retired name is no longer the schema's to claim")
        }

        @Test
        fun `a former name claimed by two renamed types is checked against both`() {
            // The declaration guard refuses an alias naming a type the schema still declares, and
            // says nothing about two live types both claiming one retired name. B is ambiguous, so
            // a lossy change on either type quarantines data labelled B.
            val diff = differ.diff(
                versionOf(
                    listOf("A", "E"),
                    properties = mapOf("E" to setOf(valueProperty("kept"), valueProperty("dropped"))),
                ),
                versionOf(
                    listOf("C", "D"),
                    properties = mapOf("D" to setOf(valueProperty("kept"))),
                    aliases = mapOf("C" to setOf("A", "B"), "D" to setOf("B", "E")),
                ),
            )
            assertEquals(2, diff.renamedEntityTypes.size, "sanity: both types paired: ${diff.changes}")

            val result = policy.evaluate(
                diff,
                listOf(proposition("ambiguous", "B"), proposition("unambiguous", "A")),
            )

            assertEquals(1, result.quarantined.size)
            assertTrue(result.quarantined.single().affectedMentionTypes.contains("B"))
            assertEquals(1, result.conforming.size, "C lost nothing, so data under A is fine")
        }

        @Test
        fun `a narrowing disguised inside a property rename still quarantines`() {
            val diff = differ.diff(
                versionOf("Person" to setOf(valueProperty("age", "integer", Cardinality.LIST))),
                versionOf(
                    "Person" to setOf(
                        valueProperty("years", "integer", Cardinality.ONE, aliases = setOf("age")),
                    ),
                ),
            )
            assertEquals(1, diff.renamedProperties.size, "sanity: it paired as a rename: ${diff.changes}")
            assertTrue(diff.modifiedEntityTypes.isEmpty(), "sanity: nothing reported as removed")

            val result = policy.evaluate(diff, listOf(proposition("Alice is 40", "Person")))

            assertEquals(1, result.quarantined.size, "a list of values does not fit in a single one")
            val reason = reasonOf(result.quarantined.single())
            assertTrue(reason.contains("age") && reason.contains("years"), "name both sides: $reason")
        }

        @Test
        fun `a type change disguised inside a property rename still quarantines`() {
            val diff = differ.diff(
                versionOf("Person" to setOf(valueProperty("age", "integer"))),
                versionOf("Person" to setOf(valueProperty("years", "string", aliases = setOf("age")))),
            )

            assertEquals(1, policy.evaluate(diff, listOf(proposition("Alice", "Person"))).quarantined.size)
        }

        @Test
        fun `a value-to-reference flip disguised inside a property rename still quarantines`() {
            val diff = differ.diff(
                versionOf("Person" to setOf(valueProperty("employer", "string"))),
                versionOf(
                    "Person" to setOf(
                        PropertySignature(
                            "worksAt",
                            PropertySignature.Kind.REFERENCE,
                            "Company",
                            Cardinality.ONE,
                            setOf("employer"),
                        ),
                    ),
                ),
            )

            assertEquals(1, policy.evaluate(diff, listOf(proposition("Alice", "Person"))).quarantined.size)
        }

        @Test
        fun `a property renamed and safely widened in one step does not quarantine`() {
            val diff = differ.diff(
                versionOf("Person" to setOf(valueProperty("age", "int"))),
                versionOf("Person" to setOf(valueProperty("years", "long", aliases = setOf("age")))),
            )
            assertEquals(1, diff.renamedProperties.size, "sanity: ${diff.changes}")

            assertEquals(0, policy.evaluate(diff, listOf(proposition("Alice", "Person"))).quarantined.size)
        }

        @Test
        fun `a lossy renamed property on a renamed type reaches data under the old type name`() {
            val diff = differ.diff(
                versionOf("Person" to setOf(valueProperty("age", "integer", Cardinality.LIST))),
                versionOf(
                    listOf("Human"),
                    properties = mapOf(
                        "Human" to setOf(
                            valueProperty("years", "integer", Cardinality.ONE, aliases = setOf("age")),
                        ),
                    ),
                    aliases = mapOf("Human" to setOf("Person")),
                ),
            )

            val result = policy.evaluate(diff, listOf(proposition("old data", "Person")))

            assertEquals(1, result.quarantined.size)
            assertTrue(result.quarantined.single().affectedMentionTypes.contains("Person"))
        }

        @Test
        fun `renaming a referenced type does not quarantine the referrer`() {
            // The rename propagates into every referrer's signature and every child's labels. The
            // differ folds that propagation into the rename, so none of it reaches this policy.
            val employer = { target: String ->
                PropertySignature("employer", PropertySignature.Kind.REFERENCE, target, Cardinality.ONE)
            }
            val diff = differ.diff(
                versionOf(listOf("Company", "Person"), properties = mapOf("Person" to setOf(employer("Company")))),
                versionOf(
                    listOf("Person", "Employer"),
                    properties = mapOf("Person" to setOf(employer("Employer"))),
                    aliases = mapOf("Employer" to setOf("Company")),
                ),
            )

            val result = policy.evaluate(
                diff,
                listOf(proposition("Alice works at Acme", "Person", "Company")),
            )

            assertEquals(1, result.conforming.size, "the rename's own propagation is not loss")
            assertEquals(0, result.quarantined.size)
        }

        @Test
        fun `an unrelated label lost on a renamed type still quarantines`() {
            // Only the own-name swap folds into the rename. A parent label that genuinely went away
            // is reported on the paired type and is judged normally.
            val from = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Person"),
                entityTypeLabels = mapOf("Person" to setOf("Person", "Agent")),
                entityTypeProperties = mapOf("Person" to emptySet()),
                relationshipNames = emptyList(),
            )
            val to = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Human"),
                entityTypeLabels = mapOf("Human" to setOf("Human")),
                entityTypeProperties = mapOf("Human" to emptySet()),
                relationshipNames = emptyList(),
                entityTypeAliases = mapOf("Human" to setOf("Person")),
            )

            val result = policy.evaluate(differ.diff(from, to), listOf(proposition("old data", "Person")))

            assertEquals(1, result.quarantined.size)
            assertTrue(reasonOf(result.quarantined.single()).contains("Agent"))
        }
    }

    /**
     * The type-widening allow-list. Iceberg permits the same four promotions on a table column: every
     * value of the older type has an exact representation in the newer one, so nothing already
     * written needs rewriting or can fail to read back.
     */
    @Nested
    inner class TypeWideningAllowList {

        private fun evaluateTypeChange(
            fromType: String,
            toType: String,
            cardinality: Cardinality = Cardinality.ONE,
            kind: PropertySignature.Kind = PropertySignature.Kind.VALUE,
        ): QuarantineResult {
            val diff = differ.diff(
                versionOf("Person" to setOf(PropertySignature("age", kind, fromType, Cardinality.ONE))),
                versionOf("Person" to setOf(PropertySignature("age", kind, toType, cardinality))),
            )
            assertTrue(diff.propertySignatureChanges.isNotEmpty(), "sanity: the differ saw a change")
            return policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))
        }

        /**
         * The allow-list is written in terms of the type names a stamp actually carries, and those
         * come out of the upstream dictionary's JVM reflection. This renders real declarations
         * through the same `PropertySignature.of` a stamp uses and asserts each of the four pairs is
         * spelled the way the list spells it, so a rendering change upstream fails here instead of
         * quietly emptying the list.
         */
        @Test
        fun `the allow-list is spelled the way real declarations render`() {
            val rendered = JvmType(WideningFixture::class.java).properties
                .associate { it.name to PropertySignature.of(it) }

            assertEquals("int", rendered.getValue("primitiveInt").type)
            assertEquals("long", rendered.getValue("primitiveLong").type)
            assertEquals("float", rendered.getValue("primitiveFloat").type)
            assertEquals("double", rendered.getValue("primitiveDouble").type)
            assertEquals("Integer", rendered.getValue("boxedInt").type)
            assertEquals("Long", rendered.getValue("boxedLong").type)
            assertEquals("Float", rendered.getValue("boxedFloat").type)
            assertEquals("Double", rendered.getValue("boxedDouble").type)

            rendered.values.forEach {
                assertEquals(PropertySignature.Kind.VALUE, it.kind, "a number is a value, not a reference: $it")
            }

            assertEquals(
                mapOf("int" to "long", "float" to "double", "Integer" to "Long", "Float" to "Double"),
                MentionTypeDriftQuarantinePolicy.SAFE_TYPE_WIDENINGS,
            )

            val renderedNames = rendered.values.mapTo(mutableSetOf()) { it.type }
            MentionTypeDriftQuarantinePolicy.SAFE_TYPE_WIDENINGS.forEach { (before, after) ->
                assertTrue(before in renderedNames, "'$before' is no longer a name any declaration renders")
                assertTrue(after in renderedNames, "'$after' is no longer a name any declaration renders")
            }
        }

        @Test
        fun `each allow-listed widening leaves the proposition alone`() {
            MentionTypeDriftQuarantinePolicy.SAFE_TYPE_WIDENINGS.forEach { (before, after) ->
                assertEquals(
                    0,
                    evaluateTypeChange(before, after).quarantined.size,
                    "$before -> $after holds everything it held before",
                )
            }
        }

        @Test
        fun `the same pairs reversed are narrowing and quarantine`() {
            MentionTypeDriftQuarantinePolicy.SAFE_TYPE_WIDENINGS.forEach { (before, after) ->
                assertEquals(
                    1,
                    evaluateTypeChange(after, before).quarantined.size,
                    "$after -> $before drops range",
                )
            }
        }

        @Test
        fun `a boxing or nullability flip is not a widening`() {
            assertEquals(1, evaluateTypeChange("int", "Long").quarantined.size, "int -> Long boxes")
            assertEquals(1, evaluateTypeChange("Integer", "long").quarantined.size, "Integer -> long drops null")
            assertEquals(1, evaluateTypeChange("float", "Double").quarantined.size)
            assertEquals(1, evaluateTypeChange("Float", "double").quarantined.size)
        }

        @Test
        fun `a promotion outside the list still quarantines`() {
            assertEquals(1, evaluateTypeChange("int", "double").quarantined.size)
            assertEquals(1, evaluateTypeChange("int", "float").quarantined.size)
            assertEquals(1, evaluateTypeChange("long", "double").quarantined.size)
            assertEquals(1, evaluateTypeChange("Integer", "Double").quarantined.size)
            assertEquals(1, evaluateTypeChange("string", "integer").quarantined.size)
        }

        @Test
        fun `a widening that also shrinks cardinality quarantines`() {
            val diff = differ.diff(
                versionOf("Person" to setOf(valueProperty("age", "int", Cardinality.LIST))),
                versionOf("Person" to setOf(valueProperty("age", "long", Cardinality.ONE))),
            )

            assertEquals(1, policy.evaluate(diff, listOf(proposition("Alice", "Person"))).quarantined.size)
        }

        @Test
        fun `the allow-list does not reach reference targets`() {
            // Contrived names, deliberately: the guard being tested is that the allow-list is scoped
            // to VALUE properties. An entity type is never a promotion of another entity type, whatever
            // the two are called.
            assertEquals(
                1,
                evaluateTypeChange("int", "long", kind = PropertySignature.Kind.REFERENCE).quarantined.size,
                "a reference target naming a different type is lossy however it is spelled",
            )
        }

        @Test
        fun `the allow-list is not mutable through the getter`() {
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (MentionTypeDriftQuarantinePolicy.SAFE_TYPE_WIDENINGS as MutableMap<String, String>)
                    .put("short", "int")
            }
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

/**
 * A real declaration carrying one field per type in the widening allow-list, in both the primitive
 * and the boxed spelling. Reflected through `JvmType`, which is the path a `DataDictionary` takes to
 * reach `PropertySignature.of`, so the type names it produces are the ones a stamp stores.
 *
 * A Kotlin `Int` compiles to a primitive field and an `Int?` to a boxed one, which is how one class
 * yields all eight names.
 */
private data class WideningFixture(
    val primitiveInt: Int,
    val primitiveLong: Long,
    val primitiveFloat: Float,
    val primitiveDouble: Double,
    val boxedInt: Int?,
    val boxedLong: Long?,
    val boxedFloat: Float?,
    val boxedDouble: Double?,
)
