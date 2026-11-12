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
package com.embabel.dice.proposition.store

import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.proposition.PropositionStore

/**
 * The name this store had before it was renamed. It never depended on Neo4j, so the new name
 * says what it is: an adapter over embabel-agent's RAG entity repository. This class stays so
 * that Java code and code compiled against the old name keep working.
 */
@Deprecated(
    message = "Renamed to RagAdapterPropositionRepository.",
    replaceWith = ReplaceWith("RagAdapterPropositionRepository(crud, entityRepository)"),
)
class Neo4jRagPropositionRepository(
    crud: PropositionStore,
    entityRepository: NamedEntityDataRepository,
) : RagAdapterPropositionRepository(crud, entityRepository)
