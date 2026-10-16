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
package com.embabel.dice.agent

import com.embabel.dice.proposition.PropositionKey

internal enum class MemoryTier { VECTOR, KEYWORD, RELATED }

internal data class CandidateEnvelope<T>(val key: PropositionKey, val value: T, val entityIds: Set<String>)

internal interface CandidateOperations<T> {
    fun preflight(tiers: Set<MemoryTier>)
    fun count(): Long
    fun list(limit: Int): List<CandidateEnvelope<T>>
    fun vector(query: String, limit: Int): List<CandidateEnvelope<T>>
    fun keyword(tokens: List<String>, limit: Int): List<CandidateEnvelope<T>>
    fun related(entityIds: Set<String>, limit: Int): List<CandidateEnvelope<T>>
}

internal data class MemorySelection<T>(val entries: List<CandidateEnvelope<T>>, val boundedPool: Boolean)
