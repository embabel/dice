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
 * Compares two metamodel versions and reports what changed.
 *
 * The canonical entry points accept either raw [DataDictionary] instances (for
 * convenience) or pre-computed [MetamodelVersion] stamps (when the caller has
 * already stamped a schema at ingestion time and stored the stamp).
 */
interface MetamodelDiffer {

    /**
     * Compare two [MetamodelVersion] stamps and return a [MetamodelDiff] describing
     * every structural change from [from] to [to].
     *
     * @param from The baseline (older) version.
     * @param to The target (newer) version.
     * @return An immutable diff. [MetamodelDiff.isEmpty] is `true` when the schemas are equivalent.
     */
    fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff

    /**
     * Convenience overload: stamps both dictionaries and then diffs the resulting versions.
     *
     * @param from The baseline (older) [DataDictionary].
     * @param to The target (newer) [DataDictionary].
     * @return An immutable diff.
     */
    fun diff(from: DataDictionary, to: DataDictionary): MetamodelDiff =
        diff(MetamodelVersion.from(from), MetamodelVersion.from(to))
}
