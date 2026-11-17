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
package com.embabel.dice.conformance

import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import org.jetbrains.annotations.ApiStatus

/**
 * A listener that forwards each event to the listener it was last pointed at.
 *
 * [AbstractExtractionRunStoreContractTest] passes a new listener to its factory in each case. A
 * store that is built once, such as a Spring bean, is built with one of these. The factory calls
 * [redirectTo] with the listener the case supplied and returns the store. An event that arrives
 * before the first call to [redirectTo] is dropped.
 *
 * Java:
 * ```java
 * RedirectableEventListener events = new RedirectableEventListener();
 * ExtractionRunStore runs = new MyExtractionRunStore(events);
 * events.redirectTo(listener);
 * ```
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
class RedirectableEventListener : DiceEventListener {

    /** Volatile, because a store announces events from its own threads. */
    @Volatile
    private var target: DiceEventListener = DiceEventListener.DEV_NULL

    /**
     * Sends every later event to [listener].
     *
     * @param listener the listener that receives events from now on
     */
    fun redirectTo(listener: DiceEventListener) {
        target = listener
    }

    override fun onEvent(event: DiceEvent) {
        target.onEvent(event)
    }
}
