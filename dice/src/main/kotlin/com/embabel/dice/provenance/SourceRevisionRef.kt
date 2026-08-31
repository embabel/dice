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
package com.embabel.dice.provenance

import org.jetbrains.annotations.ApiStatus

/**
 * Identifies an opaque revision of a source.
 *
 * A revision is opaque to DICE, exactly as `docs/design/source-revisions.md` describes it: a
 * provider-defined value DICE stores and compares for exact equality, and never parses.
 *
 * Both halves are checked against [SourceIdentityBounds] on construction, so a value too long to
 * store or index is refused here, before it can reach a query or a write. Those two ceilings are
 * the only limit either field has, and they live on this type because this type owns the value.
 * Everything downstream that holds a `SourceRevisionRef` — a revision query, a REST request,
 * evidence carried in from somewhere else, run recording — inherits the same guarantee and adds
 * no cap of its own. So length is never the reason a value that named a source successfully in
 * one place goes on to be refused in another.
 *
 * @property sourceKey Canonical source identity produced by [SourceLocator.key]
 * @property sourceRevision Provider-defined opaque revision value
 */
@ApiStatus.Experimental
data class SourceRevisionRef(
    val sourceKey: String,
    val sourceRevision: String,
) {

    init {
        require(sourceKey.isNotBlank()) { "sourceKey must not be blank" }
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }
        SourceIdentityBounds.requireSourceKeyWithinBounds(sourceKey)
        SourceIdentityBounds.requireSourceRevisionWithinBounds(sourceRevision)
    }
}
