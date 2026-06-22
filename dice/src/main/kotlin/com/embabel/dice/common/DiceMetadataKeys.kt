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
package com.embabel.dice.common

/**
 * Well-known metadata keys used by DICE for values cached on a proposition's metadata map.
 *
 * Keys follow the `dice.<area>.<name>` convention so that DICE-owned metadata never
 * collides with consumer-supplied keys.
 */
object DiceMetadataKeys {

    /**
     * Cached trust score for a proposition — a `Double` in `[0.0, 1.0]` produced by a `TrustScorer`.
     *
     * It's a cache: a content revision (`withText` / `withConfidence`) re-anchors the decay clock but
     * does not clear this key, so the score can briefly outlive the content it was computed for. That's
     * self-healing — the reviser re-scores on the next merge/reinforce — and trust is advisory, so a
     * momentarily stale value is harmless rather than wrong.
     */
    const val TRUST_SCORE = "dice.trust.score"

    /**
     * Content hash of the schema active at extraction time.
     *
     * Stamping a proposition with this key lets drift detection later identify which
     * propositions were extracted under an older schema version.
     */
    const val METAMODEL_VERSION = "dice.metamodel.version"

    /**
     * Human-readable reason a proposition was quarantined due to schema drift.
     *
     * The presence of this key (alongside `STALE` status) means the proposition was
     * quarantined by a drift policy rather than ordinary confidence decay.
     */
    const val QUARANTINE_REASON = "dice.metamodel.quarantine.reason"
}
