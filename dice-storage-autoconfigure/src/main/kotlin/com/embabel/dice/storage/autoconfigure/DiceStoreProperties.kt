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
) {
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

    /** The proposition embedding vector index (graph backend). */
    data class VectorIndex(
        var enabled: Boolean = true,
        var label: String = "Proposition",
        var property: String = "embedding",
        var similarityFunction: String = "cosine",
        var name: String = "",
    )
}
