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

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * The full proposition view: mentions **and** provenance (`DERIVED_FROM` → shared [SourceNode]).
 *
 * A second view over the same `:Proposition` node, distinct from the lean [PropositionView] (root +
 * mentions). Provenance is heavy and rarely needed on bulk/query/vector paths, so this view is used
 * only where completeness matters — `save` (persist everything) and `findById` (the canonical full
 * fetch).
 */
@GraphView
data class PropositionWithProvenanceView(
    @Root
    val proposition: PropositionNode,

    @GraphRelationship(type = "HAS_MENTION", direction = Direction.OUTGOING)
    val mentions: List<Mention> = emptyList(),

    @GraphRelationship(type = "DERIVED_FROM", direction = Direction.OUTGOING)
    val provenance: List<DerivedFrom> = emptyList(),
)
