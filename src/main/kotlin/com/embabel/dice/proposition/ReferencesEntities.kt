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
package com.embabel.dice.proposition

/**
 * Common interface for anything that references entities via mentions.
 * Propositions, projected relationships, and other constructs can
 * implement this to provide consistent entity access.
 */
interface ReferencesEntities {

    /**
     * Entity mentions within this construct.
     * Typically, includes SUBJECT and OBJECT roles for relationship-like structures.
     */
    val mentions: List<EntityMention>

    /**
     * Whether all entity mentions have been resolved to known entities.
     */
    fun isFullyResolved(): Boolean = mentions.all { it.resolvedId != null }

    /**
     * Get all resolved entity IDs from mentions.
     */
    fun resolvedEntityIds(): List<String> = mentions.mapNotNull { it.resolvedId }

    /**
     * Find the subject mention (if any).
     */
    fun subjectMention(): EntityMention? = mentions.find { it.role == MentionRole.SUBJECT }

    /**
     * Find the object mention (if any).
     */
    fun objectMention(): EntityMention? = mentions.find { it.role == MentionRole.OBJECT }

    /**
     * Get the resolved subject entity ID (if resolved).
     */
    fun subjectId(): String? = subjectMention()?.resolvedId

    /**
     * Get the resolved object entity ID (if resolved).
     */
    fun objectId(): String? = objectMention()?.resolvedId
}
