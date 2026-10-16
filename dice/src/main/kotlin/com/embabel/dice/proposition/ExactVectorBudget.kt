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

/** A vector-read failure whose fixed code is safe to expose without backend details. */
interface VectorReadFailure {
    val code: String
}

/** The backend cannot apply filters before selecting its vector results. */
class FilteredVectorUnsupportedException :
    UnsupportedOperationException("FILTERED_VECTOR_UNSUPPORTED"), VectorReadFailure {
    override val code: String get() = "FILTERED_VECTOR_UNSUPPORTED"
}

/** Exact search cannot complete within its candidate allowance. */
class ExactVectorBudgetExceededException :
    IllegalStateException("EXACT_VECTOR_BUDGET_EXCEEDED"), VectorReadFailure {
    override val code: String get() = "EXACT_VECTOR_BUDGET_EXCEEDED"
}

/** Exact search cannot complete within its time allowance. */
class ExactVectorDeadlineExceededException :
    IllegalStateException("EXACT_VECTOR_DEADLINE_EXCEEDED"), VectorReadFailure {
    override val code: String get() = "EXACT_VECTOR_DEADLINE_EXCEEDED"
}

/**
 * Finite permission for an exact vector read, never permission to scan without a bound.
 *
 * [maxCandidates] leaves room for one extra candidate to detect exhaustion. [timeout] must fit
 * in a positive nanosecond interval so deadline arithmetic cannot overflow during conversion.
 */
class ExactVectorBudget(val maxCandidates: Int, val timeout: Duration) {
    init {
        require(maxCandidates > 0 && maxCandidates < Int.MAX_VALUE) {
            "maxCandidates must be positive and leave room for one extra candidate"
        }
        require(!timeout.isZero && !timeout.isNegative && timeout <= Duration.ofNanos(Long.MAX_VALUE)) {
            "timeout must be a positive finite nanosecond interval"
        }
    }

    companion object {
        @JvmField
        val DEFAULT = ExactVectorBudget(10_000, Duration.ofSeconds(5))
    }
}
