package com.embabel.dice.proposition

import com.embabel.common.core.types.ZeroToOne

/**
 * Represents something derived from sources with associated uncertainty.
 *
 * A Derivation captures the epistemic properties of derived knowledge:
 * - **Confidence**: How certain we are (0.0 = uncertain, 1.0 = certain)
 * - **Decay**: How quickly it becomes stale (0.0 = eternal, 1.0 = ephemeral)
 * - **Grounding**: What sources support this derivation
 *
 * Both [Proposition]s (raw extracted knowledge) and [Projected] items
 * (derived representations) implement this interface, enabling uniform
 * handling of uncertain, traceable knowledge.
 *
 * ## Usage
 *
 * ```kotlin
 * fun processDerivation(d: Derivation) {
 *     if (d.confidence > 0.8 && d.decay < 0.3) {
 *         // High confidence, low decay - reliable long-term knowledge
 *         storeInLongTermMemory(d)
 *     }
 *     // Trace back to sources
 *     d.grounding.forEach { sourceId ->
 *         log("Derived from: $sourceId")
 *     }
 * }
 * ```
 */
interface Derivation {

    /**
     * Confidence in this derivation (0.0 to 1.0).
     *
     * - 1.0: Completely certain
     * - 0.5: Uncertain, could go either way
     * - 0.0: No confidence (effectively unknown)
     */
    val confidence: ZeroToOne

    /**
     * Decay rate for this derivation (0.0 to 1.0).
     *
     * - 0.0: Eternal truth, never becomes stale
     * - 0.5: Moderate decay, refresh periodically
     * - 1.0: Highly ephemeral, stale almost immediately
     */
    val decay: ZeroToOne

    /**
     * Source IDs that ground this derivation.
     *
     * For propositions, this is chunk IDs from the original documents.
     * For projections, this is proposition IDs.
     * Enables full provenance tracing back to original sources.
     */
    val grounding: List<String>
}
