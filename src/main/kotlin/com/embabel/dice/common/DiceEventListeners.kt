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

import org.slf4j.LoggerFactory

/**
 * A [DiceEventListener] decorator that isolates the emitting caller from a misbehaving
 * delegate: any [Throwable] raised while delivering an event is caught and logged rather
 * than propagated.
 *
 * Mirrors the catch-Throwable-and-log graceful-degradation precedent in
 * [com.embabel.dice.agent.Memory.loadEagerMemories]. Since DICE ships no threading policy,
 * listeners run synchronously inside the emit call; this wrapper ensures a single faulty
 * listener cannot abort the operation that produced the event.
 *
 * @property delegate The listener whose delivery should be made exception-safe.
 */
class SafeDiceEventListener(
    private val delegate: DiceEventListener,
) : DiceEventListener {

    private val logger = LoggerFactory.getLogger(SafeDiceEventListener::class.java)

    override fun onEvent(event: DiceEvent) {
        try {
            delegate.onEvent(event)
        } catch (t: Throwable) {
            logger.warn("DiceEventListener {} threw while handling {}", delegate, event.javaClass.simpleName, t)
        }
    }
}

/**
 * A [DiceEventListener] that fans an event out to every listener in [listeners]. Each
 * delivery is wrapped with [SafeDiceEventListener] semantics so that one throwing listener
 * does not prevent the rest of the chain from receiving the event.
 *
 * @property listeners The listeners to fan out to, in order.
 */
class CompositeDiceEventListener(
    private val listeners: List<DiceEventListener>,
) : DiceEventListener {

    private val safeListeners: List<SafeDiceEventListener> = listeners.map(::SafeDiceEventListener)

    override fun onEvent(event: DiceEvent) {
        safeListeners.forEach { it.onEvent(event) }
    }
}

/**
 * A [DiceEventListener] that logs each received event at debug level and does nothing else.
 * Useful as a low-overhead observability hook; never throws.
 */
class LoggingDiceEventListener : DiceEventListener {

    private val logger = LoggerFactory.getLogger(LoggingDiceEventListener::class.java)

    override fun onEvent(event: DiceEvent) {
        logger.debug("DiceEvent: {}", event)
    }
}
