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

import com.embabel.dice.proposition.Proposition
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty
import org.springframework.boot.context.properties.NestedConfigurationProperty

/**
 * Configuration for the Dice proposition store.
 *
 * With `dice-starter-neo4j` on the classpath the backend needs no setting: DICE uses its Drivine/Neo4j
 * stores when Drivine is configured and its in-memory stores otherwise. [type] is the old switch, kept
 * so existing configuration still works.
 */
@ConfigurationProperties(prefix = "embabel.dice.store")
data class DiceStoreProperties(
    /**
     * Deprecated: the old switch between DICE's in-memory stores and its Drivine ones.
     *
     * The default reads `in-memory`, and without `dice-starter-neo4j` that is what an app that leaves
     * it alone gets. With the starter on the classpath and the property unset, DICE picks the backend
     * from whether Drivine is configured instead. DICE asks the environment whether the property was
     * set, not this field, so the default here never turns detection off.
     *
     * A set value is still respected. `in-memory` forces the in-memory stores even when Drivine is
     * configured, and `graph` forces the Drivine stores. Any other value gets the in-memory stores and
     * a warning, as it always did. Setting it logs a deprecation warning.
     */
    @get:DeprecatedConfigurationProperty(
        reason = "DICE picks its stores from whether Drivine is configured",
        since = "0.5.0",
    )
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
        /** Decay-rate multiplier `k` for the staleness policy. Defaults to the same value Proposition uses. */
        var k: Double = Proposition.DEFAULT_DECAY_K,
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
