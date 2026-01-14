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
package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntity

/**
 * A known entity with additional context for this extraction.
 * Delegates to the wrapped [NamedEntity].
 *
 * @param entity The named entity
 * @param role The role this entity plays in the current analysis,
 *        e.g., "The user in the conversation", "A referenced entity".
 */
data class KnownEntity(
    val entity: NamedEntity,
    val role: String,
) : NamedEntity by entity {

    companion object {
        /**
         * Create a KnownEntity marking this as the current user.
         */
        @JvmStatic
        fun asCurrentUser(entity: NamedEntity): KnownEntity =
            KnownEntity(entity, role = "The user in the conversation")


        @JvmStatic
        fun of(entity: NamedEntity): RoleStep =
            RoleStep(entity)
    }

    /**
     * Builder step requiring the role.
     */
    class RoleStep(private val entity: NamedEntity) {
        /**
         * Specify the role for this known entity and build it.
         */
        fun withRole(role: String): KnownEntity =
            KnownEntity(entity, role)
    }
}
