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
package com.embabel.dice.common

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.filter.GroundedMentionFilter
import com.embabel.dice.common.filter.MentionFixtures
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The name rules: what the standard normalizer does to a person's name and to any other name,
 * how the rules are tuned, and that resolution and the grounding check follow the normalizer
 * they are given.
 */
class NameNormalizerTest {

    private val person = listOf("Person")
    private val round = listOf("FundingRound")
    private val standard = NameNormalizer.standard()

    @Test
    fun `a person's name loses title, suffix and middle initial, and is put in first-last order`() {
        assertEquals("Watson", standard.normalize("Dr. Watson", person))
        assertEquals("John Smith", standard.normalize("John Smith Jr.", person))
        assertEquals("Lynda Coker", standard.normalize("Lynda M Coker", person))
        assertEquals("Lynda Coker", standard.normalize("Lynda M. Coker", person))
        assertEquals("Lynda Coker", standard.normalize("Coker, Lynda", person))
        assertEquals("Lynda Coker", standard.normalize("Coker, Lynda M.", person))
    }

    @Test
    fun `a name that is not a person's only has its whitespace collapsed`() {
        assertEquals("Series A Round", standard.normalize("  Series  A Round ", round))
        assertNotEquals(standard.normalize("Series A Round", round), standard.normalize("Series B Round", round))
        assertEquals("Paris, France", standard.normalize("Paris, France", listOf("Place")))
        assertEquals("Dr. Martens", standard.normalize("Dr. Martens", listOf("Company")))
        assertEquals("Henry IV", standard.normalize("Henry IV", listOf("Play")))
    }

    @Test
    fun `a middle initial is removed whatever script the name is written in`() {
        assertEquals("José García", standard.normalize("José M García", person))
        assertEquals("Élodie Durand", standard.normalize("Élodie É. Durand", person))
        // Scripts without capital letters have no initials to remove
        assertEquals("王 小明", standard.normalize("王 小明", person))
    }

    @Test
    fun `whitespace of any kind is collapsed`() {
        assertEquals("John Smith", standard.normalize("John Smith", person))
        assertEquals("東京 タワー", standard.normalize("東京　タワー", listOf("Place")))
        assertEquals("John Smith", NameNormalizer.whitespaceOnly().normalize(" John \t Smith ", person))
        assertEquals("Dr. Watson", NameNormalizer.whitespaceOnly().normalize("Dr. Watson", person))
    }

    @Test
    fun `a person label is recognized ignoring case and package`() {
        assertEquals("Watson", standard.normalize("Dr. Watson", listOf("com.acme.model.Person")))
        assertEquals("Watson", standard.normalize("Dr. Watson", listOf("Engineer", "PERSON")))
        assertEquals("Dr. Watson", standard.normalize("Dr. Watson", emptyList()))
    }

    @Test
    fun `the labels that mark a person can be chosen`() {
        val contacts = PersonNameNormalizer.english().withPersonLabels("Contact", "Employee")
        assertEquals("Watson", contacts.normalize("Dr. Watson", listOf("Contact")))
        assertEquals("Dr. Watson", contacts.normalize("Dr. Watson", person))
    }

    @Test
    fun `every name can be treated as a person's, which matches the old normalizeName`() {
        val everyName = PersonNameNormalizer.english().forEveryName()
        for (name in listOf("Dr. Watson", "John Smith Jr.", "Lynda M Coker", "Coker, Lynda M.", "Series A Round", "  Prof.  Einstein ")) {
            assertEquals(NormalizedNameCandidateSearcher.normalizeName(name), everyName.normalize(name, round), name)
        }
        assertEquals("Series Round", everyName.normalize("Series A Round", round))
    }

    @Test
    fun `titles and suffixes can be replaced, with or without their dots`() {
        val german = PersonNameNormalizer.english().withTitles("Herr", "Frau", "Dr.").withSuffixes("sen", "jun")
        assertEquals("Müller", german.normalize("Herr Müller", person))
        assertEquals("Müller", german.normalize("frau Müller", person))
        assertEquals("Müller", german.normalize("Dr. Müller", person))
        assertEquals("Hans Müller", german.normalize("Hans Müller sen.", person))
        // The English titles are gone
        assertEquals("Mr Müller", german.normalize("Mr Müller", person))
        // No titles at all
        assertEquals("Dr. Watson", PersonNameNormalizer.english().withTitles().normalize("Dr. Watson", person))
    }

    @Test
    fun `middle initials and name order can be kept`() {
        assertEquals("Lynda M Coker", PersonNameNormalizer.english().withMiddleInitialsKept().normalize("Lynda M Coker", person))
        assertEquals("Coker, Lynda", PersonNameNormalizer.english().withNameOrderKept().normalize("Coker, Lynda", person))
    }

    @Test
    fun `a block and a chain build the same normalizer, and steps leave the original alone`() {
        val english = PersonNameNormalizer.english()
        val chain = english
            .withPersonLabels("Person", "Contact")
            .withTitles("Herr", "Frau")
            .withSuffixes("sen")
            .withMiddleInitialsKept()
            .withNameOrderKept()
        val block = PersonNameNormalizer {
            personLabels("Person", "Contact")
            titles("Herr", "Frau")
            suffixes("sen")
            stripMiddleInitials = false
            reorderLastFirst = false
        }
        assertEquals(chain, block)
        assertEquals(chain.hashCode(), block.hashCode())
        assertEquals(english, PersonNameNormalizer { })
        assertEquals(PersonNameNormalizer.english().forEveryName(), PersonNameNormalizer { everyName() })
        assertNotEquals(english, chain)
        assertEquals("Watson", english.normalize("Dr. Watson", person))
    }

