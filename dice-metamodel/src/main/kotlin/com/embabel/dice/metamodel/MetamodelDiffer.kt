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

import com.embabel.agent.core.DataDictionary

/**
 * Compares two declared schemas and says what changed.
 *
 * A version stamp answers "is this the same schema as before?"; a differ answers "and what moved?"
 * — which is what you need to decide whether stored knowledge is still described by the schema it
 * was extracted under.
 *
 * Take the [MetamodelVersion] overload when you already have stamps, which is the normal case: an
 * application stamps at ingestion time and stores the stamp, so the older side comes back out of a
 * [MetamodelVersionStore] rather than being recomputed from a dictionary that has since moved on.
 */
interface MetamodelDiffer {

    /**
     * Compare two stamps.
     *
     * @param from The baseline (older) version.
     * @param to The target (newer) version.
     * @return An immutable diff. [MetamodelDiff.isEmpty] is `true` when the schemas are equivalent.
     */
    fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff

    /**
     * Stamp two dictionaries and diff the results.
     *
     * This governs everything in both dictionaries, the closed-world reading. If the application
     * governs only part of its domain, stamp with its [GovernedTypeSelector] first (or take the two
     * stamps off `DeclaredSchema.from(...)`) and use the other overload — otherwise exploratory
     * types nobody committed to will show up as schema changes.
     *
     * @param from The baseline (older) [DataDictionary].
     * @param to The target (newer) [DataDictionary].
     * @return An immutable diff.
     */
    fun diff(from: DataDictionary, to: DataDictionary): MetamodelDiff =
        diff(MetamodelVersion.from(from), MetamodelVersion.from(to))
}

/**
 * Compares a [DeclaredSchema] against an [ObservedSchema] — what was declared against what a live
 * graph actually holds — and says where the two disagree.
 *
 * Its own interface rather than another overload on [MetamodelDiffer], because it answers a
 * different question. [MetamodelDiffer] compares two declarations, both of them decisions somebody
 * made; this compares a decision to an observation. The answer shape differs too: a
 * [MetamodelDiff] is a symmetric list of changes, while a [DeclaredObservedDiff] separates drift
 * (observed but undeclared, actionable) from merely-unobserved (declared but empty, normal).
 * Folding them together would force every caller to sift a generic change list to tell those apart.
 *
 * It takes a whole [DeclaredSchema] rather than a bare stamp because the comparison needs the bare
 * relationship type names, and those can only travel alongside the stamp — never be recovered from
 * it. [MetamodelVersion.relationshipNames] holds rendered `From-[name]->To` descriptors, and these
 * names come from free text and LLM extraction, so a name can itself contain a `-[...]->`-shaped
 * substring; reverse-parsing a descriptor is ambiguous and silently picks the wrong segment.
 * `DeclaredSchema.from(dictionary, selector)` builds both halves under the same governance rule, so
 * they can't drift apart.
 */
interface DeclaredObservedDiffer {

    /**
     * Compare [declared] against [observed].
     *
     * @param declared The schema as declared, stamp plus bare relationship type names.
     * @param observed A snapshot of what the live graph actually holds.
     * @return An immutable diff separating drift from unobserved-but-declared types.
     */
    fun diffAgainstObserved(declared: DeclaredSchema, observed: ObservedSchema): DeclaredObservedDiff
}
