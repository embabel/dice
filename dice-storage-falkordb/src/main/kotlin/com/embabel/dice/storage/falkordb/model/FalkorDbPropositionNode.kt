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
package com.embabel.dice.storage.falkordb.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction
import java.time.Instant

/**
 * A proposition as FalkorDB stores it: one node with typed fields that queries can filter on.
 *
 * This package uses nothing but Drivine and the standard library, so the KSP build can read it and
 * generate the query DSL. Conversion to and from `Proposition` lives in `FalkorDbPropositionMapper`.
 */
@NodeFragment(labels = ["DiceProposition"])
data class FalkorDbPropositionNode(
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
    @RangeIndex
    val status: String,
    @RangeIndex
    val level: Int,
    val reinforceCount: Int,
    @RangeIndex
    val pinned: Boolean,
    val grounding: List<String> = emptyList(),
    val sourceIds: List<String> = emptyList(),
    /** The resolved ids of the mentions, so entity filters can run in the database. */
    val entityIds: List<String> = emptyList(),
    // Mentions, evidence and metadata are stored as JSON text the mapper writes, because Drivine's
    // @JsonPacked reads every packed value back as a plain List: typed elements come back as maps and
    // a Map field fails to load. Nothing filters on these fields, so text loses nothing.
    val mentionsJson: String = "[]",
    val provenanceJson: String = "[]",
    /** Metadata values must be plain JSON: strings, numbers, booleans, lists and maps. */
    val metadataJson: String = "{}",
    /** The trust score from [metadata], when it is a finite number, so the trust filter can run in the database. */
    val trustScore: Double? = null,
    val uri: String? = null,
    val created: Instant,
    val contentRevised: Instant,
    val metadataRevised: Instant,
    /** The later of the two revision times, for "revised" filters and ordering. */
    val lastTouched: Instant,
    val lastAccessed: Instant,
    val observedAt: Instant? = null,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val invalidatedAt: Instant? = null,
    val supersedes: List<String> = emptyList(),
    val contradicts: List<String> = emptyList(),
    // FalkorDB holds an Instant as text and cannot do arithmetic on it, so the times that decay is
    // measured from are also kept as whole nanoseconds since the epoch. Whole numbers keep the day
    // boundaries exact. lastAccessed has its own column so touching a node never needs its other fields.
    val contentRevisedNanos: Long,
    val lastAccessedNanos: Long,
    val validFromNanos: Long? = null,
    /**
     * The embedding of [text]. The annotation is what makes Drivine store it as a FalkorDB vector;
     * searches scan it exactly, so the catalog leaves the approximate index out.
     */
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,
    /** The length of [embedding]. FalkorDB cannot measure a stored vector, and comparing two of different length fails the whole query. */
    val embeddingDimensions: Int? = null,
    @NodeStamp
    val stamp: String? = null,
)

/** Just the access time of a proposition node, so a touch can patch many nodes in one statement. */
@NodeFragment(labels = ["DiceProposition"])
data class PropositionAccess(
    @NodeId
    val id: String,
    val lastAccessed: Instant,
    val lastAccessedNanos: Long,
)
