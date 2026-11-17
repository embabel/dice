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
package com.embabel.dice.conformance.example;

import com.embabel.dice.common.DiceEvent;
import com.embabel.dice.conformance.RedirectableEventListener;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that the redirectable event listener works when called from Java. */
class RedirectableEventListenerJavaInteropTest {

    private final DiceEvent event = new DiceEvent() {
        @Override
        public Instant getTimestamp() {
            return Instant.EPOCH;
        }
    };

    @Test
    void eventsGoToTheListenerItWasLastPointedAt() {
        RedirectableEventListener events = new RedirectableEventListener();
        List<DiceEvent> first = new ArrayList<>();
        List<DiceEvent> second = new ArrayList<>();

        events.redirectTo(first::add);
        events.onEvent(event);
        events.redirectTo(second::add);
        events.onEvent(event);
        events.onEvent(event);

        assertEquals(1, first.size());
        assertEquals(2, second.size());
    }

    @Test
    void anEventBeforeTheFirstRedirectIsDropped() {
        RedirectableEventListener events = new RedirectableEventListener();
        List<DiceEvent> received = new ArrayList<>();

        assertDoesNotThrow(() -> events.onEvent(event));
        events.redirectTo(received::add);

        assertTrue(received.isEmpty());
    }
}
