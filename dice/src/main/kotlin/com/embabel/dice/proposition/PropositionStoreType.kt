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
 * Which backend provides a [PropositionRepository], for selecting/flipping implementations.
 *
 * Mirrors the chat-store `ConversationStoreType` convention. [STORED] is a persistent backend
 * (currently the Drivine graph store); if a non-graph persistent backend (e.g. SQL) is added later,
 * a finer storage-type axis can sit beneath [STORED].
 */
enum class PropositionStoreType {
    /** Ephemeral, in-process. */
    IN_MEMORY,

    /** Persistent (graph-backed today). */
    STORED,
}
