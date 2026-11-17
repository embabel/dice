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

import com.embabel.dice.projection.memory.collector.CollectorRunContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checks that Java callers get the same run context from the short and long forms of
 * CollectorRunContext.of, and that the defaults match what the Kotlin side uses.
 *
 * Contract, as comment only:
 * E1: of(runId, contextIdValue) equals the Kotlin fixture built with the constructor defaults.
 * E2: of(runId, contextIdValue) has dryRun false and the system UTC clock.
 * E3: the longer forms keep the run id, context, dryRun flag and clock they were given.
 */
class CollectorRunContextJavaInteropTest {

    @Test
    void shortOfMatchesFixtureBuiltWithDefaults() {
        assertEquals(ContextIdFixtures.collectorRunContext("acme"), CollectorRunContext.of("run-1", "acme"));
    }

    @Test
    void shortOfUsesDryRunFalseAndSystemUtcClock() {
        CollectorRunContext context = CollectorRunContext.of("run-1", "acme");
        assertFalse(context.getDryRun());
        assertEquals(Clock.systemUTC(), context.getClock());
    }

    @Test
    void threeArgOfKeepsDryRunAndDefaultsClock() {
        CollectorRunContext context = CollectorRunContext.of("run-1", "acme", true);
        assertEquals("run-1", context.getRunId());
        assertEquals("acme", context.getContextIdValue());
        assertEquals(true, context.getDryRun());
        assertEquals(Clock.systemUTC(), context.getClock());
    }

    @Test
    void fourArgOfKeepsEveryValue() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        CollectorRunContext context = CollectorRunContext.of("run-2", "globex", true, clock);
        assertEquals("run-2", context.getRunId());
        assertEquals("globex", context.getContextIdValue());
        assertEquals(true, context.getDryRun());
        assertEquals(clock, context.getClock());
    }
}
