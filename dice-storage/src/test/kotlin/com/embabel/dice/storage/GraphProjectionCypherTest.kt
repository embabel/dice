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
package com.embabel.dice.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the pure depth-clamping math the native graph queries rely on: no database needed, since
 * [GraphProjectionCypher.clampDepth] is just arithmetic.
 */
class GraphProjectionCypherTest {

    @Test
    fun `depth within range passes through unchanged`() {
        assertEquals(1, GraphProjectionCypher.clampDepth(1))
        assertEquals(3, GraphProjectionCypher.clampDepth(3))
        assertEquals(GraphProjectionCypher.MAX_DEPTH, GraphProjectionCypher.clampDepth(GraphProjectionCypher.MAX_DEPTH))
    }

    @Test
    fun `depth above MAX_DEPTH clamps to the ceiling`() {
        assertEquals(GraphProjectionCypher.MAX_DEPTH, GraphProjectionCypher.clampDepth(GraphProjectionCypher.MAX_DEPTH + 1))
        assertEquals(GraphProjectionCypher.MAX_DEPTH, GraphProjectionCypher.clampDepth(1000))
    }

    @Test
    fun `depth below 1 clamps to the floor`() {
        assertEquals(1, GraphProjectionCypher.clampDepth(0))
        assertEquals(1, GraphProjectionCypher.clampDepth(-5))
    }
}
