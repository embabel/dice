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

import com.embabel.dice.common.support.DefaultCanonicalNameSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Property-style tests for [com.embabel.dice.common.support.DefaultCanonicalNameSelector]. The exact
 * score values are an implementation detail — the tests only assert
 * the *ordering* the scoring induces, which is the contract callers
 * depend on.
 */
class CanonicalNameSelectorTest {

    private val selector = DefaultCanonicalNameSelector

    @Test
    fun `full name beats partial`() {
        // "Hunter Hordern" is the full name; "Hunter" is a partial
        // first-name-only sighting. Multi-word should win.
        assertEquals("Hunter Hordern", selector.select("Hunter", "Hunter Hordern"))
    }

    @Test
    fun `mixed case beats lowercase even at equal word count`() {
        // "sushila" (email local-part) loses to "Sushila" (title-cased).
        assertEquals("Sushila", selector.select("sushila", "Sushila"))
    }

    @Test
    fun `title-cased beats lowercased multi-word`() {
        assertEquals("Bob Smith", selector.select("bob smith", "Bob Smith"))
    }

    @Test
    fun `all-caps is penalized`() {
        // YELLING is rarely the canonical form even when it has
        // structure. Title-case should win.
        assertEquals("Bob Smith", selector.select("BOB SMITH", "Bob Smith"))
    }

    @Test
    fun `bare email loses to any non-email candidate`() {
        assertEquals(
            "Hunter Hordern",
            selector.select("hunter.hordern@ubs.com", "Hunter Hordern"),
        )
        // Even loses to a lowercase single-word fallback.
        assertEquals(
            "hunter",
            selector.select("hunter.hordern@ubs.com", "hunter"),
        )
    }

    @Test
    fun `null and blank candidates are dropped`() {
        assertEquals(
            "Hunter",
            selector.select(null, "", "  ", "Hunter"),
        )
    }

    @Test
    fun `empty inputs return null`() {
        assertNull(selector.select(emptyList()))
        assertNull(selector.select(null, null, ""))
    }

    @Test
    fun `titles are stripped before scoring`() {
        // "Dr. Hunter Hordern" → "Hunter Hordern" after normalization,
        // which is then identical to the plain candidate. Either one
        // wins as canonical.
        val result = selector.select("Dr. Hunter Hordern", "Hunter Hordern")
        assertEquals("Hunter Hordern", result)
    }

    @Test
    fun `digit-bearing handles lose to clean alternatives`() {
        // A handle like "user42" should lose to a clean name when both
        // are present.
        assertEquals("Sarah Chen", selector.select("sarahc42", "Sarah Chen"))
    }

    @Test
    fun `stable when only one candidate`() {
        assertEquals("Alice", selector.select("Alice"))
        assertEquals("alice", selector.select("alice"))
    }

    @Test
    fun `deduplication is normalization-aware`() {
        // "Dr. Hunter Hordern" and "Hunter Hordern" both normalize to
        // "Hunter Hordern" — should be treated as one candidate, not
        // counted twice (which would be a no-op anyway but matters
        // for stable ordering when ties involve dedup).
        val candidates = listOf("Dr. Hunter Hordern", "Hunter Hordern", "Hunter Hordern Jr.")
        val result = selector.select(candidates)
        assertEquals("Hunter Hordern", result)
    }
}
