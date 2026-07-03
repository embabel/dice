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

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Configuration for the multi-signal duplicate collector.
 *
 * `embabel.dice.collector.enabled` is the master switch (on by default). Each signal — the
 * built-in pair sources and scorers `CollectorAutoConfiguration` wires up — gets its own entry
 * under [signals], keyed by its short name (`vector`, `lexical`, `entity-overlap`,
 * `grounding-overlap`, `provenance-overlap`, `polarity-veto`), so ops can disable one or tune its
 * weight without touching code.
 */
@ConfigurationProperties(prefix = "embabel.dice.collector")
@Validated
data class CollectorProperties(
    /** Master switch for the whole collector. */
    val enabled: Boolean = true,

    /** Minimum aggregate score for an edge to be eligible to merge its endpoints. */
    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    val matchThreshold: Double = 0.6,

    /** Per-signal overrides, keyed by short signal name (see [SignalProperties]). */
    val signals: Map<String, SignalProperties> = emptyMap(),

    /** The periodic delta-sweep. Property binding only for now — no runner behind it yet. */
    val sweep: SweepProperties = SweepProperties(),

    /** The collector's inspectable decision trace. */
    val trace: TraceProperties = TraceProperties(),
) {
    /**
     * One signal's override. [weight], [similarityThreshold] and [topK] are nullable so "not set"
     * is distinguishable from "set to the scorer's own default" — a null weight falls back to the
     * scorer's own neutral default instead of forcing a specific number.
     */
    data class SignalProperties(
        /** Whether this signal participates at all. */
        val enabled: Boolean = true,

        /** Contribution weight in the blend; null keeps the scorer's own default. */
        @field:DecimalMin("0.0")
        val weight: Double? = null,

        /** Cosine floor for candidate recall; vector signal only. */
        val similarityThreshold: Double? = null,

        /** Max similar members considered per cluster seed; vector signal only. */
        val topK: Int? = null,
    )

    /** The periodic delta-sweep. Just the property, no runner wired up yet. */
    data class SweepProperties(
        val delta: Boolean = false,
    )

    /** The collector's inspectable decision trace (edges, components, decisions). */
    data class TraceProperties(
        /** Whether runs are recorded to a [com.embabel.dice.spi.CollectorTraceStore] at all. */
        val enabled: Boolean = true,

        /** How long detailed trace rows are kept, in days; null means keep indefinitely. */
        val detailRetentionDays: Int? = null,
    )
}
