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
package com.embabel.dice.metamodel

import com.embabel.agent.core.ContextId

/**
 * Takes a fresh snapshot of what a live graph actually contains, for [DriftCheckRunner] to compare
 * against the declared schema.
 *
 * This module has no graph driver dependency, so it can't implement this itself — a storage layer
 * (Neo4j via Drivine, or anything else) provides the real implementation by querying its live
 * database for distinct entity and relationship type labels. Tests can supply a canned
 * [ObservedSchema] instead of touching a database at all.
 *
 * An ordinary interface, not a `fun interface`: [observe] takes an optional [ContextId], and only
 * a normal override — not a SAM lambda — can carry that parameter's default value, which is what
 * lets `observe()` with no argument resolve to the global (unscoped) snapshot.
 */
interface ObservedSchemaSource {

    /**
     * @param contextId `null` (the default) snapshots the whole graph. Non-null scopes the
     *   snapshot to that one context: only that context's own data is consulted. See
     *   [DrivineObservedSchemaSource][com.embabel.dice.storage.autoconfigure.DrivineObservedSchemaSource]
     *   for what "scoped" means concretely (and why the scoped relationship-type set may come back
     *   empty).
     * @return a fresh [ObservedSchema] snapshot, captured at call time.
     */
    fun observe(contextId: ContextId? = null): ObservedSchema
}
