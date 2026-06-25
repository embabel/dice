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
package com.embabel.dice.ingestion.support

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.ContentHasher
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.support.Sha256ContentHasher
import com.embabel.dice.ingestion.ArtifactOutcome
import com.embabel.dice.ingestion.IngestedArtifact
import com.embabel.dice.ingestion.IngestionBatch
import com.embabel.dice.ingestion.IngestionHandler
import com.embabel.dice.ingestion.IngestionLedger
import com.embabel.dice.ingestion.IngestionResult
import com.embabel.dice.ingestion.InMemoryIngestionLedger
import com.embabel.dice.pipeline.PropositionPipeline
import org.slf4j.LoggerFactory

/**
 * The one shipped [IngestionHandler]: a normalized text front door that wraps the
 * existing extraction pipeline without modifying it.
 *
 * For each artifact the handler:
 * 1. resolves a deduplication key (the caller-supplied content hash when present,
 *    otherwise a hash computed via the injected [ContentHasher]),
 * 2. atomically claims that key via [IngestionLedger.recordIfAbsent]; when the
 *    key was already present it short-circuits before any extraction, returning
 *    a [ArtifactOutcome.Deduplicated] marker (no extraction call),
 * 3. bridges the artifact text into a [Chunk] and runs the pipeline with the
 *    artifact's source locator on the context, so the pipeline grounds each
 *    returned proposition's provenance in that locator, and
 * 4. leaves the claimed key recorded so identical content is deduplicated next
 *    time; if extraction fails the claim is released so retries are not poisoned.
 *
 * The handler runs extraction only — no revision. Revision and persistence stay
 * downstream caller concerns, so the returned propositions are exactly the
 * pipeline's extraction output (enriched with grounding) and are unsaved.
 *
 * Per-artifact failures are isolated into [ArtifactOutcome.Failed] so one bad
 * artifact never aborts the rest of the batch.
 *
 * A batch is processed SEQUENTIALLY, in submission order. Intra-batch
 * deduplication of identical content relies on this sequential processing: the
 * claim for an earlier artifact is visible to a later identical one. A future
 * handler that processes a batch in parallel must supply its own atomic
 * deduplication strategy rather than depending on processing order.
 */
class TextIngestionHandler @JvmOverloads constructor(
    private val pipeline: PropositionPipeline,
    private val ledger: IngestionLedger = InMemoryIngestionLedger(),
    private val contentHasher: ContentHasher = Sha256ContentHasher,
) : IngestionHandler {

    private val logger = LoggerFactory.getLogger(TextIngestionHandler::class.java)

    override fun ingest(batch: IngestionBatch, context: SourceAnalysisContext): IngestionResult {
        logger.info("Ingesting batch of {} artifact(s)", batch.artifacts.size)
        val outcomes = batch.artifacts.map { artifact ->
            runCatching { ingestOne(artifact, context) }
                .getOrElse { ArtifactOutcome.Failed(artifact.sourceId, it) }
        }
        val result = IngestionResult(outcomes)
        logger.info(
            "Batch complete: {} ingested, {} deduplicated, {} failed",
            outcomes.count { it is ArtifactOutcome.Ingested },
            outcomes.count { it is ArtifactOutcome.Deduplicated },
            outcomes.count { it is ArtifactOutcome.Failed },
        )
        return result
    }

    private fun ingestOne(
        artifact: IngestedArtifact,
        context: SourceAnalysisContext,
    ): ArtifactOutcome {
        val hash = artifact.contentHash ?: contentHasher.hash(artifact.text)
        if (!ledger.recordIfAbsent(hash)) {
            logger.debug("Deduplicated artifact {} (hash {})", artifact.sourceId, hash.take(8))
            return ArtifactOutcome.Deduplicated(artifact.sourceId, hash)
        }
        // The hash is now claimed. Release it if extraction fails so a retry of
        // the same content is not wrongly deduplicated.
        return try {
            val chunk = Chunk.create(text = artifact.text, parentId = artifact.sourceId)
            // Hand the pipeline the artifact's locator so it grounds each proposition in that
            // source; the pipeline owns provenance stamping for every ingestion path.
            val result = pipeline.processChunk(chunk, context.withSourceLocator(artifact.locator))
            val grounded = result.propositions
            logger.debug("Extracted {} proposition(s) from artifact {}", grounded.size, artifact.sourceId)
            ArtifactOutcome.Ingested(artifact.sourceId, grounded)
        } catch (e: Throwable) {
            logger.warn("Extraction failed for artifact {}, releasing claim", artifact.sourceId, e)
            ledger.forget(hash)
            throw e
        }
    }
}
