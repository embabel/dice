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
package com.embabel.dice.storage.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique

/**
 * Drivine graph projection of a dice `EntityMention`: an entity reference within a proposition.
 *
 * `role` is stored as the [com.embabel.dice.proposition.MentionRole] name string to keep this
 * package free of dice-core types (see [PropositionNode]). `resolvedId` is range-indexed because
 * entity filters (`mentions.any { resolvedId eq ... }`) query it.
 */
@NodeFragment(labels = ["Mention"])
data class Mention(
    @NodeId
    @Unique
    val id: String,

    @RangeIndex
    val resolvedId: String? = null,

    val span: String,
    val type: String,

    /** [com.embabel.dice.proposition.MentionRole] name (SUBJECT / OBJECT / OTHER). */
    val role: String,
)
