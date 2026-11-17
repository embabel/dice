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
import com.embabel.dice.pipeline.ParallelExtractionStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, from Java, how the concurrent strategies hand an interrupt and a rejected chunk back to
 * the thread that called them.
 *
 * E1: when an extraction is interrupted on a pool thread, its slot is null and the calling thread's
 *     interrupt flag is set once every chunk is done. This holds for both concurrent strategies.
 * E2: the pool thread is not left interrupted, so the next task on it runs normally.
 * E3: when the executor rejects a chunk, the strategy waits for the chunks it already handed over
 *     and then throws the RejectedExecutionException.
 * E4: an executor that is already shut down makes the strategy throw RejectedExecutionException.
 * E5: with an executor that runs refused work on the calling thread, an interrupt in an early
 *     window does not fail the chunks of later windows, and the caller still sees it at the end.
 */
class ExtractionStrategyInterruptJavaTest {

    private final List<Chunk> chunks = List.of(
            Chunk.create("one", "doc-1"),
            Chunk.create("two", "doc-1"));

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        executor.shutdownNow();
    }

    @Test
    void e1_anInterruptOnAPoolThreadReachesTheCaller() {
        for (ExtractionExecutionStrategy strategy : List.of(
                new ParallelExtractionStrategy(executor),
                new BatchedExtractionStrategy(1, executor))) {
            List<String> results = strategy.executeAll(chunks, interruptOn("one"));

            assertTrue(Thread.interrupted(), "the calling thread should see the interrupt");
            assertNull(results.get(0));
            assertArrayEquals(new Object[]{null, "two"}, results.toArray());
        }
    }

    @Test
    void e2_thePoolThreadIsNotLeftInterrupted() throws Exception {
        new ParallelExtractionStrategy(executor).executeAll(chunks, interruptOn("one"));
        Thread.interrupted();

        boolean poolThreadInterrupted = executor.submit(() -> Thread.currentThread().isInterrupted()).get();

        assertFalse(poolThreadInterrupted);
    }

    @Test
    void e3_aRejectedChunkIsThrownAfterTheStartedChunksFinish() {
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        AtomicBoolean firstFinished = new AtomicBoolean(false);
        Function<Chunk, String> slowFirst = chunk -> {
            if (chunk.getText().equals("one")) {
                sleepQuietly(300);
                firstFinished.set(true);
            }
            return chunk.getText();
        };

        assertThrows(RejectedExecutionException.class,
                () -> new ParallelExtractionStrategy(executor).executeAll(chunks, slowFirst));

        assertTrue(firstFinished.get(), "the strategy should wait for the chunk it had started");
    }

    @Test
    void e4_aShutDownExecutorIsReportedAsARejection() {
        executor.shutdown();

        assertThrows(RejectedExecutionException.class,
                () -> new BatchedExtractionStrategy(2, executor).executeAll(chunks, Chunk::getText));
    }

    @Test
    void e5_anInterruptInAnEarlyWindowDoesNotFailLaterWindows() {
        executor = new CallingThreadExecutor();
        Function<Chunk, String> interruptibleWork = chunk -> {
            if (chunk.getText().equals("one")) {
                Thread.currentThread().interrupt();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw sneaky(e);
            }
            return chunk.getText();
        };

        List<String> results = new BatchedExtractionStrategy(1, executor).executeAll(chunks, interruptibleWork);

        assertTrue(Thread.interrupted(), "the calling thread should see the interrupt at the end");
        assertArrayEquals(new Object[]{null, "two"}, results.toArray());
    }

    /** Runs every task on the thread that submits it, as CallerRunsPolicy does once a pool is full. */
    static final class CallingThreadExecutor extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** An extraction that is interrupted on the chunk with [text] and returns the text otherwise. */
    private static Function<Chunk, String> interruptOn(String text) {
        return chunk -> {
            if (chunk.getText().equals(text)) {
                Thread.currentThread().interrupt();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw sneaky(e);
                }
            }
            return chunk.getText();
        };
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
