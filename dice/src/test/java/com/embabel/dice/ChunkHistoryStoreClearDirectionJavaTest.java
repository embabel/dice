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

import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.incremental.KotlinOnlyChunkHistoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the Java entry point of a chunk history store reaches a store written in Kotlin.
 *
 * E1: clearByContextValue on a Kotlin store that overrides only clearByContext(ContextId) reaches
 *     that override once, with the same context.
 * R1: a blank context id is refused with IllegalArgumentException before any store is touched.
 */
class ChunkHistoryStoreClearDirectionJavaTest {

    @Test
    void e1_theStringFormReachesAKotlinStoreThatOverridesOnlyTheContextIdForm() {
        KotlinOnlyChunkHistoryStore store = new KotlinOnlyChunkHistoryStore();

        store.clearByContextValue("acme");

        assertEquals(List.of("acme"), store.clearedValues());
    }

    @Test
    void r1_aBlankContextIdIsRefused() {
        KotlinOnlyChunkHistoryStore store = new KotlinOnlyChunkHistoryStore();

        assertThrows(IllegalArgumentException.class, () -> store.clearByContextValue(" "));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryChunkHistoryStore().clearByContextValue(""));
        assertTrue(store.clearedValues().isEmpty());
    }
}
