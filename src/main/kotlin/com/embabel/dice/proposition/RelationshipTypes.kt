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
 * Relationship types for DICE graph schema.
 *
 * Graph schema:
 * ```
 * // Direct entity extraction (NER)
 * (Chunk)-[:HAS_ENTITY]->(__Entity__)
 *
 * // Proposition extraction
 * (Chunk)-[:HAS_PROPOSITION]->(Proposition)
 *
 * // Entity mentions in propositions
 * (Proposition)-[:MENTIONS {role: 'SUBJECT'}]->(__Entity__)
 * (Proposition)-[:MENTIONS {role: 'OBJECT'}]->(__Entity__)
 * ```
 *
 * @see com.embabel.agent.rag.model.NamedEntityData.HAS_ENTITY
 * @see MentionRole
 */
object RelationshipTypes {

    /**
     * Relationship from Chunk to Proposition.
     * Created when propositions are extracted from a chunk.
     * ```
     * (Chunk)-[:HAS_PROPOSITION]->(Proposition)
     * ```
     */
    const val HAS_PROPOSITION = "HAS_PROPOSITION"

    /**
     * Relationship from Proposition to Entity.
     * Use with [MentionRole] to indicate subject vs object.
     * ```
     * (Proposition)-[:MENTIONS {role: 'SUBJECT'}]->(__Entity__)
     * (Proposition)-[:MENTIONS {role: 'OBJECT'}]->(__Entity__)
     * ```
     */
    const val MENTIONS = "MENTIONS"

    /**
     * Property key for the role in a MENTIONS relationship.
     * Value should be a [MentionRole] name.
     */
    const val ROLE_PROPERTY = "role"
}
