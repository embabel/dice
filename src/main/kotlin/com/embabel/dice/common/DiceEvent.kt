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

import com.embabel.common.core.types.Timestamped
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "fqn"
)
interface DiceEvent : Timestamped

data class ContradictionEvent(
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

fun interface DiceEventListener {
    fun onEvent(event: DiceEvent)

    companion object {
        val DEV_NULL: DiceEventListener = DevNull
    }
}

private object DevNull : DiceEventListener {
    override fun onEvent(event: DiceEvent) {
        // Do nothing
    }
}
