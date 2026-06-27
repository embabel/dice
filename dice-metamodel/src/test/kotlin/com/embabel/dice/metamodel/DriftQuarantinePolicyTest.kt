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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.support.JaversMetamodelDiffer
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DriftQuarantinePolicyTest {

    private val contextId = ContextId("test-context")
    private lateinit var policy: MentionTypeDriftQuarantinePolicy
    private lateinit var differ: MetamodelDiffer

    @BeforeEach
    fun setUp() {
        policy = MentionTypeDriftQuarantinePolicy()
        differ = JaversMetamodelDiffer()
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

    @Nested
    inner class NoRemovedTypes {

        @Test
        fun `empty diff leaves all propositions conforming`() {
            val old = schemaWith("Person", "Company")
            val new = schemaWith("Person", "Company")
            val diff = differ.diff(old, new)

            val props = listOf(
                proposition("Alice works at Acme", "Person", "Company"),
                proposition("Bob likes coffee", "Person"),
            )
            val result = policy.evaluate(diff, props)

            assertEquals(2, result.conforming.size)
            assertEquals(0, result.quarantined.size)
            result.conforming.forEach { assertEquals(PropositionStatus.ACTIVE, it.proposition.status) }
        }
    }

    @Nested
    inner class WithRemovedTypes {

        @Test
        fun `proposition mentioning removed type is quarantined`() {
            val old = schemaWith("Person", "LegacyType")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            val affected = proposition("legacy entity stuff", "LegacyType")
            val safe = proposition("Alice is a person", "Person")

            val result = policy.evaluate(diff, listOf(affected, safe))

            assertEquals(1, result.conforming.size)
            assertEquals(1, result.quarantined.size)

            val decision = result.quarantined.single()
            assertEquals(PropositionStatus.STALE, decision.proposition.status)
            assertTrue(decision.affectedMentionTypes.contains("LegacyType"))
            assertNotNull(decision.proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `conforming propositions remain ACTIVE and unmodified`() {
            val old = schemaWith("Person", "Removed")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            val safe = proposition("Alice is active", "Person")
            val result = policy.evaluate(diff, listOf(safe))

            assertEquals(1, result.conforming.size)
            assertEquals(PropositionStatus.ACTIVE, result.conforming.single().proposition.status)
            assertNull(result.conforming.single().proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `proposition with multiple mentions is quarantined when any mention type is removed`() {
            val old = schemaWith("Person", "Company", "OldType")
            val new = schemaWith("Person", "Company")
            val diff = differ.diff(old, new)

            val mixed = proposition("Alice at Acme via OldType", "Person", "OldType")
            val result = policy.evaluate(diff, listOf(mixed))

            assertEquals(1, result.quarantined.size)
            assertTrue(result.quarantined.single().affectedMentionTypes.contains("OldType"))
        }

        @Test
        fun `quarantine reason references the removed type`() {
            val old = schemaWith("Person", "DeprecatedEntity")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            val prop = proposition("something with deprecated", "DeprecatedEntity")
            val result = policy.evaluate(diff, listOf(prop))

            val reason = result.quarantined.single().proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON] as String
            assertTrue(reason.contains("DeprecatedEntity"), "Reason should mention the type: $reason")
        }

        @Test
        fun `quarantine is non-destructive — original proposition is unchanged`() {
            val old = schemaWith("Person", "Removed")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            val original = proposition("entity with removed type", "Removed")
            val result = policy.evaluate(diff, listOf(original))

            val quarantined = result.quarantined.single().proposition
            // Returned copy is STALE
            assertEquals(PropositionStatus.STALE, quarantined.status)
            // Original is unchanged
            assertEquals(PropositionStatus.ACTIVE, original.status)
            assertNull(original.metadata[DiceMetadataKeys.QUARANTINE_REASON])
        }

        @Test
        fun `all propositions are returned via allPropositions`() {
            val old = schemaWith("Person", "Removed")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            val props = listOf(
                proposition("safe", "Person"),
                proposition("affected", "Removed"),
            )
            val result = policy.evaluate(diff, props)

            assertEquals(2, result.allPropositions.size)
            assertEquals(2, result.total)
        }
    }

    @Nested
    inner class WithLossyShapeChanges {

        private fun personWith(parents: List<String> = emptyList(), props: List<String> = emptyList()): DynamicType =
            DynamicType(
                name = "Person",
                parents = parents.map { DynamicType(name = it) },
                ownProperties = props.map { ValuePropertyDefinition(it) },
            )

        private fun schema(person: DynamicType): DataDictionary =
            DataDictionary.fromDomainTypes("test", listOf(person))

        @Test
        fun `proposition mentioning a type that lost a label is quarantined`() {
            val old = schema(personWith(parents = listOf("Agent")))   // labels {Person, Agent}
            val new = schema(personWith())                            // labels {Person}
            val diff = differ.diff(old, new)

            val result = policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))

            assertEquals(1, result.quarantined.size)
            val decision = result.quarantined.single()
            assertEquals(PropositionStatus.STALE, decision.proposition.status)
            assertTrue(decision.affectedMentionTypes.contains("Person"))
            val reason = decision.proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON] as String
            assertTrue(reason.contains("Agent"), "reason should name the lost label: $reason")
        }

        @Test
        fun `proposition mentioning a type that lost a property is quarantined`() {
            val old = schema(personWith(props = listOf("age", "email")))
            val new = schema(personWith(props = listOf("age")))
            val diff = differ.diff(old, new)

            val result = policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))

            assertEquals(1, result.quarantined.size)
            val reason = result.quarantined.single().proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON] as String
            assertTrue(reason.contains("email"), "reason should name the lost property: $reason")
        }

        @Test
        fun `additive-only change does not quarantine`() {
            // A type that GAINS a label and a property — non-lossy, so nothing is quarantined.
            val old = schema(personWith())
            val new = schema(personWith(parents = listOf("Agent"), props = listOf("age")))
            val diff = differ.diff(old, new)
            assertTrue(diff.modifiedEntityTypes.isNotEmpty(), "sanity: a modified type was detected")

            val result = policy.evaluate(diff, listOf(proposition("Alice is a person", "Person")))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
            assertEquals(PropositionStatus.ACTIVE, result.conforming.single().proposition.status)
        }
    }

    @Nested
    inner class EmptyInput {

        @Test
        fun `empty proposition list produces empty result`() {
            val old = schemaWith("Person")
            val new = schemaWith()
            val diff = differ.diff(old, new)

            val result = policy.evaluate(diff, emptyList())
            assertEquals(0, result.total)
        }
    }

    @Nested
    inner class Idempotency {

        @Test
        fun `already-quarantined proposition is not re-quarantined on a second sweep`() {
            val old = schemaWith("Person", "RemovedType")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            // First sweep quarantines the proposition.
            val original = proposition("entity with removed type", "RemovedType")
            val firstResult = policy.evaluate(diff, listOf(original))
            assertEquals(1, firstResult.quarantined.size)
            val alreadyQuarantined = firstResult.quarantined.single().proposition

            // Second sweep with the already-quarantined proposition: it should pass through
            // as conforming, preserving the original quarantine reason untouched.
            val secondResult = policy.evaluate(diff, listOf(alreadyQuarantined))
            assertEquals(1, secondResult.conforming.size, "already-quarantined proposition should be conforming on re-sweep")
            assertEquals(0, secondResult.quarantined.size)

            // The QUARANTINE_REASON from the first sweep is preserved.
            val preserved = secondResult.conforming.single().proposition
            assertEquals(
                alreadyQuarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON],
                preserved.metadata[DiceMetadataKeys.QUARANTINE_REASON],
            )
            // It passes through as conforming but stays STALE — a caller must not read "conforming"
            // as "active again".
            assertEquals(PropositionStatus.STALE, preserved.status)
        }
    }

    @Nested
    inner class PropositionsWithNoMentions {

        @Test
        fun `proposition with no mentions is conforming even when entity types are removed`() {
            // The removed type cannot match an empty mention set, so no quarantine.
            val old = schemaWith("Person", "RemovedType")
            val new = schemaWith("Person")
            val diff = differ.diff(old, new)

            val noMentionProp = proposition("A fact with no entity mentions")
            val result = policy.evaluate(diff, listOf(noMentionProp))

            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
            assertEquals(PropositionStatus.ACTIVE, result.conforming.single().proposition.status)
        }

        @Test
        fun `proposition with no mentions is conforming when diff is empty`() {
            val old = schemaWith("Person", "Company")
            val new = schemaWith("Person", "Company")
            val diff = differ.diff(old, new)

            val noMentionProp = proposition("A fact with no entity mentions")
            val result = policy.evaluate(diff, listOf(noMentionProp))

            assertEquals(1, result.total)
            assertEquals(1, result.conforming.size)
            assertEquals(0, result.quarantined.size)
        }
    }
}
