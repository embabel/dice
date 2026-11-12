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
package com.embabel.dice.store

import com.embabel.dice.proposition.PropositionQuery
import org.jetbrains.annotations.ApiStatus

/**
 * Stops a store contract block from calling into an enclosing block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
@ApiStatus.Experimental
annotation class StoreContractDsl

/**
 * Checks that a filter on a store request only selects candidates, and copies its collections so
 * a list the caller keeps cannot change the request afterwards. The request sets the order and
 * the size of the result, so a filter that carries either is rejected.
 *
 * @param filter The filter to check.
 * @return A copy of the filter that shares no collection with the caller.
 * @throws IllegalArgumentException when [filter] carries an ordering or a limit.
 */
internal fun requireNarrowingFilter(filter: PropositionQuery): PropositionQuery {
    require(filter.orderBy == PropositionQuery.OrderBy.NONE) {
        "A request filter must not carry an ordering (${filter.orderBy}). The request sets the order."
    }
    require(filter.limit == null) {
        "A request filter must not carry a limit (${filter.limit}). The request sets the result size."
    }
    return filter.copy(
        anyEntityIds = filter.anyEntityIds?.toList(),
        allEntityIds = filter.allEntityIds?.toList(),
        statuses = filter.statuses?.toSet(),
    )
}
