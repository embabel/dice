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
 * What a [PropositionRepository.reembedAll] did.
 *
 * A count alone could not say whether the vector index had to be rebuilt, which is the one part a
 * caller wants in its log: re-embedding ten thousand propositions at the same width is routine,
 * and doing it because the model's shape changed underneath the index is an event.
 *
 * @param propositions how many propositions were re-embedded
 * @param indexRecreated whether this backend's vector index was dropped and remade to match the
 * model's current shape. False for a same-shape re-embed, and for any backend that owns no index.
 */
data class PropositionReembedReport(
    val propositions: Int,
    val indexRecreated: Boolean,
)
