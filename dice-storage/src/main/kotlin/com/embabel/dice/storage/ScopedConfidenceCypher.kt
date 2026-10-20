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

/** Live confidence and timestamp normalisation, shared by scoped reads and the decay sweep. */
internal object ScopedConfidenceCypher {
    // Stored timestamps are datetimes in any zone or ISO strings from an older write path; compare them as UTC instants.
    fun instant(value: String): String =
        "(CASE WHEN $value IS NULL THEN null ELSE datetime({datetime: datetime(toString($value)), timezone: '+00:00'}) END)"

    fun expression(nodeAlias: String, asOfParameter: String, decayKParameter: String): String {
        // These names belong to the compiler, never to a query or policy supplied by a caller.
        listOf(nodeAlias, asOfParameter, decayKParameter).forEach {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid compiler identifier" }
        }
        val p = nodeAlias
        val at = "datetime(${'$'}$asOfParameter)"
        val k = "${'$'}$decayKParameter"
        val content = instant("coalesce($p.contentRevised, $p.created)")
        val accessed = instant("coalesce($p.lastAccessed, $p.created)")
        val validFrom = instant("$p.validFrom")
        val validTo = instant("$p.validTo")
        val invalidatedAt = instant("$p.invalidatedAt")
        val anchor = "(CASE WHEN $content >= $accessed THEN $content ELSE $accessed END)"
        fun decayed(from: String): String {
            // inSeconds preserves elapsed time (including nanoseconds), unlike calendar days.
            // Positive integer division truncates complete 86400-second days, just like Duration.toDays.
            val days = "(CASE WHEN $at < $from THEN 0 " +
                "ELSE duration.inSeconds($from, $at).seconds / 86400 END)"
            return "$p.confidence * exp(-coalesce($p.decay, 0.0) * $k * $days)"
        }
        return """
            (CASE
              WHEN $p.invalidatedAt IS NOT NULL AND $invalidatedAt <= $at THEN 0.0
              WHEN $p.validFrom IS NOT NULL THEN
                CASE
                  WHEN $at < $validFrom OR ($p.validTo IS NOT NULL AND $at >= $validTo) THEN 0.0
                  WHEN $p.validTo IS NOT NULL THEN $p.confidence
                  ELSE ${decayed(validFrom)}
                END
              ELSE ${decayed(anchor)}
            END)
        """.trimIndent()
    }
}
