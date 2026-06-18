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
 * Wraps another listener and stops it from taking down the caller: if the wrapped listener
 * throws, the error is logged and swallowed instead of bubbling up.
 *
 * This matters because listeners run synchronously, right inside the call that emitted the
 * event — DICE doesn't put them on a separate thread. Without this wrapper, one buggy
 * listener could abort the very operation that produced the event.
 *
 * @property delegate The listener to make exception-safe.
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
 * Delivers each event to several listeners in turn. Every delivery is made exception-safe
 * (see [SafeDiceEventListener]), so one listener throwing doesn't stop the others from
 * hearing about the event.
 *
 * @property listeners The listeners to notify, in order.
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
 * Logs every event it receives at debug level and nothing more. A cheap way to see the
 * event stream while developing or debugging; it never throws.
 */
class LoggingDiceEventListener : DiceEventListener {

    private val logger = LoggerFactory.getLogger(LoggingDiceEventListener::class.java)

    override fun onEvent(event: DiceEvent) {
        logger.debug("DiceEvent: {}", event)
    }
}
