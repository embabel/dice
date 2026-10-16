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

/** The store cannot filter before it ranks vectors, so it refuses rather than rank unfiltered rows. */
class FilteredVectorUnsupportedException : UnsupportedOperationException("FILTERED_VECTOR_UNSUPPORTED")

/** How many eligible rows an exact vector search may score, and how long it may take. */
class ExactVectorBudget(val maxCandidates: Int, val timeout: Duration) {
    init {
        require(maxCandidates > 0) { "maxCandidates must be positive" }
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
    }

    companion object {
        /** Enough rows for one user's working memory, few enough to score in memory quickly. */
        const val DEFAULT_MAX_CANDIDATES = 10_000

        /** How long an exact vector search may run unless the caller says otherwise. */
        @JvmField
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)

        @JvmField
        val DEFAULT = ExactVectorBudget(DEFAULT_MAX_CANDIDATES, DEFAULT_TIMEOUT)
    }
}
