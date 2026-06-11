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
package com.embabel.dice.query.graph

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.temporal.TemporalMetadata

/**
 * The assembled lineage of a single proposition — the "why" behind a stored fact.
 *
 * Every field is read directly from the proposition's own durable state; no separate revision
 * store is consulted or invented. This is the answer to "where did this come from and what is its
 * standing": its grounding in source material, the propositions it was abstracted from, how often
 * it has been reinforced, its current lifecycle status, and its temporal validity.
 *
 * @property proposition the proposition this lineage explains
 * @property provenanceEntries rich provenance entries linking the proposition to source material
 * @property groundingChunkIds the legacy chunk-id grounding (coarse source references)
 * @property sources the source propositions this one was abstracted from (empty if the store does
 *   not back abstraction-hierarchy traversal)
 * @property reinforceCount how many times the proposition has been reinforced
 * @property status the proposition's lifecycle status (e.g. superseded or contradicted, which is
 *   precisely the kind of standing a lineage is expected to surface)
 * @property temporal the proposition's temporal validity metadata, if any
 */
data class PropositionLineage(
    val proposition: Proposition,
    val provenanceEntries: List<ProvenanceEntry>,
    val groundingChunkIds: List<String>,
    val sources: List<Proposition>,
    val reinforceCount: Int,
    val status: PropositionStatus,
    val temporal: TemporalMetadata?,
)
