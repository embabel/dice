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

import org.jetbrains.annotations.ApiStatus

import com.embabel.agent.core.ContextId

/**
 * Takes a fresh snapshot of what a live graph holds, so a [DeclaredObservedDiffer] can compare it
 * against what was declared.
 *
 * This is the storage-side SPI of a pair: [DeclaredSchemaSource] is where an application says what
 * it governs, and this is where a backend says what is there. This module has no graph driver
 * dependency, so a storage layer (Neo4j via Drivine, or anything else) provides the implementation
 * by querying its live database for distinct labels and relationship types. Tests supply a canned
 * [ObservedSchema] and never touch a database.
 *
 * An ordinary interface rather than a `fun interface`, because it carries two entry points: the
 * scoped [observe] an implementation provides, and the no-argument [observe] convenience it gets for
 * free. A SAM lambda can only supply the first. The no-argument form is a real method with a body
 * rather than a Kotlin default argument so Java callers can write `observe()`; Java cannot see a
 * Kotlin default argument. It is also all Java gets: `ContextId` is a Kotlin value class, so the
 * scoped form compiles to a mangled JVM name Java can't call.
 */
@ApiStatus.Experimental
interface ObservedSchemaSource {

    /**
     * @param contextId `null` snapshots the whole graph. Non-null scopes the snapshot to that one
     *   context: only that context's own data is consulted. What "scoped" means concretely is up
     *   to the implementation. A graph-backed one typically walks the context's own nodes, and may
     *   return an empty relationship-type set when it can only reach node labels that way.
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
