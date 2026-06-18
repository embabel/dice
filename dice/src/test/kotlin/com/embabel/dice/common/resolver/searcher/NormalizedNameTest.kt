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
package com.embabel.dice.common.resolver.searcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [NormalizedNameCandidateSearcher.normalizeName] — the
 * canonical-name function the resolver uses to compare suggested
 * mentions against existing rows. Each rule kills a real-world
 * duplicate-Person bug we hit in production: same human, different
 * display variants across systems (work-vs-personal email,
 * directory-export reversed order, signature with title, signature
 * with middle initial).
 */
class NormalizedNameTest {

    private fun normalize(s: String): String =
        NormalizedNameCandidateSearcher.normalizeName(s)

    // --- titles / suffixes (pre-existing behaviour) -----------------

    @Test
    fun `strips Dr title`() {
        assertEquals("Watson", normalize("Dr. Watson"))
        assertEquals("Watson", normalize("Dr Watson"))
    }

    @Test
    fun `strips Mr Mrs Ms Prof titles`() {
        assertEquals("Smith", normalize("Mr. Smith"))
        assertEquals("Smith", normalize("Mrs Smith"))
        assertEquals("Smith", normalize("Ms. Smith"))
        assertEquals("Einstein", normalize("Prof. Einstein"))
    }

    @Test
    fun `strips Jr Sr suffixes`() {
        assertEquals("John Smith", normalize("John Smith Jr."))
        assertEquals("John Smith", normalize("John Smith Sr"))
    }

    @Test
    fun `strips Roman numeral suffixes`() {
        assertEquals("Henry", normalize("Henry II"))
        assertEquals("Henry", normalize("Henry III"))
        assertEquals("Henry", normalize("Henry IV"))
    }

    // --- middle-initial stripping (new) ------------------------------

    @Test
    fun `strips middle initial without dot`() {
        // Real case from the email corpus: Lynda's two addresses
        // surface as "Lynda Coker" (gmail) and "Lynda M Coker"
        // (embabel). Same human. Should normalise the same.
        assertEquals("Lynda Coker", normalize("Lynda M Coker"))
        assertEquals(normalize("Lynda Coker"), normalize("Lynda M Coker"))
    }

    @Test
    fun `strips middle initial with dot`() {
        assertEquals("Lynda Coker", normalize("Lynda M. Coker"))
        assertEquals(normalize("Lynda Coker"), normalize("Lynda M. Coker"))
    }

    @Test
    fun `does not strip single-letter token at start or end`() {
        // Defensive: don't accidentally chew up names like
        // "A. Lincoln" where the initial is meaningful (we'd lose all
        // signal). Same with trailing single letters.
        assertEquals("A. Lincoln", normalize("A. Lincoln"))
        assertEquals("Cat T", normalize("Cat T"))
    }

    @Test
    fun `does not strip middle word when it is not a single letter`() {
        // "John Wesley Powell" — Wesley is a name, not an initial.
        assertEquals("John Wesley Powell", normalize("John Wesley Powell"))
    }

    // --- reversed order ---------------------------------------------

    @Test
    fun `reverses Lastname, Firstname`() {
        // Directory exports often present "Coker, Lynda" — the
        // comma is the marker. Flip to natural order.
        assertEquals("Lynda Coker", normalize("Coker, Lynda"))
    }

    @Test
    fun `reverses and strips middle initial together`() {
        assertEquals("Lynda Coker", normalize("Coker, Lynda M"))
        assertEquals("Lynda Coker", normalize("Coker, Lynda M."))
    }

    @Test
    fun `reverses with title`() {
        // Title goes first AFTER reversal. The reversal happens
        // before title-stripping, so this should still work.
        assertEquals("Lynda Coker", normalize("Coker, Dr. Lynda"))
    }

    @Test
    fun `does not reverse when there is no comma`() {
        assertEquals("Jasper Blues", normalize("Jasper Blues"))
    }

    // --- whitespace + idempotency -----------------------------------

    @Test
    fun `collapses multiple spaces`() {
        assertEquals("John Smith", normalize("John   Smith"))
    }

    @Test
    fun `normalisation is idempotent`() {
        val once = normalize("Coker, Dr. Lynda M.")
        val twice = normalize(once)
        assertEquals(once, twice, "normalising an already-normalised name should not change it")
    }

    // --- the duplicate-Person scenarios we shipped ------------------

    @Test
    fun `Lynda variants all collapse to one canonical form`() {
        val variants = listOf(
            "Lynda Coker",
            "Lynda M Coker",
            "Lynda M. Coker",
            "Coker, Lynda",
            "Coker, Lynda M.",
        )
        val canonical = variants.map(::normalize).distinct()
        assertEquals(
            listOf("Lynda Coker"),
            canonical,
            "every display variant must canonicalise to one string — was $canonical",
        )
    }
}
