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
package com.embabel.dice.proposition;

import com.embabel.common.core.types.TextSimilaritySearchRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScopedReadJavaInteropTest {

    @Test
    void factoriesWithersAndSessionAreCallableFromJava() {
        PropositionStore store = mock(PropositionStore.class,
                withSettings().extraInterfaces(ContextSetReadCapable.class));
        ContextSetReadCapable nativeReads = (ContextSetReadCapable) store;
        when(nativeReads.getSupportsScopedReads()).thenReturn(true);
        when(nativeReads.query(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(nativeReads.count(any(), any())).thenReturn(7L);
        when(nativeReads.keywordOverlap(any(), any(), anyList(), anyInt())).thenReturn(List.of());
        ContextReadScope scope = ContextReadScope.ofValues(List.of("A", "B"));
        ExactVectorBudget budget = new ExactVectorBudget(10, Duration.ofSeconds(1));
        ScopedReadLimits limits = ScopedReadLimits.DEFAULT
                .withMaxResults(5).withMaxProbe(200).withMaxEager(4).withVectorBudget(budget);
        assertSame(budget, limits.getVectorBudget());
        assertEquals(5, limits.getMaxResults());
        assertEquals(200, limits.getMaxProbe());
        assertEquals(4, limits.getMaxEager());
        ScopedPropositionReader defaults = ScopedPropositionReader.open(store, scope);
        ScopedPropositionReader reader = ScopedPropositionReader.open(store, scope, limits)
                .withLimits(limits)
                .restrict(PropositionQuery.againstContext("A"))
                .includingOnly(List.of(new PropositionKey("A", "fact")))
                .excluding(List.of(new PropositionKey("B", "fact")));
        Instant asOf = Instant.parse("2026-01-01T00:00:00Z");
        ScopedReadSession session = reader.snapshot(asOf, 2.0)
                .restrict(PropositionQuery.againstContext("A"))
                .includingOnly(List.of(new PropositionKey("A", "fact")))
                .excluding(List.of());
        assertSame(scope, reader.getScope());
        assertSame(limits, session.getLimits());
        assertSame(ScopedReadLimits.DEFAULT, defaults.getLimits());
        assertEquals(asOf, session.getSpec().getAsOf());
        assertTrue(session.query(ReadOrder.CREATED_DESC, 5).isEmpty());
        assertTrue(session.keywordOverlap(List.of("fact"), 5).isEmpty());
        assertEquals(7L, session.count());
        ScopedReadException failure = assertThrows(ScopedReadException.class,
                () -> session.query(ReadOrder.NONE, 201));
        assertEquals("INVALID_SCOPED_READ", failure.getCode());
    }

    @Test
    void publicFactoryDescriptorsRemainAvailable() throws Exception {
        assertNotNull(ScopedPropositionReader.class.getMethod("open", PropositionStore.class, ContextReadScope.class));
        assertNotNull(ScopedPropositionReader.class.getMethod("open", PropositionStore.class,
                ContextReadScope.class, ScopedReadLimits.class));
        assertNotNull(ScopedReadSession.class.getMethod("findSimilarWithScores", TextSimilaritySearchRequest.class));
        assertNotNull(ContextSetVectorSearchCapable.class.getMethod("findSimilarWithScores",
                TextSimilaritySearchRequest.class, ContextReadScope.class, ScopedReadSpec.class, ExactVectorBudget.class));
        ScopedReadLimits.Configuration config = new ScopedReadLimits.Configuration();
        config.setMaxProbe(500);
        assertEquals(500, config.getMaxProbe());
    }
}
