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

/**
 * Relationship between a source and target entity,
 * identified by ID
 */
interface RelationshipInstance {
    val sourceId: String
    val targetId: String

    /**
     * The type of the relationship, e.g. "FALLS_UNDER"
     */
    val type: String
    val description: String?

    companion object {

        operator fun invoke(
            sourceId: String,
            targetId: String,
            type: String,
            description: String?,
        ): RelationshipInstance {
            return RelationshipInstanceImpl(sourceId, targetId, type, description)
        }
    }
}

private data class RelationshipInstanceImpl(
    override val sourceId: String,
    override val targetId: String,
    override val type: String,
    override val description: String? = null
) : RelationshipInstance
