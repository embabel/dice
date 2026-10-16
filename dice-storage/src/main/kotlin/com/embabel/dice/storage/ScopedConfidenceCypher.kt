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

/** Live confidence from the same persisted inputs used by Proposition.effectiveConfidenceAt. */
internal object ScopedConfidenceCypher {
    fun expression(nodeAlias: String, asOfParameter: String, decayKParameter: String): String {
        // These names belong to the compiler, never to a query or policy supplied by a caller.
        listOf(nodeAlias, asOfParameter, decayKParameter).forEach {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid compiler identifier" }
        }
        val p = nodeAlias
        val at = "datetime($" + asOfParameter + ")"
        val k = "$" + decayKParameter
        val content = "coalesce($p.contentRevised, $p.created)"
        val accessed = "coalesce($p.lastAccessed, $p.created)"
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
              WHEN $p.invalidatedAt IS NOT NULL AND $p.invalidatedAt <= $at THEN 0.0
              WHEN $p.validFrom IS NOT NULL THEN
                CASE
                  WHEN $at < $p.validFrom OR ($p.validTo IS NOT NULL AND $at >= $p.validTo) THEN 0.0
                  WHEN $p.validTo IS NOT NULL THEN $p.confidence
                  ELSE ${decayed("$p.validFrom")}
                END
              ELSE ${decayed(anchor)}
            END)
        """.trimIndent()
    }
}
