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

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction
import java.time.Instant

/**
 * Drivine graph projection of a dice `Proposition`. The dice `Proposition` is the system of
 * record; this node is a flat persistence view of it.
 *
 * Deliberately decoupled from dice-core types (enums stored as their `name` string, temporal
 * metadata flattened to scalar instants) so the Drivine KSP code generator can process this
 * package with only Drivine + the Kotlin/JDK stdlib on its classpath. Conversion to/from the rich
 * dice `Proposition` lives in `PropositionGraphMapper`, which the Maven build compiles with
 * dice-core available.
 *
 * @see com.embabel.dice.storage.PropositionGraphMapper
 */
@NodeFragment(labels = ["Proposition"])
data class PropositionNode(
    @NodeId
    @Unique
    val id: String,

    @RangeIndex
    val contextId: String,

    val text: String,

    val confidence: Double,
    val decay: Double,
    val importance: Double,
    val reasoning: String? = null,

    val grounding: List<String> = emptyList(),
    val sourceIds: List<String> = emptyList(),
    val uri: String? = null,

    val created: Instant,
    // Proposition splits revision into content vs metadata revisions (lifecycle work, PR #30).
    val contentRevised: Instant,
    val metadataRevised: Instant,
    val lastAccessed: Instant,

    /** [com.embabel.dice.proposition.PropositionStatus] name. */
    @RangeIndex
    val status: String,

    /** Pinned propositions are exempt from decay/forgetting (lifecycle, PR #30). */
    @RangeIndex
    val pinned: Boolean = false,

    /** Abstraction level: 0 = raw observation, 1+ = derived. Persisted (unlike the legacy store). */
    @RangeIndex
    val level: Int = 0,

    /** Merge/reinforcement count. Persisted (unlike the legacy store). */
    val reinforceCount: Int = 0,

    /** Embedding of [text]; the vector index this annotation declares is also what `loadNearest` infers. */
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,

    // --- Decay materialization (written by the periodic sweep, read by ranking queries) ---

    /**
     * Effective confidence as of the last sweep, with decay pre-applied. Lets ranking/filtering
     * (`OrderBy.EFFECTIVE_CONFIDENCE_DESC`, `minEffectiveConfidence`) push into the database
     * instead of loading the whole store and decaying in memory.
     */
    @RangeIndex
    val effectiveConfidence: Double? = null,

    /** When [effectiveConfidence] was last recomputed. */
    val decayUpdatedAt: Instant? = null,

    // --- Flattened temporal metadata. validFrom/validTo/invalidatedAt drive the decay sweep's
    //     CASE; observedAt/supersedes/contradicts are carried so TemporalMetadata round-trips fully.
    //     (see PropositionGraphMapper) ---

    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val invalidatedAt: Instant? = null,
    val observedAt: Instant? = null,
    val supersedes: List<String> = emptyList(),
    val contradicts: List<String> = emptyList(),

    /**
     * Open metadata bag, stored as flat `metadata.<key>` node properties (queryable, unlike a JSON
     * blob). Values must be Neo4j-storable primitives/arrays; non-storable values throw at save.
     * Ints read back as Longs (Drivine's documented property-bag type asymmetry).
     */
    @PropertyBag
    val metadata: Map<String, Any?> = emptyMap(),
)
