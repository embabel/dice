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
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionPersistenceResult;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.ExtractionRunKey;
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore;
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore;
import com.embabel.dice.proposition.extraction.PersistOutcome;
import com.embabel.dice.proposition.extraction.PersistStep;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds a persist unit, writes it and reads the outcome from Java.
 */
class PersistUnitJavaInteropTest {

    private final InMemoryPropositionRepository claims = new InMemoryPropositionRepository(null);

    private final InMemoryNamedEntityDataRepository entities =
            new InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("java-interop"));

    /**
     * Builds the active claim every test writes.
     *
     * @return a proposition with id {@code java-unit-claim}
     */
    private Proposition claim() {
        Instant now = Instant.now();
        return Proposition.create(
                "java-unit-claim",
                "java-unit-tenant",
                "Alice is a software engineer",
                List.of(),
                0.8,
                0.0,
                0.5,
                null,
                List.of(),
                now,
                now,
                Instant.now(),
                PropositionStatus.ACTIVE
        );
    }

    /**
     * Builds a unit holding the one claim and no entities.
     *
     * @return a persist unit with no run attached
     */
    private PersistUnit unit() {
        return PersistUnit.of(List.of(claim()), List.of(), List.of());
    }

    @Test
    void storesAUnitAndReadsTheOutcome() {
        PersistOutcome outcome = PersistUnitWriter.inMemory(claims, entities).write(unit());

        assertTrue(outcome.isStored());
        if (outcome instanceof PersistOutcome.Stored stored) {
            assertTrue(stored.getPersisted().getCanonicalIds().contains("java-unit-claim"));
        } else {
            fail("Expected a stored outcome, got " + outcome);
        }
        assertNotNull(claims.findById("java-unit-claim"));
    }

    @Test
    void aFailedUnitNamesItsStepAndLeavesNothing() {
        InMemoryExtractionRunStore runStore = new InMemoryExtractionRunStore();
        InMemoryPropositionRunLinkStore links = new InMemoryPropositionRunLinkStore(runStore, claims);
        PersistUnit unit = unit().withRun(ExtractionRunKey.of("java-unit-tenant", "missing-run"));

        PersistOutcome outcome = PersistUnitWriter.inMemory(claims, entities, links).write(unit);

        PersistOutcome.Failed failed = assertInstanceOf(PersistOutcome.Failed.class, outcome);
        assertEquals(PersistStep.RUN_LINKS, failed.getStep());
        assertTrue(failed.getLeftInStore().isEmpty());
        assertNull(claims.findById("java-unit-claim"));
        Exception thrown = assertThrows(Exception.class, outcome::persistedOrThrow);
        assertSame(failed.getCause(), thrown);
    }

    @Test
    void aWriterCanBeALambda() {
        PersistUnitWriter writer = u -> PersistOutcome.stored(PropositionPersistenceResult.EMPTY);

        assertTrue(writer.write(unit()).isStored());
    }
}
