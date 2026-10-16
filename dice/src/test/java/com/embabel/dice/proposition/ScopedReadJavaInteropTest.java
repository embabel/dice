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
import com.embabel.dice.agent.AllowedSource;
import com.embabel.dice.agent.ApprovedText;
import com.embabel.dice.agent.MemoryField;
import com.embabel.dice.agent.RowProjection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    void namedLimitChainIsImmutableAndAcceptsEitherWitherOrder() {
        var budget = new ExactVectorBudget(10, Duration.ofSeconds(1));
        var base = ScopedReadLimits.configuring();
        var resultsFirst = base.withMaxResults(500).withMaxEager(600);
        var configured = resultsFirst.withMaxProbe(2000).withVectorBudget(budget);
        var limits = configured.limits();
        var reverse = base.withMaxProbe(2000).withVectorBudget(budget)
                .withMaxEager(600).withMaxResults(500).limits();
        var direct = new ScopedReadLimits(500, 2000, 600, budget);
        assertEquals(direct.getMaxResults(), limits.getMaxResults());
        assertEquals(direct.getMaxProbe(), limits.getMaxProbe());
        assertEquals(direct.getMaxEager(), limits.getMaxEager());
        assertSame(budget, limits.getVectorBudget());
        assertEquals(limits.getMaxResults(), reverse.getMaxResults());
        assertEquals(limits.getMaxProbe(), reverse.getMaxProbe());
        assertEquals(limits.getMaxEager(), reverse.getMaxEager());
        assertSame(budget, reverse.getVectorBudget());
        assertEquals(100, base.limits().getMaxResults());
        assertEquals(401, base.limits().getMaxProbe());
        assertEquals(100, configured.withMaxResults(100).limits().getMaxResults());
        assertEquals(500, configured.limits().getMaxResults());
        var directError = assertThrows(ScopedReadException.class,
                () -> new ScopedReadLimits(500, 401, 600, budget));
        assertEquals(directError.getMessage(),
                assertThrows(ScopedReadException.class, resultsFirst::limits).getMessage());
    }

    @Test
    void namedProjectionChainSnapshotsCollectionsAndKeepsEarlierValues() {
        var fields = new HashSet<>(Set.of(MemoryField.ID));
        var keys = new HashSet<>(Set.of("public"));
        var handles = new HashSet<>(Set.of("person"));
        var sources = new ArrayList<>(List.of(new AllowedSource("source", true, false)));
        var base = RowProjection.projecting(ApprovedText.Stored.INSTANCE);
        var withFields = base.withFields(fields);
        var configured = withFields.withMetadataKeys(keys).withEntityHandles(handles).withSources(sources);
        var direct = RowProjection.text(ApprovedText.Stored.INSTANCE).withFields(fields)
                .withMetadataKeys(keys).withEntityHandles(handles).withSources(sources);
        fields.clear();
        keys.clear();
        handles.clear();
        sources.clear();
        var projection = configured.projection();
        assertEquals(direct, projection);
        assertTrue(base.projection().getFields().isEmpty());
        assertEquals(Set.of(MemoryField.ID), withFields.projection().getFields());
        assertTrue(withFields.projection().getMetadataKeys().isEmpty());
        assertEquals(Set.of(MemoryField.OWNER), configured.withFields(Set.of(MemoryField.OWNER)).projection().getFields());
        assertEquals(direct, configured.projection());
        assertThrows(UnsupportedOperationException.class, () -> projection.getFields().clear());
        assertThrows(UnsupportedOperationException.class, () -> projection.getMetadataKeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> projection.getEntityHandles().clear());
        assertThrows(UnsupportedOperationException.class, () -> projection.getSources().clear());
    }

    @Test
    void projectionTerminalUsesTheSameValidationAsExistingWithers() {
        var fields = new HashSet<MemoryField>();
        fields.add(null);
        var invalid = RowProjection.projecting(ApprovedText.Omitted.INSTANCE).withFields(fields);
        fields.clear();
        var directFields = new HashSet<MemoryField>();
        directFields.add(null);
        var direct = assertThrows(IllegalArgumentException.class,
                () -> RowProjection.text(ApprovedText.Omitted.INSTANCE).withFields(directFields));
        assertEquals(direct.getMessage(), assertThrows(IllegalArgumentException.class, invalid::projection).getMessage());
        var keys = RowProjection.projecting(ApprovedText.Omitted.INSTANCE).withMetadataKeys(Set.of(""));
        var keyError = assertThrows(IllegalArgumentException.class,
                () -> RowProjection.text(ApprovedText.Omitted.INSTANCE).withMetadataKeys(Set.of("")));
        assertEquals(keyError.getMessage(), assertThrows(IllegalArgumentException.class, keys::projection).getMessage());
    }

    @Test
    void publicFactoryDescriptorsRemainAvailable() throws Exception {
        assertNotNull(ScopedPropositionReader.class.getMethod("open", PropositionStore.class, ContextReadScope.class));
        assertNotNull(ScopedPropositionReader.class.getMethod("open", PropositionStore.class,
                ContextReadScope.class, ScopedReadLimits.class));
        assertNotNull(ScopedReadSession.class.getMethod("findSimilarWithScores", TextSimilaritySearchRequest.class));
        assertNotNull(ContextSetVectorSearchCapable.class.getMethod("findSimilarWithScores",
                TextSimilaritySearchRequest.class, ContextReadScope.class, ScopedReadSpec.class, ExactVectorBudget.class));
        assertNotNull(ScopedReadLimits.class.getConstructor(int.class, int.class, int.class, ExactVectorBudget.class));
        assertNotNull(RowProjection.class.getMethod("text", ApprovedText.class));
    }
}
