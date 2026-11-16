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
package com.embabel.dice.evidence;

import com.embabel.dice.spi.ConflictType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows what a Java caller gets for a null list element, and how claims are told apart by id and context.
 */
class EvidenceArgumentJavaInteropTest {

    private final EvidenceItem item =
            EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9);

    @Test
    void nullChunkIdIsRejectedWithListNameAndIndex() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Citation.of("p-17", "support-team").withChunkIds(Arrays.asList("chunk-1", null)));

        assertTrue(thrown.getMessage().contains("chunkIds[1]"), thrown.getMessage());
    }

    @Test
    void nullRecordedItemIsRejectedWithListNameAndIndex() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Evidence.recorded(Arrays.asList(item, null)));

        assertTrue(thrown.getMessage().contains("items[1]"), thrown.getMessage());
    }

    @Test
    void nullRetrievalIsRejectedWithListNameAndIndex() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> item.withRetrievals(Arrays.asList(null, Retrieval.of(RetrieverId.VECTOR))));

        assertTrue(thrown.getMessage().contains("retrievals[0]"), thrown.getMessage());
    }

    @Test
    void sameClaimMeansSameIdAndContext() {
        Citation citation = Citation.of("p-17", "support-team").withChunkIds(List.of("chunk-1"));
        Citation otherChunks = Citation.of("p-17", "support-team").withChunkIds(List.of("chunk-2"));
        Citation otherContext = Citation.of("p-17", "billing-team");

        assertTrue(citation.sameClaimAs(otherChunks));
        assertFalse(citation.sameClaimAs(otherContext));
    }

    @Test
    void conflictingAcceptsOneIdInTwoContexts() {
        EvidenceItem elsewhere = EvidenceItem.of("Ada wrote no program", Citation.of("p-17", "billing-team"), 0.6);

        Evidence.Conflicting conflicting =
                Evidence.conflicting(List.of(item, elsewhere), ConflictType.Contradiction.INSTANCE);

        assertEquals(List.of(item, elsewhere), conflicting.getItems());
    }

    @Test
    void conflictingRejectsOneClaimTwice() {
        EvidenceItem again = EvidenceItem.of("Ada wrote no program", Citation.of("p-17", "support-team"), 0.6);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Evidence.conflicting(List.of(item, again), ConflictType.Contradiction.INSTANCE));

        assertTrue(thrown.getMessage().contains("items[0]"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("items[1]"), thrown.getMessage());
    }
}
