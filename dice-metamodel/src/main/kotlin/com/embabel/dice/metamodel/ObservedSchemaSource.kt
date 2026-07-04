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

/**
 * Takes a fresh snapshot of what a live graph actually contains, for [DriftCheckRunner] to compare
 * against the declared schema.
 *
 * This module has no graph driver dependency, so it can't implement this itself — a storage layer
 * (Neo4j via Drivine, or anything else) provides the real implementation by querying its live
 * database for distinct entity and relationship type labels. Tests can supply a canned
 * [ObservedSchema] instead of touching a database at all.
 */
fun interface ObservedSchemaSource {

    /**
     * @return a fresh [ObservedSchema] snapshot, captured at call time.
     */
    fun observe(): ObservedSchema
}
