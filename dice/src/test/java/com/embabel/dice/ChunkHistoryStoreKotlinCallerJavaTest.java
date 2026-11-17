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
import com.embabel.dice.incremental.ProcessedChunkRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that existing Kotlin code calling clearByContext(ContextId) reaches a store written in Java.
 *
 * E1: a Java store that overrides only clearByContextValue is cleared by the Kotlin clearByContext
 *     call, so a hash it recorded for that context is no longer reported as processed.
 * E2: a Java store that overrides neither clear method still treats both calls as a no-op.
 */
class ChunkHistoryStoreKotlinCallerJavaTest {

    /** A Java store that keeps processed hashes and clears them only through the String form. */
    static final class HashSetStore implements ChunkHistoryStore {

        private final Set<HashKey> processed = new HashSet<>();

        @Override
        public AnalysisBookmark getLastBookmark(BookmarkKey key) {
            return null;
        }

        @Override
        public boolean isProcessed(HashKey key) {
            return processed.contains(key);
        }

        @Override
        public void recordProcessed(ProcessedChunkRecord record) {
            processed.add(record.getHashKey());
        }

        @Override
        public void clearByContextValue(String contextIdValue) {
            processed.removeIf(key -> key.getContextIdValue().equals(contextIdValue));
        }
    }

    /** A Java store that overrides no clear method at all. */
    static final class NoClearStore implements ChunkHistoryStore {

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
    }

    @Test
    void e1_theKotlinClearReachesAJavaStoreThatOverridesOnlyTheStringForm() {
        HashSetStore store = new HashSetStore();
        store.recordProcessed(new ProcessedChunkRecord(
                BookmarkKey.of("acme", "source-1"), HashKey.of("acme", "h"), 0, 1, Instant.EPOCH));
        store.recordProcessed(new ProcessedChunkRecord(
                BookmarkKey.of("globex", "source-1"), HashKey.of("globex", "h"), 0, 1, Instant.EPOCH));

        ContextIdFixtures.clearByContext(store, "acme");

        assertFalse(store.isProcessed(HashKey.of("acme", "h")));
        assertTrue(store.isProcessed(HashKey.of("globex", "h")));
    }

    @Test
    void e2_aJavaStoreWithNoClearOverrideStillTreatsBothFormsAsANoOp() {
        NoClearStore store = new NoClearStore();

        assertDoesNotThrow(() -> ContextIdFixtures.clearByContext(store, "acme"));
        assertDoesNotThrow(() -> store.clearByContextValue("acme"));
    }
}
