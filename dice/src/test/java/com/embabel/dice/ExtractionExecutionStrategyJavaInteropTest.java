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

import com.embabel.agent.rag.model.Chunk;
import com.embabel.dice.pipeline.BatchedExtractionStrategy;
import com.embabel.dice.pipeline.ExtractionExecutionStrategy;
import com.embabel.dice.pipeline.SerialExtractionStrategy;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that a Java caller can run an extraction strategy with a plain
 * java.util.function.Function, and that a strategy written in Java gets that method for free.
 */
class ExtractionExecutionStrategyJavaInteropTest {

    private final List<Chunk> chunks = List.of(
            Chunk.create("alpha", "doc-1"),
            Chunk.create("beta", "doc-1"),
            Chunk.create("gamma", "doc-1"));

    @Test
    void serialStrategyReturnsTextsInInputOrder() {
        List<String> texts = SerialExtractionStrategy.INSTANCE.executeAll(chunks, Chunk::getText);

        assertEquals(Arrays.asList("alpha", "beta", "gamma"), texts);
    }

    @Test
    void failedChunkBecomesNullSlotAndOthersKeepTheirValues() {
        Function<Chunk, String> failOnBeta = chunk -> {
            if (chunk.getText().equals("beta")) {
                throw new IllegalStateException("extraction failed");
            }
            return chunk.getText();
        };

        List<String> results = SerialExtractionStrategy.INSTANCE.executeAll(chunks, failOnBeta);

        assertEquals(Arrays.asList("alpha", null, "gamma"), results);
    }

    @Test
    void batchedStrategyKeepsInputOrder() {
        List<Chunk> fiveChunks = List.of(
                Chunk.create("one", "doc-2"),
                Chunk.create("two", "doc-2"),
                Chunk.create("three", "doc-2"),
                Chunk.create("four", "doc-2"),
                Chunk.create("five", "doc-2"));

        try (BatchedExtractionStrategy strategy = new BatchedExtractionStrategy(2)) {
            List<String> texts = strategy.executeAll(fiveChunks, Chunk::getText);

            assertEquals(Arrays.asList("one", "two", "three", "four", "five"), texts);
        }
    }

    @Test
    void javaWrittenStrategyGetsExecuteAllWithoutOverriding() {
        ExtractionExecutionStrategy strategy = new ExtractionExecutionStrategy() {
            @Override
            public <T> List<T> execute(List<? extends Chunk> chunks, Function1<? super Chunk, ? extends T> extract) {
                List<T> results = new ArrayList<>();
                for (Chunk chunk : chunks) {
                    results.add(extract.invoke(chunk));
                }
                return results;
            }
        };

        List<String> texts = strategy.executeAll(chunks, Chunk::getText);

        assertEquals(Arrays.asList("alpha", "beta", "gamma"), texts);
    }

    @Test
    void functionOnWiderInputTypeIsAccepted() {
        Function<Object, String> describe = o -> "seen";

        List<String> results = SerialExtractionStrategy.INSTANCE.executeAll(chunks, describe);

        assertEquals(Arrays.asList("seen", "seen", "seen"), results);
    }
}
