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
package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.ReferenceOnlyEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.VetoedEntity
import com.embabel.dice.text2graph.builder.Animal
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [KnownEntityResolver]'s name-match + label-widening
 * behaviour. The resolver was originally a strict "name AND label
 * intersection" matcher; that prevented multi-typed entities (e.g.
 * Airbnb resolved as `Saas` then re-suggested as `Trip` would create
 * a duplicate row instead of widening the existing one). The resolver
 * now matches by name alone and chooses between [ReferenceOnlyEntity]
 * (suggestion's labels already covered) and [ExistingEntity]
 * (suggestion would widen the type set).
 */
class KnownEntityResolverTest {

    private val schema = DataDictionary.fromClasses("test", Person::class.java, Animal::class.java)

    private val noOpDelegate: EntityResolver = object : EntityResolver {
        override fun resolve(
            suggestedEntities: SuggestedEntities,
            schema: DataDictionary,
        ): Resolutions<SuggestedEntityResolution> = Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = suggestedEntities.suggestedEntities.map { VetoedEntity(it) },
        )
    }

    private fun namedKnown(
        id: String,
        name: String,
        labels: Set<String>,
    ): com.embabel.agent.rag.model.NamedEntityData =
        SimpleNamedEntityData(
            id = id,
            name = name,
            description = name,
            labels = labels + setOf("__Entity__"),
            properties = emptyMap(),
        )

    private fun suggestion(
        name: String,
        labels: List<String>,
        chunkId: String = "chunk-1",
    ): SuggestedEntity = SuggestedEntity(
        labels = labels,
        name = name,
        summary = name,
        chunkId = chunkId,
    )

    @Test
    fun `name match with overlapping labels returns ReferenceOnlyEntity`() {
        // Existing Airbnb is already known as a Saas; the LLM
        // re-suggests it as a Saas — labels already cover, so the
        // resolver returns a read-only reference rather than firing a
        // label-widening update.
        val known = namedKnown("airbnb-1", "Airbnb", setOf("Saas"))
        val resolver = KnownEntityResolver(listOf(known), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Airbnb", listOf("Saas")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertTrue(resolution is ReferenceOnlyEntity, "Expected ReferenceOnlyEntity but got $resolution")
        assertEquals("airbnb-1", resolution.recommended?.id)
    }

    @Test
    fun `name match with widening labels returns ExistingEntity with merged labels`() {
        // Existing Airbnb is Saas; the LLM now suggests it is also a
        // Trip vendor. The resolver SHOULD return the existing row
        // wrapped as ExistingEntity, with both Saas and Trip in the
        // recommended labels — NOT create a duplicate, NOT veto the
        // type change.
        val known = namedKnown("airbnb-1", "Airbnb", setOf("Saas"))
        val resolver = KnownEntityResolver(listOf(known), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Airbnb", listOf("Trip")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertTrue(resolution is ExistingEntity, "Expected ExistingEntity but got $resolution")
        val labels = resolution.recommended!!.labels()
        assertTrue("Saas" in labels, "Expected Saas in widened labels, got $labels")
        assertTrue("Trip" in labels, "Expected Trip in widened labels, got $labels")
        assertEquals("airbnb-1", resolution.recommended!!.id)
    }

    @Test
    fun `name match with disjoint labels still widens rather than duplicating`() {
        // Existing Rod Johnson is an AssistantUser; the LLM suggests
        // a Person mention. The two label sets are disjoint, but the
        // name is the same — the user's KG row should gain the Person
        // label, not get cloned into a Person-only sibling row.
        val known = namedKnown("rod_johnson_assistant", "Rod Johnson", setOf("AssistantUser", "User"))
        val resolver = KnownEntityResolver(listOf(known), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Rod Johnson", listOf("Person")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertTrue(resolution is ExistingEntity, "Expected ExistingEntity but got $resolution")
        assertEquals("rod_johnson_assistant", resolution.recommended!!.id)
        assertTrue("Person" in resolution.recommended!!.labels())
    }

    @Test
    fun `multiple name matches with label overlap prefers the overlapping one`() {
        // Edge case: two known entities normalise to the same name
        // (e.g. "Apple" the SaaS vs "Apple" a fictional Person record).
        // When the LLM suggests "Apple" with labels=[Saas], the
        // resolver should pick the existing Saas one — NOT silently
        // merge into the Person row.
        val saas = namedKnown("apple-saas", "Apple", setOf("Saas"))
        val person = namedKnown("apple-person", "Apple", setOf("Person"))
        val resolver = KnownEntityResolver(listOf(person, saas), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Apple", listOf("Saas")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertEquals("apple-saas", resolution.recommended?.id)
    }

    @Test
    fun `multiple name matches with no label overlap returns first match`() {
        // Both known are Apple. LLM suggests "Apple" with labels=[Biller].
        // Neither overlaps, but rather than create a duplicate we take
        // the first name match and let it widen via ExistingEntity.
        // This is the multi-typing case that motivated the rewrite.
        val saas = namedKnown("apple-saas", "Apple", setOf("Saas"))
        val org = namedKnown("apple-org", "Apple", setOf("Organization"))
        val resolver = KnownEntityResolver(listOf(saas, org), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Apple", listOf("Biller")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertTrue(resolution is ExistingEntity, "Expected widening, got $resolution")
        assertEquals("apple-saas", resolution.recommended?.id)
        assertTrue("Biller" in resolution.recommended!!.labels())
    }

    @Test
    fun `no name match delegates to the next resolver`() {
        val known = namedKnown("airbnb-1", "Airbnb", setOf("Saas"))
        val captured = mutableListOf<SuggestedEntity>()
        val capturingDelegate: EntityResolver = object : EntityResolver {
            override fun resolve(
                suggestedEntities: SuggestedEntities,
                schema: DataDictionary,
            ): Resolutions<SuggestedEntityResolution> {
                captured += suggestedEntities.suggestedEntities
                return Resolutions(
                    chunkIds = suggestedEntities.chunkIds,
                    resolutions = suggestedEntities.suggestedEntities.map { VetoedEntity(it) },
                )
            }
        }
        val resolver = KnownEntityResolver(listOf(known), capturingDelegate)

        resolver.resolve(
            SuggestedEntities(listOf(suggestion("Stripe", listOf("Saas")))),
            schema,
        )

        assertEquals(1, captured.size)
        assertEquals("Stripe", captured.single().name)
    }

    @Test
    fun `name match is case insensitive after normalisation`() {
        val known = namedKnown("airbnb-1", "Airbnb", setOf("Saas"))
        val resolver = KnownEntityResolver(listOf(known), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("airbnb", listOf("Saas")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertNotNull(resolution.recommended)
        assertEquals("airbnb-1", resolution.recommended!!.id)
    }

    @Test
    fun `alias match binds to the known entity and short-circuits before the delegate`() {
        // The current user "Thomas" also goes by "Tom". A chunk mention
        // of "Tom" must resolve to the user via the known-entity path —
        // NOT fall through to the delegate, which is where the escalating
        // resolver would heuristic-match (or mint) a separate external
        // Person. Asserting the delegate is never called proves the
        // exact/known match takes precedence over heuristic escalation.
        val user = namedKnown("user-1", "Thomas", setOf("AssistantUser", "Person"))
        val known = KnownEntity.asCurrentUser(user, listOf("Tom"))
        val captured = mutableListOf<SuggestedEntity>()
        val capturingDelegate: EntityResolver = object : EntityResolver {
            override fun resolve(
                suggestedEntities: SuggestedEntities,
                schema: DataDictionary,
            ): Resolutions<SuggestedEntityResolution> {
                captured += suggestedEntities.suggestedEntities
                return Resolutions(
                    chunkIds = suggestedEntities.chunkIds,
                    resolutions = suggestedEntities.suggestedEntities.map { VetoedEntity(it) },
                )
            }
        }
        val resolver = KnownEntityResolver(listOf(known), capturingDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Tom", listOf("Person")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertEquals("user-1", resolution.recommended?.id)
        assertTrue(captured.isEmpty(), "Alias match must resolve without invoking the delegate")
    }

    @Test
    fun `alias mention of the user widens the user's node instead of forking an external Person`() {
        // Root-cause fixture: the user "Thomas" is only an AssistantUser
        // in the graph; a chunk mentions the alt name "Tom" as a Person.
        // The alias match must bind to the user's existing node (gaining
        // the Person label via ExistingEntity), NOT create a distinct
        // external Person keyed on the alias.
        val user = namedKnown("user-1", "Thomas", setOf("AssistantUser"))
        val known = KnownEntity.asCurrentUser(user, listOf("Tom"))
        val resolver = KnownEntityResolver(listOf(known), noOpDelegate)

        val result = resolver.resolve(
            SuggestedEntities(listOf(suggestion("Tom", listOf("Person")))),
            schema,
        )

        val resolution = result.resolutions.single()
        assertTrue(resolution is ExistingEntity, "Expected widening ExistingEntity, got $resolution")
        assertEquals("user-1", resolution.recommended!!.id)
        assertTrue("Person" in resolution.recommended!!.labels(), "User node should gain the Person label")
    }

    @Test
    fun `empty known list short-circuits to delegate`() {
        val captured = mutableListOf<SuggestedEntity>()
        val capturingDelegate: EntityResolver = object : EntityResolver {
            override fun resolve(
                suggestedEntities: SuggestedEntities,
                schema: DataDictionary,
            ): Resolutions<SuggestedEntityResolution> {
                captured += suggestedEntities.suggestedEntities
                return Resolutions(
                    chunkIds = suggestedEntities.chunkIds,
                    resolutions = suggestedEntities.suggestedEntities.map { VetoedEntity(it) },
                )
            }
        }
        val resolver = KnownEntityResolver(emptyList(), capturingDelegate)

        resolver.resolve(
            SuggestedEntities(listOf(suggestion("Airbnb", listOf("Saas")))),
            schema,
        )

        assertEquals(1, captured.size)
    }
}
