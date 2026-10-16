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

import java.util.Collections

/** An immutable, finite set of context IDs supplied by the host's read policy. */
class ContextReadScope private constructor(ids: Collection<String>) {

    val contextIdValues: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ids))

    companion object {
        /** IDs are preserved verbatim: whitespace in a nonblank ID is significant. */
        @JvmStatic
        fun ofValues(ids: Collection<String>): ContextReadScope {
            val snapshot = ids.toList()
            require(snapshot.isNotEmpty()) { "A read scope must contain at least one context ID" }
            require(snapshot.none { it.isBlank() }) { "Context IDs must not be blank" }
            return ContextReadScope(snapshot)
        }
    }
}
