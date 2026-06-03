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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceLocatorTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `subtypes reject blank required fields`() {
        assertThrows(IllegalArgumentException::class.java) { UriLocator("") }
        assertThrows(IllegalArgumentException::class.java) { FileLocator(" ") }
        assertThrows(IllegalArgumentException::class.java) { ContentAddressedLocator("") }
        assertThrows(IllegalArgumentException::class.java) { ConnectorRef("", "x") }
        assertThrows(IllegalArgumentException::class.java) { ConnectorRef("slack", " ") }
    }

    @Test
    fun `key is kind-prefixed and stable`() {
        assertEquals("uri:https://example.com/doc", UriLocator("https://example.com/doc").key())
        assertEquals("file:/tmp/notes.txt", FileLocator("/tmp/notes.txt").key())
        assertEquals("content:abc123", ContentAddressedLocator("abc123").key())
        assertEquals("connector:slack:C123", ConnectorRef("slack", "C123").key())
    }

    @Test
    fun `display does not participate in identity`() {
        val a = UriLocator("https://example.com/doc", display = "Doc A")
        val b = UriLocator("https://example.com/doc", display = "Doc B")
        assertEquals(a.key(), b.key())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // Different identity is still distinct.
        assertNotEquals(a, UriLocator("https://example.com/other"))

        // Same payload, different kind → distinct, because key() is kind-prefixed.
        assertNotEquals(FileLocator("x"), ContentAddressedLocator("x"))
    }

    @Test
    fun `ProvenanceEntry rejects negative and inverted offsets`() {
        val loc = UriLocator("https://example.com/doc")
        assertThrows(IllegalArgumentException::class.java) { ProvenanceEntry(loc, startOffset = -1) }
        assertThrows(IllegalArgumentException::class.java) { ProvenanceEntry(loc, endOffset = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            ProvenanceEntry(loc, startOffset = 10, endOffset = 5)
        }
    }

    @Test
    fun `SourceLocator round-trips through Jackson by kind discriminator`() {
        val locators: List<SourceLocator> = listOf(
            UriLocator("https://example.com/doc", display = "Doc"),
            FileLocator("/tmp/notes.txt"),
            ContentAddressedLocator("deadbeef"),
            ConnectorRef("slack", "C123"),
        )
        for (original in locators) {
            val json = mapper.writeValueAsString(original)
            assertTrue(json.contains("\"kind\""), "expected a kind discriminator in $json")
            val back = mapper.readValue<SourceLocator>(json)
            assertEquals(original, back)
            // equals() ignores display, so assert it explicitly — it is data that must survive.
            assertEquals(original.display, back.display, "display must survive the JSON round-trip")
        }
    }

    @Test
    fun `ProvenanceEntry round-trips through Jackson with polymorphic locator`() {
        val entry = ProvenanceEntry(
            locator = ConnectorRef("notion", "page-1", display = "Spec"),
            chunkId = "c1",
            startOffset = 5,
            endOffset = 25,
            contentHash = "deadbeef",
        )
        val back = mapper.readValue<ProvenanceEntry>(mapper.writeValueAsString(entry))
        assertEquals(entry, back)
    }
}
