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

import com.embabel.dice.proposition.ConsecutiveFailureBreaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows that Java code can call the failure breaker through attemptCall and read the outcome.
 *
 * Contract:
 * E1 a successful call gives a success outcome with its value
 * E2 a checked exception comes back as a failure outcome without a try/catch
 * E3 after the limit is reached the breaker is tripped and the next call is skipped
 * E4 a success resets the failure count
 * E5 an Error from the call propagates
 */
class ConsecutiveFailureBreakerJavaInteropTest {

    // E1
    @Test
    void successfulCallGivesSuccessOutcome() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker();

        ConsecutiveFailureBreaker.Outcome<String> outcome = breaker.attemptCall(() -> "ok");

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.isFailure());
        assertFalse(outcome.isSkipped());
        assertEquals("ok", outcome.getValue());
    }

    // E2
    @Test
    void checkedExceptionGivesFailureOutcome() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker();
        IOException expected = new IOException("disk unavailable");

        ConsecutiveFailureBreaker.Outcome<String> outcome = breaker.attemptCall(() -> {
            throw expected;
        });

        assertTrue(outcome.isFailure());
        assertSame(expected, outcome.getException());
    }

    // E3
    @Test
    void callIsSkippedOnceBreakerHasTripped() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker(2);
        breaker.attemptCall(() -> {
            throw new IOException("first failure");
        });
        breaker.attemptCall(() -> {
            throw new IOException("second failure");
        });
        assertTrue(breaker.getTripped());

        AtomicInteger calls = new AtomicInteger();
        ConsecutiveFailureBreaker.Outcome<String> outcome = breaker.attemptCall(() -> {
            calls.incrementAndGet();
            return "never run";
        });

        assertTrue(outcome.isSkipped());
        assertEquals(0, calls.get());
    }

    // E4
    @Test
    void successResetsFailureCount() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker(2);
        breaker.attemptCall(() -> {
            throw new IOException("first failure");
        });
        breaker.attemptCall(() -> "recovered");
        breaker.attemptCall(() -> {
            throw new IOException("second failure");
        });

        assertFalse(breaker.getTripped());
    }

    // E5
    @Test
    void errorFromCallPropagates() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker();

        assertThrows(AssertionError.class, () -> breaker.attemptCall(() -> {
            throw new AssertionError("boom");
        }));
    }
}
