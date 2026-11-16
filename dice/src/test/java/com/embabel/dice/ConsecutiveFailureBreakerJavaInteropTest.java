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
 * E6 an interrupted call is a failure, sets the thread's interrupt flag again, and does not trip the breaker
 * E7 Outcome.success gives a success outcome with its value
 * E8 Outcome.failure gives a failure outcome with its exception
 * E9 Outcome.skipped gives a skipped outcome with no value or exception
 * R1 a skipped outcome with a value is rejected, and one without a value is accepted
 * R2 an outcome with both a value and an exception is rejected, and one with only one of them is accepted
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

    // E6
    @Test
    void interruptedCallIsFailureAndRestoresInterruptFlag() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker(5);

        ConsecutiveFailureBreaker.Outcome<String> outcome = breaker.attemptCall(() -> {
            throw new InterruptedException("stop");
        });

        assertTrue(outcome.isFailure());
        assertInstanceOf(InterruptedException.class, outcome.getException());
        // Reading the flag also clears it, so later tests start with a clean thread.
        assertTrue(Thread.interrupted());
        assertFalse(breaker.getTripped());
    }

    // E7
    @Test
    void successFactoryGivesSuccessOutcome() {
        ConsecutiveFailureBreaker.Outcome<String> outcome = ConsecutiveFailureBreaker.Outcome.success("ok");

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.isFailure());
        assertFalse(outcome.isSkipped());
        assertEquals("ok", outcome.getValue());
        assertNull(outcome.getException());
    }

    // E8
    @Test
    void failureFactoryGivesFailureOutcome() {
        IOException expected = new IOException("disk unavailable");

        ConsecutiveFailureBreaker.Outcome<String> outcome = ConsecutiveFailureBreaker.Outcome.failure(expected);

        assertTrue(outcome.isFailure());
        assertFalse(outcome.isSuccess());
        assertFalse(outcome.isSkipped());
        assertSame(expected, outcome.getException());
        assertNull(outcome.getValue());
    }

    // E9
    @Test
    void skippedFactoryGivesSkippedOutcome() {
        ConsecutiveFailureBreaker.Outcome<String> outcome = ConsecutiveFailureBreaker.Outcome.skipped();

        assertTrue(outcome.isSkipped());
        assertFalse(outcome.isSuccess());
        assertFalse(outcome.isFailure());
        assertNull(outcome.getValue());
        assertNull(outcome.getException());
    }

    // R1
    @Test
    void skippedOutcomeWithValueIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsecutiveFailureBreaker.Outcome<>(true, "x", null));
        assertDoesNotThrow(() -> new ConsecutiveFailureBreaker.Outcome<String>(true, null, null));
    }

    // R2
    @Test
    void outcomeWithValueAndExceptionIsRejected() {
        IOException expected = new IOException("disk unavailable");

        assertThrows(IllegalArgumentException.class,
                () -> new ConsecutiveFailureBreaker.Outcome<>(false, "x", expected));
        assertDoesNotThrow(() -> new ConsecutiveFailureBreaker.Outcome<>(false, "x", null));
        assertDoesNotThrow(() -> new ConsecutiveFailureBreaker.Outcome<String>(false, null, expected));
    }
}
