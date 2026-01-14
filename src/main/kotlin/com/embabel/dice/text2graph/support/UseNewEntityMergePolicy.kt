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
package com.embabel.dice.text2graph.support

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.text2graph.*

/**
 * Always adds new entities and ignores existing or vetoed entities.
 * This is probably not want you want in production!
 */
object UseNewEntityMergePolicy : EntityMergePolicy {

    override fun determineEntities(
        suggestedEntitiesResolution: Resolutions<SuggestedEntityResolution>,
        schema: DataDictionary,
    ): Merges<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData> {
        return Merges(
            merges = suggestedEntitiesResolution.resolutions.map {
                when (it) {
                    is com.embabel.dice.common.NewEntity -> EntityMerge(
                        resolution = it,
                        convergenceTarget = it.recommended,
                    )

                    is com.embabel.dice.common.ExistingEntity -> EntityMerge(
                        resolution = it,
                        convergenceTarget = it.recommended,
                    )

                    is com.embabel.dice.common.ReferenceOnlyEntity -> EntityMerge(
                        resolution = it,
                        convergenceTarget = it.recommended, // Use existing, but won't be updated
                    )

                    is com.embabel.dice.common.VetoedEntity -> EntityMerge(
                        resolution = it,
                        convergenceTarget = null, // Vetoed entities have no target
                    )
                }
            }
        )
    }
}
