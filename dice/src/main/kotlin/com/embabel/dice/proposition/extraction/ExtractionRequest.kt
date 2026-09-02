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

import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.jetbrains.annotations.ApiStatus

/**
 * What a caller wants to say about one extraction, on top of the text and the user it belongs to.
 *
 * Extraction keeps learning about new dimensions — where the material came from, which version of
 * it, which content policy it runs under — and each one would otherwise mean another argument on
 * [IncrementalPropositionExtraction.rememberText] and another overload to keep the old shape
 * callable. They travel here together, so the next dimension is a field on this type and the
 * entry-point signatures stay put. A host that overrides an entry point keeps compiling when one
 * is added, and sees the new value without touching its override.
 *
 * Everything is optional. An empty request — [NONE], or `ExtractionRequest()` — asks for the
 * extraction DICE has always done.
 *
 * A [sourceRevision] needs a [sourceLocator] whose key it matches, because a revision names one
 * version of one specific source. That pairing is checked while the request is being built, so a
 * caller finds out about a mismatch before extraction reads a byte. [profile] is checked against
 * nothing: it is independent of where the material came from, and coupling it to the other two
 * would invent a relationship the contract does not have.
 *
 * EXPERIMENTAL, for as long as [ExtractionContentProfileRef] is.
 *
 * @property sourceLocator where this run's material lives, when the caller has a typed source for
 *   it. The pipeline stamps it onto every proposition's provenance, so a caller who knows the real
 *   source gets richer grounding than the content-hash fallback.
 * @property sourceRevision the provider's own identifier for the version of [sourceLocator] this
 *   run reads. Supplying one asserts that the revision covers the whole text or the whole file
 *   being extracted; DICE reads material as one aggregate and cannot work that out for itself.
 * @property profile the host's content-profile identity for this extraction. DICE carries it and
 *   does nothing else with it: no provider, model, or credential is chosen from it, and extraction
 *   runs exactly as it would without one.
 */
@ApiStatus.Experimental
data class ExtractionRequest @JvmOverloads constructor(
    val sourceLocator: SourceLocator? = null,
    val sourceRevision: SourceRevisionRef? = null,
    val profile: ExtractionContentProfileRef? = null,
) {

    init {
        sourceRevision?.let { revision ->
            val locator = requireNotNull(sourceLocator) {
                "sourceLocator is required when sourceRevision is set"
            }
            require(revision.sourceKey == locator.key()) {
                "sourceRevision source key must match sourceLocator source key"
            }
        }
    }

    /**
     * True when this request carries nothing at all, so the call means what the same call meant
     * before requests existed. Comparing against [NONE] keeps this honest as fields are added.
     */
    val isEmpty: Boolean
        get() = this == NONE

    /**
     * Returns a copy grounded in the given source.
     */
    fun withSourceLocator(sourceLocator: SourceLocator): ExtractionRequest =
        copy(sourceLocator = sourceLocator)

    /**
     * Returns a copy carrying a revision of this request's source. Throws if there is no locator,
     * or if the revision names a different source key.
     */
    fun withSourceRevision(sourceRevision: SourceRevisionRef): ExtractionRequest =
        copy(sourceRevision = sourceRevision)

    /**
     * Returns a copy attributed to the given content [profile]. EXPERIMENTAL. Changes no other
     * field and no extraction behaviour.
     */
    fun withProfile(profile: ExtractionContentProfileRef): ExtractionRequest =
        copy(profile = profile)

    companion object {

        /**
         * The request that asks for nothing beyond plain extraction. Entry points use it as the
         * default, and a caller can build outwards from it with the `with...` helpers.
         */
        @JvmField
        val NONE: ExtractionRequest = ExtractionRequest()
    }
}
