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
package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.proposition.Proposition

/**
 * Keeps only the entities some proposition's mention resolves to. Input order is kept.
 *
 * @param propositions The propositions whose resolved mentions decide what is kept.
 * @param entities The candidate entities.
 * @return The entities whose id a mention of [propositions] resolves to, in the order of [entities].
 */
internal fun referencedEntities(
    propositions: Collection<Proposition>,
    entities: Collection<NamedEntityData>,
): List<NamedEntityData> =
    propositions
        .flatMap { it.mentions }
        .mapNotNull { it.resolvedId }
        .toSet()
        .let { referencedIds -> entities.filter { it.id in referencedIds } }
