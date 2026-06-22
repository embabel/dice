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
package com.embabel.dice.provenance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProvenanceMergeTest {

    @Test
    fun `merge deduplicates by entry equality`() {
        val a = ProvenanceEntry(locator = UriLocator("https://example.com/a"), chunkId = "c1")
        val b = ProvenanceEntry(locator = UriLocator("https://example.com/b"), chunkId = "c2")

        val merged = mergeProvenanceEntries(listOf(a), listOf(a, b))

        assertEquals(2, merged.size)
        assertEquals(listOf(a, b), merged)
    }
}
