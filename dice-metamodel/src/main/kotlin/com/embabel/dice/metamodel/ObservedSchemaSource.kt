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
 * An ordinary interface, not a `fun interface`: it carries two entry points — the scoped
 * [observe] an implementation provides, and the no-argument [observe] convenience it gets for
 * free — and a SAM lambda can only ever supply the first. The no-argument form is a real method
 * with a body rather than a Kotlin default argument so that Java callers can write `observe()`
 * too; Java cannot see a Kotlin default argument. It is also all Java gets: `ContextId` is a
 * Kotlin value class, so the scoped form compiles to a mangled JVM name Java can't call.
 */
interface ObservedSchemaSource {

    /**
     * @param contextId `null` snapshots the whole graph. Non-null scopes the snapshot to that one
     *   context: only that context's own data is consulted. What "scoped" means concretely is up
     *   to the implementation — a graph-backed one typically walks the context's own nodes, and
     *   may return an empty relationship-type set when it can only reach node labels that way.
     * @return a fresh [ObservedSchema] snapshot, captured at call time.
     */
    fun observe(contextId: ContextId?): ObservedSchema

    /**
     * Snapshot the whole graph, unscoped.
     *
     * @return a fresh [ObservedSchema] snapshot, captured at call time.
     */
    fun observe(): ObservedSchema = observe(null)
}
