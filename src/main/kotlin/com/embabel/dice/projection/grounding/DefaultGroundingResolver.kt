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
package com.embabel.dice.projection.grounding

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import org.slf4j.LoggerFactory

/**
 * Standard repository-backed [GroundingResolver]. Resolution is:
 *
 *  1. **Exact** — `findById(groundingId)`. The id the caller passed is
 *     the node id verbatim. Most groundings hit here.
 *  2. **Namespace suffix** — on a miss, match any entity whose id ends
 *     with the grounding id's trailing segment (everything after the
 *     first `:`). This bridges `email:<hash>` grounding to the stored
 *     `email:<user>:<hash>` node, mirroring how the source-text readers
 *     have always matched (`n.id ENDS WITH <suffix>`).
 *
 * A grounding id with no `:` (a bare chunk hash / free-text fingerprint)
 * only ever matches exactly — never by suffix — so legacy chunk ids stay
 * unresolved (and edge-free), exactly as before.
 */
class DefaultGroundingResolver(
    private val repository: NamedEntityDataRepository,
    private val entityLabel: String = ENTITY_LABEL,
) : GroundingResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun resolveAll(groundingId: String): List<NamedEntityData> {
        if (groundingId.isBlank()) return emptyList()
        resolveExact(groundingId)?.let { return listOf(it) }
        val suffix = groundingId.substringAfter(':', "")
        if (suffix.isBlank() || suffix == groundingId) return emptyList()
        return try {
            repository.find(entityLabel, PropertyFilter.endsWith("id", suffix))
        } catch (e: Exception) {
            logger.debug("[grounding-resolver] suffix lookup for '{}' (suffix '{}') failed: {}", groundingId, suffix, e.message)
            emptyList()
        }
    }

    override fun resolve(groundingId: String): NamedEntityData? {
        if (groundingId.isBlank()) return null
        resolveExact(groundingId)?.let { return it }
        return resolveAll(groundingId).singleOrNull()
    }

    private fun resolveExact(groundingId: String): NamedEntityData? =
        try {
            repository.findById(groundingId)
        } catch (e: Exception) {
            logger.debug("[grounding-resolver] findById('{}') threw: {}", groundingId, e.message)
            null
        }

    companion object {
        /** Universal label every persisted entity carries; scopes the suffix scan. */
        const val ENTITY_LABEL: String = "__Entity__"
    }
}
