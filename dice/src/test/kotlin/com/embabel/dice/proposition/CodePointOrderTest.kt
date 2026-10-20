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
package com.embabel.dice.proposition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodePointOrderTest {

    @Test
    fun `L1 code point order differs from UTF-16 where surrogates meet U+E000 to U+FFFF`() {
        assertTrue(CodePointOrder.compare("｡", "𐀀") < 0)
        // Kotlin's own comparison puts U+10000 first, because its first UTF-16 unit is U+D800.
        assertTrue("｡" > "𐀀")
        assertTrue(CodePointOrder.compare("a", "ab") < 0)
        assertEquals(0, CodePointOrder.compare("𐀀x", "𐀀x"))
        // A lone surrogate sorts as its own value, below every supplementary character.
        assertTrue(CodePointOrder.compare("\uD800", "𐀀") < 0)
    }
}
