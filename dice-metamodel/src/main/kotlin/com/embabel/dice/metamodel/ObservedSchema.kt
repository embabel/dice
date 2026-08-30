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

import java.time.Instant

/**
 * A snapshot of what a live graph actually contains, as opposed to what the declared
 * [MetamodelVersion] says it should contain.
 *
 * This is a plain value type — nothing in this module knows how to go and read a graph.
 * A storage layer (Neo4j or otherwise) is expected to build one of these by querying the
 * live database for its distinct entity and relationship type labels, then hand it here
 * to be compared against the declared schema. That's deliberate: schemas in the wild are
 * often partly dynamic (integrations contribute types that come and go), so what's actually
 * in the graph can drift from what was ever declared, and this module needs a way to talk
 * about that without depending on any particular graph driver.
 *
 * @property entityTypeNames Entity type (label) names actually observed in the graph.
 * @property relationshipTypeNames Relationship type names actually observed in the graph.
 * @property capturedAt When this snapshot was taken.
 */
data class ObservedSchema(
    val entityTypeNames: Set<String>,
    val relationshipTypeNames: Set<String>,
    val capturedAt: Instant,
)
