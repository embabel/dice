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
package com.embabel.dice.storage.autoconfigure

import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.storage.DrivinePropositionRepository
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

/**
 * Configuration for the Dice proposition store.
 *
 * `embabel.dice.store.type` selects the backend: `graph` (Drivine/Neo4j) or `in-memory` (default).
 */
@ConfigurationProperties(prefix = "embabel.dice.store")
data class DiceStoreProperties(
    /** Backend kind: `graph` or `in-memory`. */
    var type: String = "in-memory",

    @NestedConfigurationProperty
    var decay: Decay = Decay(),

    @NestedConfigurationProperty
    var vectorIndex: VectorIndex = VectorIndex(),

    @NestedConfigurationProperty
    var exactVector: ExactVector = ExactVector(),

    @NestedConfigurationProperty
    var scopedRead: ScopedRead = ScopedRead(),
) {
    /**
     * How far one filtered exact vector search may go before it gives up: the most eligible
     * propositions it will score, and how long it may run. The graph backend honours both, though
     * only a search it runs natively in Neo4j is held to the timeout. The in-memory store ignores
     * both. A value that is not positive stops the graph store from starting.
     */
    data class ExactVector(
        var maxCandidates: Int = ExactVectorBudget.DEFAULT_MAX_CANDIDATES,
        var timeout: Duration = ExactVectorBudget.DEFAULT_TIMEOUT,
    ) {
        fun toBudget(): ExactVectorBudget = ExactVectorBudget(maxCandidates, timeout)
    }

    /**
     * How long a scoped query, count or keyword read may run on the graph backend before the
     * database stops it. Vector and strict policy reads keep their own deadlines, and the in-memory
     * store ignores it. A value that is not positive stops the graph store from starting.
     */
    data class ScopedRead(
        var timeout: Duration = DrivinePropositionRepository.DEFAULT_SCOPED_READ_TIMEOUT,
    )

    /** The scheduled decay tick (materialise cached confidence, then apply lifecycle transitions). */
    data class Decay(
        var enabled: Boolean = true,
        /** Delay between ticks, in milliseconds. Default 1 hour. */
        var intervalMs: Long = 3_600_000,
        /** Decay-rate multiplier `k` for the staleness policy. */
        var k: Double = 2.0,
        /** Hard-delete STALE propositions during the lifecycle sweep. */
        var pruneStale: Boolean = false,
    )

    /**
     * The proposition embedding vector index (graph backend). Only [enabled] is configurable: its
     * label, property, name, and similarity are fixed by the `@VectorIndex` annotation on
     * `PropositionNode.embedding` and live as constants on `DrivinePropositionRepository`.
     */
    data class VectorIndex(
        var enabled: Boolean = true,
    )
}
