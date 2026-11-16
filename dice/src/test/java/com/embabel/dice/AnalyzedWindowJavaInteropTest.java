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

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.common.support.Sha256ContentHasher;
import com.embabel.dice.incremental.AbstractIncrementalAnalyzer;
import com.embabel.dice.incremental.AnalyzedWindow;
import com.embabel.dice.incremental.BookmarkKey;
import com.embabel.dice.incremental.ChunkHistoryStore;
import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.incremental.IncrementalSource;
import com.embabel.dice.incremental.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzedWindowJavaInteropTest {

    /** An analyzer whose result is the window text in upper case. */
    private static final class Lines extends AbstractIncrementalAnalyzer<String, String> {
        Lines(ChunkHistoryStore history) {
            super(history, items -> String.join("\n", items), new WindowConfig(10, 0, 2), Sha256ContentHasher.INSTANCE);
        }

        @Override
        protected String processChunk(Chunk chunk, SourceAnalysisContext context) {
            return chunk.getText().toUpperCase();
        }
    }

    /** A source over a fixed list of lines. */
    private record ListSource(List<String> items) implements IncrementalSource<String> {
        @Override public String getId() { return "java-lines"; }
        @Override public int getSize() { return items.size(); }
        @Override public List<String> getItems(int start, int end) { return items.subList(start, end); }
    }

    private final SourceAnalysisContext context = SourceAnalysisContext
            .withContextId("java-window")
            .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
            .withSchema(DataDictionary.fromClasses("java-window"));

    private final BookmarkKey bookmark = BookmarkKey.of("java-window", "java-lines");

    @Test
    void aWindowIsRecordedOnlyWhenItIsMarked() {
        InMemoryChunkHistoryStore history = new InMemoryChunkHistoryStore();
        Lines analyzer = new Lines(history);
        ListSource source = new ListSource(List.of("a", "b"));

        AnalyzedWindow<String> window = analyzer.analyzeWindow(source, context);

        assertNotNull(window);
        assertEquals("A\nB", window.getResult());
        assertFalse(window.isMarkedProcessed());
        assertNull(history.getLastBookmark(bookmark));

        window.markProcessed();

        assertTrue(window.isMarkedProcessed());
        assertEquals(2, history.getLastBookmark(bookmark).getEndIndex());
        assertNull(analyzer.analyzeWindow(source, context));
    }

    @Test
    void aWindowThatIsNotMarkedIsAnalysedAgain() {
        Lines analyzer = new Lines(new InMemoryChunkHistoryStore());
        ListSource source = new ListSource(List.of("a"));

        assertNull(analyzer.analyzeWindow(source, context));
        assertEquals("A", analyzer.analyzeWindowNow(source, context).getResult());
        assertEquals("A", analyzer.analyzeWindowNow(source, context).getResult());
    }
}
