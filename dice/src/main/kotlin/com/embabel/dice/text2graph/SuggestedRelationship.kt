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
package com.embabel.dice.text2graph

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.util.loggerFor
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntityResolution

data class SuggestedRelationship(
    override val sourceId: String,
    override val targetId: String,
    override val type: String,
    override val description: String? = null,
) : RelationshipInstance {

    fun isValid(
        schema: DataDictionary,
        sourceEntity: NamedEntityData,
        targetEntity: NamedEntityData,
    ): Boolean {
//        val valid =
//            schema.allowedRelationships().any {
//                it.name == type && it.from.labels.intersect(sourceEntity.labels())
//                    .isNotEmpty() && it.to.labels.intersect(
//                    targetEntity.labels()
//                ).isNotEmpty()
//            }
        val from = schema.domainTypeForLabels(sourceEntity.labels())
        val to = schema.domainTypeForLabels(targetEntity.labels())
        if (from == null || to == null) {
            loggerFor<DataDictionary>().info(
                "Relationship {} between {} and {} is invalid",
                type,
                sourceEntity.infoString(verbose = false),
                targetEntity.infoString(verbose = false),
            )
            return false
        } else {
            // TODO fix this
            println("Check relationship $type between $from and $to")
        }
        return true
    }
}


data class SuggestedRelationships(
    val entitiesResolution: Resolutions<SuggestedEntityResolution>,
    val suggestedRelationships: List<SuggestedRelationship>,
)
