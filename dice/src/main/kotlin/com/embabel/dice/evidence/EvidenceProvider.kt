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
package com.embabel.dice.evidence

import org.jetbrains.annotations.ApiStatus

/**
 * The contract for a lookup that returns [Evidence].
 *
 * A lookup implements this interface with the type of query it takes, such as a search string or a query object.
 *
 * ```java
 * EvidenceProvider<String> provider = query ->
 *     Evidence.missing(Searched.of("ctx-main").withQuery(query));
 * ```
 *
 * ```kotlin
 * val provider = EvidenceProvider<String> { query ->
 *     Evidence.missing(Searched.of("ctx-main").withQuery(query))
 * }
 * ```
 *
 * @param Q the type of query the lookup takes
 */
@ApiStatus.Experimental
fun interface EvidenceProvider<in Q : Any> {

    /**
     * Looks up evidence for [query]. When there is nothing to return the result is [Evidence.Missing].
     *
     * @param query what to look up
     * @return the evidence found
     */
    fun evidence(query: Q): Evidence
}
