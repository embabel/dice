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
package com.embabel.dice.projection.lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReconciliationDecisionTest {

    @Test
    fun `Adopt and Align reject blank targetRef`() {
        assertThrows(IllegalArgumentException::class.java) { ReconciliationDecision.Adopt("") }
        assertThrows(IllegalArgumentException::class.java) { ReconciliationDecision.Adopt("   ") }
        assertThrows(IllegalArgumentException::class.java) { ReconciliationDecision.Align("") }
        assertThrows(IllegalArgumentException::class.java) { ReconciliationDecision.Align("   ") }
    }

    @Test
    fun `Adopt and Align accept a non-blank targetRef`() {
        assertEquals("node-42", ReconciliationDecision.Adopt("node-42").targetRef)
        assertEquals("node-77", ReconciliationDecision.Align("node-77").targetRef)
    }
}
