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

/**
 * Compares strings by Unicode code point, the order the graph stores use for stored text.
 * Kotlin's own string comparison works on UTF-16 units and would put characters above U+FFFF
 * before U+E000..U+FFFF, so ties would break differently on resident and database backends.
 */
internal object CodePointOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(i)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
        }
        return a.length.compareTo(b.length)
    }
}

/** Context first, then proposition id, both by code point: how every scoped read breaks ties. */
internal val propositionKeyOrder: Comparator<PropositionKey> =
    compareBy(CodePointOrder) { key: PropositionKey -> key.contextIdValue }.thenBy(CodePointOrder) { it.propositionId }