    @Test
    fun `a custom normalizer is a lambda`() {
        val upper = NameNormalizer { name, _ -> name.uppercase() }
        assertEquals("WATSON", upper.normalize("Watson", person))
    }

    private val schema = DataDictionary.fromClasses("test", Person::class.java)

    private val veto: EntityResolver = object : EntityResolver {
        override fun resolve(suggestedEntities: SuggestedEntities, schema: DataDictionary): Resolutions<SuggestedEntityResolution> =
            Resolutions(suggestedEntities.chunkIds, suggestedEntities.suggestedEntities.map { VetoedEntity(it) })
    }

    private fun entity(id: String, name: String, vararg labels: String): NamedEntityData =
        SimpleNamedEntityData(id = id, name = name, description = name, labels = labels.toSet(), properties = emptyMap())

    private fun suggested(name: String, vararg labels: String) =
        SuggestedEntities(listOf(SuggestedEntity(labels = labels.toList(), name = name, summary = name, chunkId = "chunk-1")))

    @Test
    fun `known entities of another kind are not merged by person rules`() {
        val known = listOf(entity("b", "Series B Round", "FundingRound"), entity("w", "Watson", "Person"))
        val resolver = KnownEntityResolver(known, veto)

        assertInstanceOf(VetoedEntity::class.java, resolver.resolve(suggested("Series A Round", "FundingRound"), schema).resolutions.single())
        val doctor = resolver.resolve(suggested("Dr. Watson", "Person"), schema).resolutions.single()
        assertEquals("w", (doctor as ReferenceOnlyEntity).existing.id)

        // Treating every name as a person's brings the old merge back
        val everyName = resolver.withNameNormalizer(PersonNameNormalizer.english().forEveryName())
        val merged = everyName.resolve(suggested("Series A Round", "FundingRound"), schema).resolutions.single()
        assertEquals("b", (merged as ReferenceOnlyEntity).existing.id)
    }

    @Test
    fun `names with nothing left after normalization match nothing`() {
        val digitsOnly = NameNormalizer { name, _ -> name.filter { it.isLetter() } }
        val known = KnownEntityResolver(listOf(entity("a", "10001", "Account")), veto).withNameNormalizer(digitsOnly)
        assertInstanceOf(VetoedEntity::class.java, known.resolve(suggested("20002", "Account"), schema).resolutions.single())

        val inMemory = InMemoryEntityResolver().withNameNormalizer(digitsOnly)
        inMemory.resolve(suggested("10001", "Account"), schema)
        assertInstanceOf(NewEntity::class.java, inMemory.resolve(suggested("98765", "Account"), schema).resolutions.single())
    }

    @Test
    fun `the in-memory resolver follows its normalizer`() {
        val standard = InMemoryEntityResolver()
        standard.resolve(suggested("Paris, France", "Place"), schema)
        assertInstanceOf(NewEntity::class.java, standard.resolve(suggested("France Paris", "Place"), schema).resolutions.single())
        standard.resolve(suggested("Coker, Lynda", "Person"), schema)
        assertInstanceOf(ExistingEntity::class.java, standard.resolve(suggested("Lynda Coker", "Person"), schema).resolutions.single())

        val everyName = InMemoryEntityResolver().withNameNormalizer(PersonNameNormalizer.english().forEveryName())
        everyName.resolve(suggested("Paris, France", "Place"), schema)
        assertInstanceOf(ExistingEntity::class.java, everyName.resolve(suggested("France Paris", "Place"), schema).resolutions.single())

        val orderKept = InMemoryEntityResolver().withNameNormalizer(PersonNameNormalizer.english().withNameOrderKept())
        orderKept.resolve(suggested("Coker, Lynda", "Person"), schema)
        assertInstanceOf(NewEntity::class.java, orderKept.resolve(suggested("Lynda Coker", "Person"), schema).resolutions.single())
    }

    @Test
    fun `the grounding check and known-entity resolution agree under the same normalizer`() {
        val watson = KnownEntity(entity("w", "Watson", "Person"), "A known person")
        val context = MentionFixtures.context(watson)
        val proposition = SuggestedProposition(
            text = "Watson examined the room",
            mentions = listOf(SuggestedMention(span = "Dr. Watson", type = "Person", role = "SUBJECT")),
            confidence = 0.9,
        )
        fun linked(normalizer: NameNormalizer) =
            KnownEntityResolver(listOf(watson), veto).withNameNormalizer(normalizer)
                .resolve(suggested("Dr. Watson", "Person"), schema).resolutions.single() !is VetoedEntity

        for (normalizer in listOf(NameNormalizer.standard(), NameNormalizer.whitespaceOnly())) {
            val grounded = GroundedMentionFilter().withNameNormalizer(normalizer)
                .validateMentions(proposition, context).single() == ValidationResult.Valid
            assertEquals(linked(normalizer), grounded, normalizer.toString())
        }
        assertEquals(true, linked(NameNormalizer.standard()))
        assertEquals(false, linked(NameNormalizer.whitespaceOnly()))
    }
}
