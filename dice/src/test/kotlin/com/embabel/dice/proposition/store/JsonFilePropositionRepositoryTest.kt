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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.store.ContextReadScope
import com.embabel.dice.store.ReadBudget
import com.embabel.dice.store.ReadCriteria
import com.embabel.dice.store.ScopedReadSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class JsonFilePropositionRepositoryTest {

    private val contextA = ContextId("user-a")
    private val contextB = ContextId("user-b")

    private fun proposition(
        contextId: ContextId,
        text: String,
        provenanceEntries: List<ProvenanceEntry> = emptyList(),
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.9,
            provenanceEntries = provenanceEntries,
        )

    @Test
    fun `scoped reads wait for a JSON save to commit or roll back`(@TempDir tempDir: Path) {
        val repository = JsonFilePropositionRepository(tempDir.resolve("propositions.json"))
        val reads = repository.scopedReads()
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(contextA.value))
        val serializing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val rejected = proposition(contextA, "temporary uncommitted fact").withMetadataValue(
            "failingValue", FailingJsonValue(serializing, release),
        )

        Executors.newFixedThreadPool(2).use { executor ->
            val write = executor.submit<Proposition> { repository.save(rejected) }
            try {
                assertTrue(serializing.await(5, TimeUnit.SECONDS))
                val read = executor.submit<Int> {
                    reading.countDown()
                    reads.count(spec, ReadCriteria.empty(), ReadBudget.ofRows(1))
                }
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                assertThrows<TimeoutException> { read.get(200, TimeUnit.MILLISECONDS) }
                release.countDown()
                assertThrows<ExecutionException> { write.get(5, TimeUnit.SECONDS) }
                assertEquals(0, read.get(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }

    private class FailingJsonValue(
        private val serializing: CountDownLatch,
        private val release: CountDownLatch,
    ) {
        @Suppress("unused") // Jackson reads this property when writing the pending file.
        val value: Int
            get() {
                serializing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("Fail after the store has published its pending state")
            }
    }

    @Test
    fun `propositions saved by one instance are recovered by a fresh instance over the same file`(
        @TempDir tempDir: Path,
    ) {
        val storeFile = tempDir.resolve("propositions.json")

        val first = proposition(contextA, "first statement")
        val second = proposition(contextB, "second statement")

        // Process 1: persist two propositions in different contexts.
        JsonFilePropositionRepository(storeFile).apply {
            save(first)
            save(second)
        }

        // Process restart: a brand-new instance over the same file must recover state.
        val reloaded = JsonFilePropositionRepository(storeFile)

        assertEquals(2, reloaded.count())
        assertEquals(first, reloaded.findById(first.id))

        val forA = reloaded.findByContextIdValue(contextA.value)
        assertEquals(1, forA.size)
        assertEquals(first, forA.single())
    }

    @Test
    fun `constructed without an embedding service findSimilarWithScores degrades to empty`(
        @TempDir tempDir: Path,
    ) {
        val repo = JsonFilePropositionRepository(tempDir.resolve("propositions.json"))
        repo.save(proposition(contextA, "first statement"))

        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "first", topK = 10, similarityThreshold = 0.0),
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `portable source queries retain their meanings across a JSON reload`(
        @TempDir tempDir: Path,
    ) {
        val storeFile = tempDir.resolve("propositions.json")
        val fixture = PortableSourceQueryFixture()
        val repository = JsonFilePropositionRepository(storeFile)
        repository.saveAll(fixture.propositions)

        assertPortableSourceQueries(repository, fixture)
        assertPortableSourceQueries(JsonFilePropositionRepository(storeFile), fixture)
    }

    @Test
    fun `legacy JSON without source revision participates only as revisionless evidence`(
        @TempDir tempDir: Path,
    ) {
        val storeFile = tempDir.resolve("propositions.json")
        val locator = UriLocator("https://example.com/legacy-source")
        val local = proposition(
            contextA,
            "legacy local",
            listOf(ProvenanceEntry(locator = locator, sourceRevision = "legacy-r1")),
        )
        val foreign = proposition(
            contextB,
            "legacy foreign",
            listOf(ProvenanceEntry(locator = locator, sourceRevision = "legacy-r1")),
        )
        JsonFilePropositionRepository(storeFile).saveAll(listOf(local, foreign))

        val currentJson = Files.readString(storeFile)
        val oldJson = currentJson.replace(
            Regex(",\\s*\"sourceRevision\"\\s*:\\s*(?:null|\"(?:\\\\.|[^\"\\\\])*\")"),
            "",
        )
        assertTrue(oldJson != currentJson)
        assertTrue(!oldJson.contains("\"sourceRevision\""))
        Files.writeString(storeFile, oldJson)

        val reloaded = JsonFilePropositionRepository(storeFile)
        val allSourceVersions = reloaded.findBySourceKey(contextA, locator.key())
        val exactRevision = reloaded.findBySourceRevision(
            contextA,
            SourceRevisionRef(locator.key(), "legacy-r1"),
        )
        val revisionless = reloaded.findRevisionlessBySourceLocator(contextA, locator)

        assertEquals(listOf(local.id), allSourceVersions.map { it.id })
        assertEquals(emptyList<Proposition>(), exactRevision)
        assertEquals(listOf(local.id), revisionless.map { it.id })
        assertEquals(false, allSourceVersions.any { it.id == foreign.id })
        assertEquals(false, revisionless.any { it.id == foreign.id })
    }
}
