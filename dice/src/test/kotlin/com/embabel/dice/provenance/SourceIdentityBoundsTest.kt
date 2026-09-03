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

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The length ceilings on externally supplied source identity and revision strings.
 *
 * Each limit is checked three ways: a value exactly at the limit is accepted, a value one character
 * over it is refused with a message naming the limit, and the refusal happens while the value object
 * is being built — upstream of any hash and of any call into a store.
 */
class SourceIdentityBoundsTest {

    /**
     * A store that counts every call anything makes into it. Used to prove a rejected write never
     * got as far as the persistence port.
     */
    private class InteractionCountingStore : PropositionStore {

        var interactions = 0
            private set

        override fun save(proposition: Proposition): Proposition {
            interactions++
            return proposition
        }

        override fun findById(id: String): Proposition? {
            interactions++
            return null
        }

        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> {
            interactions++
            return emptyList()
        }

        override fun findByStatus(status: PropositionStatus): List<Proposition> {
            interactions++
            return emptyList()
        }

        override fun findByGrounding(chunkId: String): List<Proposition> {
            interactions++
            return emptyList()
        }

        override fun findByMinLevel(minLevel: Int): List<Proposition> {
            interactions++
            return emptyList()
        }

        override fun findAll(): List<Proposition> {
            interactions++
            return emptyList()
        }

        override fun delete(id: String): Boolean {
            interactions++
            return false
        }

        override fun count(): Int {
            interactions++
            return 0
        }
    }

    /** A `uri:` locator whose canonical key comes out at exactly [keyLength] characters. */
    private fun locatorWithKeyLength(keyLength: Int): UriLocator {
        val prefix = "https://example.com/"
        val padding = keyLength - "uri:".length - prefix.length
        return UriLocator(prefix + "a".repeat(padding))
    }

    private fun propositionCiting(entry: ProvenanceEntry): Proposition =
        Proposition(
            contextId = ContextId("bounds"),
            text = "a bounded fact",
            mentions = emptyList(),
            confidence = 0.9,
            provenanceEntries = listOf(entry),
        )

    @Test
    fun `a source key at the limit is accepted`() {
        val locator = locatorWithKeyLength(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH)
        assertEquals(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH, locator.key().length)

        val entry = ProvenanceEntry(locator = locator)
        assertEquals(locator.key(), entry.locator.key())
        assertEquals(locator.key(), SourceRevisionRef(locator.key(), "r1").sourceKey)
    }

    @Test
    fun `a source key one over the limit is refused before the store is touched`() {
        val store = InteractionCountingStore()
        val locator = locatorWithKeyLength(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH + 1)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            store.save(propositionCiting(ProvenanceEntry(locator = locator)))
        }

        assertTrue(
            failure.message!!.contains("MAX_SOURCE_KEY_LENGTH") &&
                failure.message!!.contains("${SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH}"),
            "the refusal must name the limit it enforced: ${failure.message}",
        )
        assertEquals(0, store.interactions, "an over-long source key must never reach the store")
    }

    @Test
    fun `a source revision at the limit is accepted`() {
        val revision = "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH)
        val locator = UriLocator("https://example.com/at-limit-revision")

        assertEquals(revision, ProvenanceEntry(locator = locator, sourceRevision = revision).sourceRevision)
        assertEquals(revision, SourceRevisionRef(locator.key(), revision).sourceRevision)
    }

    @Test
    fun `a source revision one over the limit is refused before the store is touched`() {
        val store = InteractionCountingStore()
        val locator = UriLocator("https://example.com/over-limit-revision")
        val revision = "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH + 1)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            store.save(propositionCiting(ProvenanceEntry(locator = locator, sourceRevision = revision)))
        }

        assertTrue(
            failure.message!!.contains("MAX_SOURCE_REVISION_LENGTH") &&
                failure.message!!.contains("${SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH}"),
            "the refusal must name the limit it enforced: ${failure.message}",
        )
        assertEquals(0, store.interactions, "an over-long revision must never reach the store")
    }

    /**
     * The query-side value object is bounded too. A caller that hands a runaway key or revision to
     * an exact-revision query is refused at the same ceiling the write path uses.
     */
    @Test
    fun `SourceRevisionRef refuses an over-long key or revision`() {
        val overLongKey = "uri:" + "a".repeat(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH)
        val keyFailure = assertThrows(IllegalArgumentException::class.java) {
            SourceRevisionRef(overLongKey, "r1")
        }
        assertTrue(
            keyFailure.message!!.contains("MAX_SOURCE_KEY_LENGTH"),
            "the refusal must name the limit it enforced: ${keyFailure.message}",
        )

        val revisionFailure = assertThrows(IllegalArgumentException::class.java) {
            SourceRevisionRef("uri:https://example.com/ok", "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH + 1))
        }
        assertTrue(
            revisionFailure.message!!.contains("MAX_SOURCE_REVISION_LENGTH"),
            "the refusal must name the limit it enforced: ${revisionFailure.message}",
        )
    }

    /**
     * The bound sits ahead of the evidence-key encoding, so nothing over the limit can ever be
     * folded into a stored provenance ref.
     */
    @Test
    fun `an over-long value cannot reach the evidence key encoding`() {
        val locator = locatorWithKeyLength(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH)
        val entry = ProvenanceEntry(
            locator = locator,
            sourceRevision = "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH),
        )
        val encoded = ProvenanceEvidenceKey.encode(entry)

        assertTrue(ProvenanceEvidenceKey.matches(entry, encoded))
        assertThrows(IllegalArgumentException::class.java) {
            ProvenanceEvidenceKey.encode(
                ProvenanceEntry(
                    locator = locatorWithKeyLength(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH + 1),
                ),
            )
        }
    }
}
