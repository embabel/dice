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

/** Hard bounds every strict policy read checks before it touches a store. */
object StrictReadBounds {
    /** The most rows, grants or results one strict read handles. */
    const val MAX_ROWS = 1000

    /** The most approved literal text one read or policy carries, in UTF-8 bytes. */
    const val MAX_APPROVED_TEXT_BYTES = 1024 * 1024

    /** The longest a strict read may run: the same ceiling an exact vector search has by default. */
    @JvmField
    val MAX_TIMEOUT: Duration = ExactVectorBudget.DEFAULT_TIMEOUT
}

/** The text a granted row may show and a strict search may match. */
sealed interface ApprovedText {
    /** No text: the row shows none and keyword search never matches it. */
    data object Omitted : ApprovedText
    /** The proposition's own stored text. */
    data object Stored : ApprovedText
    /** Replacement text the policy supplies. */
    data class Literal(val value: String) : ApprovedText {
        init { require(value.toByteArray(Charsets.UTF_8).size <= StrictReadBounds.MAX_APPROVED_TEXT_BYTES) { "Approved text exceeds 1MiB" } }
    }
}

/** One granted row and the text a strict search may match. Rows are ranked in list order. */
data class StrictReadRow(val key: PropositionKey, val text: ApprovedText) {
    init {
        require(key.contextIdValue.isNotBlank() && key.propositionId.isNotBlank()) { "Blank row key" }
    }

    companion object {
        /** Checks a strict request before any store call; an empty row list must return without one. */
        @JvmStatic
        fun validateRequest(spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int, timeout: Duration) {
            val scope = spec.scope
            require(rows.size <= StrictReadBounds.MAX_ROWS && limit in 0..StrictReadBounds.MAX_ROWS) {
                "Invalid strict read size"
            }
            require(!timeout.isNegative && !timeout.isZero && timeout <= StrictReadBounds.MAX_TIMEOUT) {
                "Invalid strict timeout"
            }
            require(rows.map { it.key }.toSet().size == rows.size) { "Duplicate strict row" }
            require(rows.all { it.key.contextIdValue in scope.contextIdValues }) { "Strict row outside scope" }
            require(rows.sumOf { (it.text as? ApprovedText.Literal)?.value?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
                <= StrictReadBounds.MAX_APPROVED_TEXT_BYTES) { "Approved text exceeds 1MiB" }
        }
    }
}

/**
 * Strict search uses approved text only; membership is intersected before ranking and limit.
 *
 * Declaring this interface does not mean the store can serve strict reads right now: the graph store
 * declares it with them switched off, and a wrapping store declares it whatever it wraps.
 * [supportsStrictPolicyReads] is the answer; a strict read on a store that reports false fails with
 * [ScopedReadFailure.SCOPED_READ_UNSUPPORTED].
 */
interface StrictPolicyReadCapable : ContextSetReadCapable {
    /** Whether this store can serve strict policy reads now. */
    val supportsStrictPolicyReads: Boolean get() = true

    /** Returns the granted rows the spec admits, in row order, judged at the clock's instant. */
    fun queryByGrantOrder(
        spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
    ): List<Proposition>

    /** Returns the granted rows whose approved text contains a token, most matching tokens first, then in row order. */
    fun keywordByApprovedText(
        spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>,
        tokens: List<String>, limit: Int, timeout: Duration,
    ): List<Proposition>
}
