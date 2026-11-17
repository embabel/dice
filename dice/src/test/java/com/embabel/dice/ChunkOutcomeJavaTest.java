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
import com.embabel.dice.pipeline.ChunkOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the ChunkOutcome factories and getters work from Java. A success keeps the value
 * it was given, a failure keeps the exception, and each one keeps the chunk it came from.
 *
 * Contract:
 * E1: success(chunk, value) is a success with that chunk and value, and no exception.
 * E2: failure(chunk, exception) is a failure with that chunk and exception, and no value.
 * E3: success(chunk, null) is still a success, with a null value.
 * I1: the chunk handed to a factory is the same object the outcome returns.
 */
class ChunkOutcomeJavaTest {

    private final Chunk chunk = Chunk.create("alpha", "doc-1");

    // E1: a success reports success, carries the value, and has no exception.
    @Test
    void successCarriesChunkAndValue() {
        ChunkOutcome<String> outcome = ChunkOutcome.success(chunk, "extracted");

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.isFailure());
        assertSame(chunk, outcome.getChunk());
        assertEquals("extracted", outcome.getValue());
        assertNull(outcome.getException());
    }

    // E2: a failure reports failure, carries the exception, and has no value.
    @Test
    void failureCarriesChunkAndException() {
        IllegalStateException boom = new IllegalStateException("boom");

        ChunkOutcome<String> outcome = ChunkOutcome.failure(chunk, boom);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.isFailure());
        assertSame(chunk, outcome.getChunk());
        assertNull(outcome.getValue());
        assertSame(boom, outcome.getException());
    }

    // E3: a null value is still a success, because the extraction ran without throwing.
    @Test
    void successWithNullValueIsStillSuccess() {
        ChunkOutcome<String> outcome = ChunkOutcome.success(chunk, null);

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.isFailure());
        assertNull(outcome.getValue());
        assertNull(outcome.getException());
        assertSame(chunk, outcome.getChunk());
    }
}
