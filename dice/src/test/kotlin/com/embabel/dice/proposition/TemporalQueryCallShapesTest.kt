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
package com.embabel.dice.proposition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Checks that the temporal queries on TemporalQueryCapable can still be used as untyped
 * Kotlin callable references, and that calling them without a decay rate still works.
 *
 * Contract:
 * R1: the store is a plain object, so TemporalQueryCapable must keep a single abstract method.
 * E1: the callable reference store::findAllOrderedByEffectiveConfidence compiles with no expected type and,
 *     called with DEFAULT_DECAY_K, puts the fresh proposition first.
 * E2: the callable reference store::findByEffectiveConfidenceAbove compiles with no expected type and,
 *     called with a threshold and DEFAULT_DECAY_K, leaves the decayed proposition out.
 * E3: the no-k call store.findAllOrderedByEffectiveConfidence() still compiles and uses DEFAULT_DECAY_K.
 * I1: calling a query never changes the propositions the store returns.
 */
class TemporalQueryCallShapesTest {

    private val now = Instant.now()
    private val lastYear = now.minus(365, ChronoUnit.DAYS)

    // Fresh has no decay, so it keeps full confidence. Old has heavy decay and is a year old.
    private val fresh = Proposition.draft("acme", "Alice lives in Paris", 0.9)
        .withCreated(now)
        .withContentRevised(now)
        .withMetadataRevised(now)
        .withLastAccessed(now)
        .toProposition()

    private val old = Proposition.draft("acme", "Bob lived in Lyon", 0.9)
        .withDecay(0.5)
        .withCreated(lastYear)
        .withContentRevised(lastYear)
        .withMetadataRevised(lastYear)
        .withLastAccessed(lastYear)
        .toProposition()

    private val store: TemporalQueryCapable = object : TemporalQueryCapable {
        override fun findAll(): List<Proposition> = listOf(fresh, old)
    }

    // E1: the untyped reference compiles and returns the fresh proposition first.
    @Test
    fun findAllOrderedByEffectiveConfidenceCanBeReferencedWithoutType() {
        val f = store::findAllOrderedByEffectiveConfidence

        val result = f(Proposition.DEFAULT_DECAY_K)

        assertSame(fresh, result.first())
        assertEquals(store.findAllOrderedByEffectiveConfidence(Proposition.DEFAULT_DECAY_K), result)
    }

    // E2: the untyped reference compiles and leaves out the decayed proposition.
    @Test
    fun findByEffectiveConfidenceAboveCanBeReferencedWithoutType() {
        val g = store::findByEffectiveConfidenceAbove

        val result = g(0.5, Proposition.DEFAULT_DECAY_K)

        assertEquals(listOf(fresh), result)
    }

    // E3: calling with no k still compiles and gives the same answer as the explicit default.
    @Test
    fun omittingKUsesTheDefaultDecayRate() {
        assertEquals(
            store.findAllOrderedByEffectiveConfidence(Proposition.DEFAULT_DECAY_K),
            store.findAllOrderedByEffectiveConfidence(),
        )
    }

    // I1: queries leave the stored propositions untouched.
    @Test
    fun callingQueriesDoesNotChangeStoredPropositions() {
        store.findAllOrderedByEffectiveConfidence()
        store.findByEffectiveConfidenceAbove(0.5)

        assertEquals(0.9, fresh.confidence, 1e-12)
        assertEquals(0.5, old.decay, 1e-12)
    }
}
