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

import java.time.Duration

sealed interface ApprovedSearchText {
    data object Omitted : ApprovedSearchText
    data object Stored : ApprovedSearchText
    data class Literal(val value: String) : ApprovedSearchText {
        init { require(value.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Approved text exceeds 1MiB" } }
    }
}

data class StrictReadRow(val key: PropositionKey, val ordinal: Int, val text: ApprovedSearchText) {
    init {
        require(key.contextIdValue.isNotBlank() && key.propositionId.isNotBlank()) { "Blank row key" }
        require(ordinal >= 0) { "Negative grant ordinal" }
    }

    companion object {
        /** Shared preflight for strict implementations; an empty list must return without backend calls. */
        @JvmStatic
        fun validateRequest(
            scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
        ) {
            require(rows.size <= 1000 && limit in 0..1000) { "Invalid strict read size" }
            require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofSeconds(5)) { "Invalid strict timeout" }
            require(rows.map { it.key }.toSet().size == rows.size && rows.map { it.ordinal }.toSet().size == rows.size) {
                "Duplicate strict row or ordinal"
            }
            require(rows.all { it.key.contextIdValue in scope.contextIdValues }) { "Strict row outside scope" }
            require(spec.includedKeys == null || spec.includedKeys.all { it.contextIdValue in scope.contextIdValues }) {
                "Included row outside scope"
            }
            require(spec.excludedKeys.all { it.contextIdValue in scope.contextIdValues }) { "Excluded row outside scope" }
            require(rows.sumOf { (it.text as? ApprovedSearchText.Literal)?.value?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
                <= 1024 * 1024) { "Approved text exceeds 1MiB" }
        }
    }
}

/** Strict search uses approved text only; membership is intersected before ranking and limit. */
interface StrictPolicyReadCapable : ContextSetReadCapable {
    val supportsStrictPolicyReads: Boolean get() = true

    fun queryByGrantOrder(
        scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
    ): List<Proposition>

    fun keywordByApprovedText(
        scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>,
        tokens: List<String>, limit: Int, timeout: Duration,
    ): List<Proposition>
}
