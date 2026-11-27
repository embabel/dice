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
import java.util.ArrayList;
import java.util.List;
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
 * E10 attemptEachCall gives one outcome per item, in order, and its call may throw checked exceptions
 * E11 attemptEachCall goes back for items it skipped once a probe gets through
 * E12 attemptEachCall hands each chunk of items and outcomes to the consumer
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

    // E10
    @Test
    void attemptEachCallGivesOneOutcomePerItemInOrder() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker();

        List<ConsecutiveFailureBreaker.Outcome<String>> outcomes = breaker.attemptEachCall(
            List.of("a", "bad", "c"),
            item -> {
                if (item.equals("bad")) {
                    throw new IOException("refused " + item);
                }
                return item.toUpperCase();
            });

        assertEquals(3, outcomes.size());
        assertEquals("A", outcomes.get(0).getValue());
        assertInstanceOf(IOException.class, outcomes.get(1).getException());
        assertEquals("C", outcomes.get(2).getValue());
    }

    // E11
    @Test
    void attemptEachCallRetriesSkippedItemsAfterAProbeGetsThrough() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker(2);
        List<Integer> items = List.of(0, 1, 2, 3, 4);
        AtomicInteger calls = new AtomicInteger();

        List<ConsecutiveFailureBreaker.Outcome<Integer>> outcomes = breaker.attemptEachCall(items, item -> {
            calls.incrementAndGet();
            if (item < 2) {
                throw new IOException("refused " + item);
            }
            return item;
        });

        assertTrue(outcomes.get(0).isFailure());
        assertTrue(outcomes.get(1).isFailure());
        assertEquals(List.of(2, 3, 4), outcomes.subList(2, 5).stream().map(ConsecutiveFailureBreaker.Outcome::getValue).toList());
        assertEquals(5, calls.get());
    }

    // E12
    @Test
    void attemptEachCallHandsEachChunkToTheConsumer() {
        ConsecutiveFailureBreaker breaker = new ConsecutiveFailureBreaker();
        List<List<String>> chunks = new ArrayList<>();

        breaker.attemptEachCall(
            List.of("a", "b", "c"),
            2,
            chunk -> chunks.add(chunk.stream().map(entry -> entry.getKey() + "=" + entry.getValue().getValue()).toList()),
            item -> item.toUpperCase());

        assertEquals(List.of(List.of("a=A", "b=B"), List.of("c=C")), chunks);
    }
}
