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
package com.embabel.dice.common

/**
 * Test double that records every [DiceEvent] delivered to it, for deterministic
 * assertion in event-emitting tests. Shared capture surface for event tests.
 *
 * This is the canonical recording listener referenced by the decorator, projector,
 * pipeline, and listener-utility tests — it lets a test assert on the exact events
 * (and their order) produced at a deterministic boundary without an LLM in the loop.
 */
class RecordingDiceEventListener : DiceEventListener {

    /** Every event delivered, in delivery order. */
    val events: MutableList<DiceEvent> = mutableListOf()

    override fun onEvent(event: DiceEvent) {
        events.add(event)
    }

    /** Total number of events captured. */
    fun count(): Int = events.size

    /** All captured events of the given concrete type, in delivery order. */
    inline fun <reified T : DiceEvent> eventsOfType(): List<T> =
        events.filterIsInstance<T>()

    /** Reset the capture list (useful between assertions in a single test). */
    fun clear() {
        events.clear()
    }
}
