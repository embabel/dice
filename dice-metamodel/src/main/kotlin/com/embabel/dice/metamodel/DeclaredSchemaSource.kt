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
 * The schema as declared, ready to hand to [DeclaredObservedDiffer.diffAgainstObserved]: the
 * stamped [version] plus the bare relationship type names it allows.
 *
 * The bare names travel alongside the stamp rather than being recovered from it because
 * [MetamodelVersion.relationshipNames] holds rendered `From-[name]->To` descriptors, and
 * reverse-parsing one back into a bare name is ambiguous for free-text / LLM-derived names (see
 * [DeclaredObservedDiffer.diffAgainstObserved]'s KDoc). Whoever builds a [DeclaredSchema] holds the
 * un-rendered relationship definitions before they were ever stamped, and should pass that set
 * straight through.
 *
 * @property version The stamped declared schema.
 * @property relationshipTypeNames The bare relationship type names [version] allows.
 */
data class DeclaredSchema(
    val version: MetamodelVersion,
    val relationshipTypeNames: Set<String>,
)

/**
 * Supplies the schema an application has declared, for [DriftCheckRunner] to compare against a live
 * graph's [ObservedSchema].
 *
 * This module has no opinion on where a declared schema comes from — a consuming Spring app is
 * expected to implement this over whatever it already uses to define its [MetamodelVersion] (a
 * `DataDictionary`, a config file, a registry, ...) and wire it as a bean. There is no default
 * implementation because there is no default declared schema.
 */
fun interface DeclaredSchemaSource {

    /**
     * @return the current [DeclaredSchema].
     */
    fun declare(): DeclaredSchema
}
