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
package com.embabel.dice.proposition.extraction

import org.jetbrains.annotations.ApiStatus

/**
 * How much of a run someone could set up again from what the run recorded.
 *
 * There is no value here that means "run this again and get the same output", and there will not
 * be one. A hosted model can change weights, quantization, routing, safety filtering, and system
 * instructions under a stable model name, and none of that is visible to DICE. Temperature zero
 * narrows the distribution and does not remove batching and floating-point nondeterminism. So the
 * strongest value the model offers is [APPROXIMATE], and it is named for what it is.
 *
 * The field says what the *record* supports. It is not a promise about the provider, and a host
 * replay policy — how many attempts, against which provider, with what tolerance — stays the
 * host's.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionReplayFidelity {

    /**
     * The run recorded nothing that would help set it up again. It can be counted and attributed;
     * it cannot be re-run in any recognisable form.
     */
    NONE,

    /**
     * Identities and fingerprints only. You can tell which prompt template, schema, metamodel and
     * profile version were in play, and compare two runs by those, without being able to
     * reconstruct the input to either.
     */
    METADATA,

    /**
     * Identities, fingerprints, and the requested model configuration. A host can stand up a
     * similar run against the same declared model and settings. The output will differ, sometimes
     * materially, and any comparison between the two is a comparison of two runs rather than a
     * verification of one.
     */
    APPROXIMATE,
    ;

    companion object {

        /**
         * The strongest fidelity DICE records, which is still approximate. Call this rather than
         * taking the last enum entry, so the honesty of the claim survives someone appending a
         * value.
         */
        @JvmStatic
        fun strongest(): ExtractionReplayFidelity = APPROXIMATE
    }
}
