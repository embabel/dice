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
package com.embabel.dice;

import com.embabel.dice.common.DiceEventListener;
import com.embabel.dice.common.PropositionContradicted;
import com.embabel.dice.common.UngroundedMentionsFound;
import com.embabel.dice.incremental.BookmarkKey;
import com.embabel.dice.incremental.HashKey;
import com.embabel.dice.projection.memory.DreamLoopReport;
import com.embabel.dice.projection.memory.collector.CollectorRunContext;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.spi.CollapseUndoCommand;
import com.embabel.dice.spi.CollapseUndoContextMismatchException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that Java listeners can read the context id of the events that carry one.
 */
class ContextIdAccessorsJavaInteropTest {

    @Test
    void listenerReadsTheContextOfAnUngroundedMentionsEvent() {
        List<String> seen = new ArrayList<>();
        DiceEventListener listener = event -> {
            if (event instanceof UngroundedMentionsFound found) {
                seen.add(found.getContextIdValue() + "/" + found.getChunkId());
            }
        };

        listener.onEvent(ContextIdFixtures.ungroundedMentionsFound("acme"));

        assertEquals(List.of("acme/chunk-1"), seen);
    }

    @Test
    void listenerReadsTheContextOfAContradiction() {
        List<String> seen = new ArrayList<>();
        DiceEventListener listener = event -> {
            if (event instanceof PropositionContradicted contradicted) {
                seen.add(contradicted.getContextIdValue());
            }
        };

        listener.onEvent(ContextIdFixtures.propositionContradicted("acme"));

        assertEquals(List.of("acme"), seen);
    }

    @Test
    void chunkHistoryKeysAndRecordsExposeTheirContext() {
        assertEquals("acme", ContextIdFixtures.bookmarkKey("acme").getContextIdValue());
        assertEquals("acme", ContextIdFixtures.hashKey("acme").getContextIdValue());
        assertEquals("acme", ContextIdFixtures.processedChunkRecord("acme").getContextIdValue());
    }

    @Test
    void dreamLoopReportExposesItsContext() {
        DreamLoopReport report = ContextIdFixtures.dreamLoopReport("acme");

        assertEquals("acme", report.getContextIdValue());
    }

    @Test
    void collectorRunContextExposesItsContext() {
        assertEquals("acme", ContextIdFixtures.collectorRunContext("acme").getContextIdValue());
    }

    @Test
    void collapseUndoCommandExposesItsContext() {
        assertEquals("acme", ContextIdFixtures.collapseUndoCommand("acme").getContextIdValue());
    }

    @Test
    void contextMismatchNamesBothContexts() {
        CollapseUndoContextMismatchException mismatch = assertThrows(
                CollapseUndoContextMismatchException.class,
                () -> { throw ContextIdFixtures.collapseUndoContextMismatch("acme", "globex"); });

        assertEquals("acme", mismatch.getCommandedContextIdValue());
        assertEquals("globex", mismatch.getActualContextIdValue());
    }

    @Test
    void chunkHistoryKeysAreBuiltFromJava() {
        BookmarkKey bookmark = BookmarkKey.of("acme", "conversation-1");
        HashKey hash = HashKey.of("acme", "abc123");

        assertEquals("acme", bookmark.getContextIdValue());
        assertEquals("conversation-1", bookmark.getSourceId());
        assertEquals("acme", hash.getContextIdValue());
        assertEquals("abc123", hash.getContentHash());
        assertEquals(ContextIdFixtures.bookmarkKey("acme").getContextIdValue(), bookmark.getContextIdValue());
    }

    @Test
    void collectorRunContextIsBuiltFromJava() {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        CollectorRunContext run = CollectorRunContext.of("run-1", "acme");
        CollectorRunContext dryRun = CollectorRunContext.of("run-2", "acme", true, clock);

        assertEquals("acme", run.getContextIdValue());
        assertEquals("run-1", run.getRunId());
        assertFalse(run.getDryRun());
        assertTrue(dryRun.getDryRun());
        assertSame(clock, dryRun.getClock());
    }

    @Test
    void collapseUndoCommandIsBuiltFromJava() {
        CollapseUndoCommand command = CollapseUndoCommand.of("acme", "survivor-1", "retired-1");

        assertEquals("acme", command.getContextIdValue());
        assertEquals("survivor-1", command.getSurvivorId());
        assertEquals("retired-1", command.getRetiredId());
        assertEquals(ContextIdFixtures.collapseUndoCommand("acme"), command);
    }

    @Test
    void collapseUndoCommandRejectsABlankId() {
        assertThrows(IllegalArgumentException.class, () -> CollapseUndoCommand.of("acme", " ", "retired-1"));
    }

    @Test
    void queryIsScopedToAContextByName() {
        PropositionQuery query = PropositionQuery.againstContext("acme");

        assertEquals("acme", query.getContextIdValue());
        assertEquals("other", query.withContextIdValue("other").getContextIdValue());
    }
}
