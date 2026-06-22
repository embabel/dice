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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val testContextId = ContextId("test-context")

class AuthorityResolverTest {

    @Test
    fun `authority tier ordinal ordering ranks primary highest and unknown lowest`() {
        // Higher authority == lower ordinal; UNKNOWN is the highest ordinal (lowest authority).
        assertTrue(AuthorityTier.PRIMARY.ordinal < AuthorityTier.SECONDARY.ordinal)
        assertTrue(AuthorityTier.SECONDARY.ordinal < AuthorityTier.DERIVED.ordinal)
        assertTrue(AuthorityTier.DERIVED.ordinal < AuthorityTier.UNKNOWN.ordinal)
    }

    @Test
    fun `fixed resolver returns its configured tier regardless of grounding`() {
        val resolver = FixedAuthorityResolver(AuthorityTier.SECONDARY)

        val grounded = createProposition(
            "Alice works at Acme",
            provenanceEntries = listOf(
                ProvenanceEntry(locator = ConnectorRef("slack", "msg-1")),
            ),
        )
        val ungrounded = createProposition("Alice works at Acme")

        assertEquals(AuthorityTier.SECONDARY, resolver.resolve(grounded))
        assertEquals(AuthorityTier.SECONDARY, resolver.resolve(ungrounded))
    }

    @Test
    fun `structural resolver maps connector ref grounding to primary`() {
        val resolver = StructuralAuthorityResolver()

        val prop = createProposition(
            "Alice works at Acme",
            provenanceEntries = listOf(
                ProvenanceEntry(locator = ConnectorRef("slack", "msg-1")),
            ),
        )

        assertEquals(AuthorityTier.PRIMARY, resolver.resolve(prop))
    }

    @Test
    fun `structural resolver maps uri grounding to secondary`() {
        val resolver = StructuralAuthorityResolver()

        val prop = createProposition(
            "Alice works at Acme",
            provenanceEntries = listOf(
                ProvenanceEntry(locator = UriLocator("https://example.com/article")),
            ),
        )

        assertEquals(AuthorityTier.SECONDARY, resolver.resolve(prop))
    }

    @Test
    fun `structural resolver returns the strongest tier across mixed grounding locators`() {
        val resolver = StructuralAuthorityResolver()

        // A URI (SECONDARY) and a connector ref (PRIMARY) on the same proposition.
        // The strongest (lowest-ordinal) tier must win.
        val prop = createProposition(
            "Alice works at Acme",
            provenanceEntries = listOf(
                ProvenanceEntry(locator = UriLocator("https://example.com/article")),
                ProvenanceEntry(locator = ConnectorRef("slack", "msg-1")),
            ),
        )

        assertEquals(AuthorityTier.PRIMARY, resolver.resolve(prop))
    }

    @Test
    fun `structural resolver treats an unmapped locator kind as unknown`() {
        // Resolver with an empty map: every grounded locator is unmapped and must
        // fail safe to UNKNOWN rather than throwing or silently picking a tier.
        val resolver = StructuralAuthorityResolver(emptyMap())

        val prop = createProposition(
            "Alice works at Acme",
            provenanceEntries = listOf(
                ProvenanceEntry(locator = ConnectorRef("slack", "msg-1")),
            ),
        )

        assertEquals(AuthorityTier.UNKNOWN, resolver.resolve(prop))
    }

    @Test
    fun `structural resolver maps empty grounding to unknown`() {
        val resolver = StructuralAuthorityResolver()

        val prop = createProposition("Alice works at Acme")

        assertEquals(AuthorityTier.UNKNOWN, resolver.resolve(prop))
    }

    private fun createProposition(
        text: String,
        provenanceEntries: List<ProvenanceEntry> = emptyList(),
    ) = Proposition(
        contextId = testContextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.8,
        provenanceEntries = provenanceEntries,
    )
}
