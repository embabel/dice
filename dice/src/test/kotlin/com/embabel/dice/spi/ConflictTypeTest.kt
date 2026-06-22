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
package com.embabel.dice.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConflictTypeTest {

    @Test
    fun `custom accepts a non-blank label`() {
        val custom = ConflictType.Custom("supersession")
        assertEquals("supersession", custom.label)
    }

    @Test
    fun `custom rejects a blank label`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConflictType.Custom("   ")
        }
    }

    @Test
    fun `custom rejects an empty label`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConflictType.Custom("")
        }
    }
}
