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

import com.embabel.dice.incremental.AnalysisBookmark;
import com.embabel.dice.incremental.BookmarkKey;
import com.embabel.dice.incremental.ChunkHistoryStore;
import com.embabel.dice.incremental.HashKey;
import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.incremental.ProcessedChunkRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a Java developer can write a chunk history store by overriding the plain
 * String form, and that the built-in in-memory store clears a context through either form.
 */
class ChunkHistoryStoreJavaInteropTest {

    /**
     * A store written in Java. It only records which contexts were cleared.
     */
    static final class RecordingStore implements ChunkHistoryStore {

        final List<String> clearedContextValues = new ArrayList<>();

        @Override
        public AnalysisBookmark getLastBookmark(BookmarkKey key) {
            return null;
        }

        @Override
        public boolean isProcessed(HashKey key) {
            return false;
        }

        @Override
        public void recordProcessed(ProcessedChunkRecord record) {
        }

        @Override
        public void clearByContextValue(String contextIdValue) {
            clearedContextValues.add(contextIdValue);
        }
    }

    @Test
    void javaOverrideReceivesTheContextValueOnceThroughTheStringForm() {
        RecordingStore store = new RecordingStore();

        store.clearByContextValue("acme");

        assertEquals(List.of("acme"), store.clearedContextValues);
    }

    @Test
    void theKotlinFormOnAJavaStoreReachesTheStringOverrideOnce() {
        RecordingStore store = new RecordingStore();

        assertDoesNotThrow(() -> ContextIdFixtures.clearByContext(store, "acme"));

        assertEquals(List.of("acme"), store.clearedContextValues);
    }

    @Test
    void inMemoryStoreClearsOnlyTheNamedContextThroughTheStringForm() {
        InMemoryChunkHistoryStore store = new InMemoryChunkHistoryStore();
        store.recordProcessed(record("acme", "h-acme"));
        store.recordProcessed(record("globex", "h-globex"));

        assertDoesNotThrow(() -> store.clearByContextValue("acme"));

        assertFalse(store.isProcessed(HashKey.of("acme", "h-acme")));
        assertTrue(store.isProcessed(HashKey.of("globex", "h-globex")));
    }

    @Test
    void inMemoryStoreClearsTheSameWayThroughTheKotlinCaller() {
        InMemoryChunkHistoryStore store = new InMemoryChunkHistoryStore();
        store.recordProcessed(record("acme", "h-acme"));
        store.recordProcessed(record("globex", "h-globex"));

        assertDoesNotThrow(() -> ContextIdFixtures.clearByContext(store, "acme"));

        assertFalse(store.isProcessed(HashKey.of("acme", "h-acme")));
        assertTrue(store.isProcessed(HashKey.of("globex", "h-globex")));
    }

    /**
     * A processed chunk for one context, with a fixed source and a plain index range.
     *
     * @param contextIdValue The context the chunk belongs to.
     * @param contentHash The hash of the chunk content.
     * @return The record.
     */
    private static ProcessedChunkRecord record(String contextIdValue, String contentHash) {
        return new ProcessedChunkRecord(
                BookmarkKey.of(contextIdValue, "source-1"),
                HashKey.of(contextIdValue, contentHash),
                0,
                1,
                Instant.EPOCH);
    }
}
