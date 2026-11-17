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

/**
 * Cypher for ordering by an instant that a node holds as ISO-8601 text.
 *
 * The property-bag stores write an instant as `Instant.toString()`. That prints no fraction on a
 * whole second and three, six or nine digits otherwise, so text order can differ from time order:
 * `Z` sorts after `.` and after every digit, which puts `12:00:05Z` after `12:00:05.500Z`.
 * [sortKey] pads the fraction to nine digits. Keys of that form sort in time order.
 */
internal object InstantText {

    /** The parameter [sortKey] reads [PATTERN] from. Bind it on every statement that uses a key. */
    const val PATTERN_PARAMETER: String = "instantText"

    /** What `Instant.toString()` prints for the years 0000 to 9999. */
    const val PATTERN: String = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3}|\\.\\d{6}|\\.\\d{9})?Z"

    /**
     * A Cypher expression for a fixed-width key that sorts in time order.
     *
     * The key is the empty string when the text is absent or is not an instant. The empty string
     * sorts before every key, so a node with no readable instant counts as the oldest.
     *
     * @param text A Cypher expression for the text, such as `d.createdAt`. Pass a literal of your
     *   own. Never pass caller input.
     * @return The expression, ready to follow `ORDER BY`.
     */
    fun sortKey(text: String): String =
        "CASE WHEN $text =~ \$$PATTERN_PARAMETER " +
            "THEN left($text, 19) + '.' + left(replace(replace(substring($text, 19), 'Z', ''), '.', '') + '000000000', 9) " +
            "ELSE '' END"
}
