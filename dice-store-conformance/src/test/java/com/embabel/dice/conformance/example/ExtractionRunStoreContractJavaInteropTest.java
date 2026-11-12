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
import com.embabel.dice.common.DiceEventListener;
import com.embabel.dice.conformance.AbstractExtractionRunStoreContractTest;
import com.embabel.dice.proposition.extraction.ExtractionRunStore;
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore;

/**
 * A test for an {@code ExtractionRunStore} that is built once with its listener: the listener
 * forwards to whichever one a case supplies.
 */
class ExtractionRunStoreContractJavaInteropTest extends AbstractExtractionRunStoreContractTest {

    private final RedirectableListener events = new RedirectableListener();
    private final ExtractionRunStore runs = new InMemoryExtractionRunStore(events);

    @Override
    protected ExtractionRunStore store(DiceEventListener listener) {
        events.redirectTo(listener);
        return runs;
    }

    /** Forwards each event to the listener it was last pointed at. */
    static final class RedirectableListener implements DiceEventListener {

        private volatile DiceEventListener target = event -> { };

        void redirectTo(DiceEventListener listener) {
            target = listener;
        }

        @Override
        public void onEvent(DiceEvent event) {
            target.onEvent(event);
        }
    }
}
