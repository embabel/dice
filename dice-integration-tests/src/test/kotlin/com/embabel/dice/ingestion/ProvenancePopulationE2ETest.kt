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
package com.embabel.dice.ingestion

import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.eval.CanonicalFlowFixtures
import com.embabel.dice.eval.FixedPropositionExtractor
import com.embabel.dice.eval.FixedVectorEmbeddingService
import com.embabel.dice.ingestion.support.TextIngestionHandler
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.web.rest.PropositionDto
import com.embabel.agent.rag.model.Chunk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end proof that extracted propositions are populated with provenance and that it survives
 * the whole path consumers care about: artifact → [TextIngestionHandler] →
 * [PropositionPipeline] → proposition store → read-back → REST DTO. No LLM, embedding model,
 * network, or container — a [FixedPropositionExtractor] stands in for extraction.
 *
 * This closes the gap behind embabel/dice#32: before this, propositions left the pipeline with
 * empty `provenanceEntries`, so nothing downstream could trace a fact back to its source by URI,
 * file, content hash, or chunk.
 */
class ProvenancePopulationE2ETest {

    private val context: SourceAnalysisContext = CanonicalFlowFixtures.context

    private fun newStore() = InMemoryPropositionRepository(embeddingService = FixedVectorEmbeddingService())

    private fun newHandler() = TextIngestionHandler(PropositionPipeline.withExtractor(FixedPropositionExtractor()))

    private fun artifact(sourceId: String, locator: UriLocator, text: String) =
        IngestedArtifact.withSourceId(sourceId).withLocator(locator).withText(text)

    @Test
    fun `ingested propositions are grounded in the artifact source locator`() {
        val locator = UriLocator("https://example.com/doc-alpha")
        val outcome = newHandler()
            .ingest(artifact("doc-alpha", locator, "Alice works with Bob."), context)
            .outcomes.single()

        assertTrue(outcome is ArtifactOutcome.Ingested)
        val propositions = (outcome as ArtifactOutcome.Ingested).propositions
        assertTrue(propositions.isNotEmpty(), "extraction yields propositions")
        propositions.forEach { p ->
            val entry = p.provenanceEntries.single()
            assertEquals(locator, entry.locator, "grounded in the artifact's source")
            assertNotNull(entry.chunkId, "carries the chunk it came from")
            assertNotNull(entry.contentHash, "carries a content hash")
        }
    }

    @Test
    fun `each artifact in a batch grounds its propositions in its own source`() {
        val locA = UriLocator("https://example.com/doc-a")
        val locB = UriLocator("https://example.com/doc-b")
        val batch = IngestionBatch.of(
            artifact("doc-a", locA, "Alice works with Bob."),
            artifact("doc-b", locB, "Bob works with Carol."),
        )

        val byId = newHandler().ingest(batch, context).outcomes
            .filterIsInstance<ArtifactOutcome.Ingested>()
            .associateBy { it.sourceId }

        assertEquals(setOf("doc-a", "doc-b"), byId.keys)
        assertTrue(
            byId.getValue("doc-a").propositions.all { p -> p.provenanceEntries.all { it.locator == locA } },
            "doc-a's propositions are grounded only in doc-a",
        )
        assertTrue(
            byId.getValue("doc-b").propositions.all { p -> p.provenanceEntries.all { it.locator == locB } },
            "doc-b's propositions are grounded only in doc-b",
        )
    }

    @Test
    fun `provenance survives persistence and read-back from the store`() {
        val locator = UriLocator("https://example.com/doc-beta")
        val store = newStore()

        val ingested = newHandler()
            .ingest(artifact("doc-beta", locator, "Bob works with Carol."), context)
            .propositions
        store.saveAll(ingested)

        val readBack = store.query(PropositionQuery.forContextId(context.contextId))
        assertTrue(readBack.isNotEmpty(), "propositions are persisted")
        assertTrue(
            readBack.all { it.provenanceEntries.any { e -> e.locator == locator } },
            "every persisted proposition keeps its source locator after read-back",
        )
    }

    @Test
    fun `the REST DTO surfaces provenance`() {
        val locator = UriLocator("https://example.com/doc-gamma")
        val proposition = newHandler()
            .ingest(artifact("doc-gamma", locator, "Carol works with Dana."), context)
            .propositions.first()

        val dto = PropositionDto.from(proposition)

        val entry = dto.provenance.single()
        assertEquals(locator.key(), entry.locator, "DTO exposes the locator key")
        assertNotNull(entry.contentHash)
    }

    @Test
    fun `a chunk with no source locator falls back to content-addressed provenance`() {
        val pipeline = PropositionPipeline.withExtractor(FixedPropositionExtractor())
        val chunk = Chunk.create(text = "Alice works with Bob.", parentId = "no-locator-source")

        val result = pipeline.processChunk(chunk, context) as ChunkPropositionResult.Success

        result.propositions.forEach { p ->
            val entry = p.provenanceEntries.single()
            assertTrue(entry.locator is ContentAddressedLocator, "no locator -> content-addressed fallback")
            assertEquals((entry.locator as ContentAddressedLocator).contentHash, entry.contentHash)
            assertEquals(chunk.id, entry.chunkId)
        }
    }
}
