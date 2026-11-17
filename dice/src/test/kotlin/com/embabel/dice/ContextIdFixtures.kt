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
package com.embabel.dice

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.PropositionContradicted
import com.embabel.dice.evidence.Citation
import com.embabel.dice.common.UngroundedMentionsFound
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.pipeline.UngroundedMentionHandling
import com.embabel.dice.projection.memory.DreamLoopReport
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.spi.CollapseUndoCommand
import com.embabel.dice.spi.CollapseUndoContextMismatchException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Builds events whose constructors take a [ContextId]. Java cannot call those constructors, so the
 * Java interop tests get their events from here.
 */
object ContextIdFixtures {

    @JvmStatic
    fun ungroundedMentionsFound(contextIdValue: String): UngroundedMentionsFound =
        UngroundedMentionsFound(ContextId(contextIdValue), "chunk-1", emptyList(), UngroundedMentionHandling.KEEP)

    @JvmStatic
    fun propositionContradicted(contextIdValue: String): PropositionContradicted {
        val contextId = ContextId(contextIdValue)
        return PropositionContradicted(
            contextId,
            proposition(contextId, "Alice lives in Paris"),
            proposition(contextId, "Alice lives in Rome"),
        )
    }

    @JvmStatic
    fun bookmarkKey(contextIdValue: String): BookmarkKey = BookmarkKey(ContextId(contextIdValue), "source-1")

    @JvmStatic
    fun hashKey(contextIdValue: String): HashKey = HashKey(ContextId(contextIdValue), "abc123")

    @JvmStatic
    fun processedChunkRecord(contextIdValue: String): ProcessedChunkRecord =
        ProcessedChunkRecord(bookmarkKey(contextIdValue), hashKey(contextIdValue), 0, 1, Instant.EPOCH)

    @JvmStatic
    fun dreamLoopReport(contextIdValue: String): DreamLoopReport = DreamLoopReport(
        contextId = ContextId(contextIdValue),
        cycleStarted = Instant.EPOCH,
        cycleCompleted = Instant.EPOCH,
        passResults = emptyList(),
        totalExamined = 0,
        totalTransitioned = 0,
        totalNewPropositions = 0,
        triggered = true,
    )

    @JvmStatic
    fun collectorRunContext(contextIdValue: String): CollectorRunContext =
        CollectorRunContext("run-1", ContextId(contextIdValue))

    @JvmStatic
    fun collapseUndoCommand(contextIdValue: String): CollapseUndoCommand =
        CollapseUndoCommand(ContextId(contextIdValue), "survivor-1", "retired-1")

    /**
     * Clears one context's history through the [ContextId] form, which Java cannot call directly.
     *
     * @param store The store to clear.
     * @param contextIdValue The context to clear, as a plain string.
     */
    @JvmStatic
    fun clearByContext(store: ChunkHistoryStore, contextIdValue: String) =
        store.clearByContext(ContextId(contextIdValue))

    @JvmStatic
    fun collapseUndoContextMismatch(commanded: String, actual: String): CollapseUndoContextMismatchException =
        CollapseUndoContextMismatchException(ContextId(commanded), "proposition-1", ContextId(actual))

    /**
     * One ready-made value for every public type that has a `ContextId` getter, keyed by its class.
     * The JSON guard test serializes each one and checks that the id comes out as a plain string
     * under the property's own name. When you add a type with a `ContextId` property, add a sample
     * here too, or the guard fails.
     *
     * Every sample uses the context `acme`, except the mismatch exception, which needs two
     * different contexts and uses `commanded` and `actual`. The run context gets a stopped clock
     * because Jackson will not write the system clock.
     *
     * @return The samples, keyed by their exact class.
     */
    @JvmStatic
    fun jsonSamples(): Map<Class<*>, Any> {
        val acme = ContextId("acme")
        val samples = listOf(
            ungroundedMentionsFound("acme"),
            propositionContradicted("acme"),
            bookmarkKey("acme"),
            hashKey("acme"),
            processedChunkRecord("acme"),
            dreamLoopReport("acme"),
            CollectorRunContext.of("run-1", "acme", clock = EpochClock),
            collapseUndoCommand("acme"),
            collapseUndoContextMismatch("commanded", "actual"),
            proposition(acme, "Alice lives in Paris"),
            Proposition.draft("acme", "Alice lives in Paris", 0.9),
            PropositionQuery.againstContext("acme"),
            ExtractionRunKey.of("acme", "run-1"),
            ExtractionRun.of(
                "acme",
                ExtractionRunLineage.root(ExtractionRunRef("run-1")),
                ExtractionRunStatus.RUNNING,
                Instant.EPOCH,
            ),
            Citation.of("proposition-1", acme),
        )
        return samples.associateBy { it.javaClass }
    }

    /**
     * A clock stopped at the epoch, for the run context sample. Jackson refuses to serialize the
     * JDK's own clocks, but it writes this one as a bean with just its zone.
     */
    private object EpochClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.EPOCH
    }

    /**
     * A minimal proposition in one context.
     *
     * @param contextId The context it belongs to.
     * @param text Its statement.
     * @return The proposition.
     */
    private fun proposition(contextId: ContextId, text: String): Proposition =
        Proposition(contextId = contextId, text = text, mentions = emptyList(), confidence = 0.9)
}
