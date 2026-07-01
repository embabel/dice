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

import org.drivine.annotation.Default
import org.drivine.annotation.EmptyWhenAbsent
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
 * ## Schema evolution: keep new fields loadable against old nodes
 *
 * Drivine hydrates a node by projecting each field explicitly, so a property a node was written
 * without comes back as *present-null*, not absent — and a plain Kotlin default only applies for an
 * *absent* property, so it never fires here. A non-nullable field with no null handling therefore
 * fails to load any node predating that field.
 *
 * So every field that might be missing on an older node must be either nullable, or carry a
 * `@Default` (scalars — falls back to the declared Kotlin default) or `@EmptyWhenAbsent`
 * (collections). Only the fields present since the node's first version — `id`, `contextId`,
 * `text`, `confidence`, `created` — are left strict; a node missing one of those is genuinely
 * corrupt and should fail loudly. Apply the same rule to any field added from here on.
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
    @param:Default
    val decay: Double = 0.0,
    @param:Default
    val importance: Double = 0.5,
    val reasoning: String? = null,

    @param:EmptyWhenAbsent
    val grounding: List<String> = emptyList(),
    @param:EmptyWhenAbsent
    val sourceIds: List<String> = emptyList(),
    val uri: String? = null,

    val created: Instant,
    @param:Default
    val contentRevised: Instant = created,
    @param:Default
    val metadataRevised: Instant = created,

    /**
     * The later of [contentRevised] and [metadataRevised] — "last touched of any kind". Materialised
     * (not derived at query time) so "revised" filtering and ordering push into the database against a
     * single indexable column, matching the in-memory backend's `Proposition.lastTouched` semantics.
     * Written on every save; the partial-update writers (decay sweep, re-embed) don't touch the
     * revision clocks, so it never drifts. On a node written before this column existed it falls back
     * to the later of the two revision clocks.
     */
    @RangeIndex
    @param:Default
    val lastTouched: Instant = maxOf(contentRevised, metadataRevised),

    @param:Default
    val lastAccessed: Instant = created,

    /** [com.embabel.dice.proposition.PropositionStatus] name. */
    @RangeIndex
    @param:Default
    val status: String = "ACTIVE",

    /** Pinned propositions are exempt from decay/forgetting (lifecycle, PR #30). */
    @RangeIndex
    @param:Default
    val pinned: Boolean = false,

    /** Abstraction level: 0 = raw observation, 1+ = derived. Persisted (unlike the legacy store). */
    @RangeIndex
    @param:Default
    val level: Int = 0,

    /** Merge/reinforcement count. Persisted (unlike the legacy store). */
    @param:Default
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
    @param:EmptyWhenAbsent
    val supersedes: List<String> = emptyList(),
    @param:EmptyWhenAbsent
    val contradicts: List<String> = emptyList(),

    /**
     * Open metadata bag, stored as flat `metadata.<key>` node properties (queryable, unlike a JSON
     * blob). Values must be Neo4j-storable primitives/arrays; non-storable values throw at save.
     * Ints read back as Longs (Drivine's documented property-bag type asymmetry).
     */
    @PropertyBag
    val metadata: Map<String, Any?> = emptyMap(),
)
