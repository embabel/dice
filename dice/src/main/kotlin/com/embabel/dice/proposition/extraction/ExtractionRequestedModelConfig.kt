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
import java.time.Duration

/**
 * What the run *asked* for.
 *
 * Everything here was decided before any call went out. Nothing here is evidence that a provider
 * honoured it: a service can clamp a temperature, ignore a top-k, silently route to a different
 * checkpoint, or cap max tokens below what was asked. What actually came back is
 * [ExtractionProviderResponseFacts], on the invocation record, and the two are separate types so
 * that no field can hold both meanings and no mapper can drift one into the other. They share no
 * property name, which is why the requested model is [requestedModel] and the reported one is
 * `responseModel`.
 *
 * **Portable fields only.** Every field here means the same thing across providers. There is no
 * extension object, no provider settings blob, and no free map, because that is where credentials,
 * system prompts, and whole SDK request bodies get persisted by accident. A provider-specific
 * knob that matters to a host is folded into [selectionFingerprint] or [thinkingFingerprint] —
 * an opaque digest DICE compares and never reads.
 *
 * Ranges are checked where every provider agrees and left open where they do not. Temperature has
 * no upper bound here because services differ on whether it stops at 1 or 2; the penalties are
 * only required to be real numbers for the same reason.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property modelRole The host's name for the job this model was doing, such as `extraction`
 * @property requestedModel The model name the host asked for
 * @property temperature Requested sampling temperature
 * @property topP Requested nucleus-sampling mass, between 0 and 1
 * @property topK Requested top-k cutoff
 * @property maxTokens Requested output-token ceiling
 * @property presencePenalty Requested presence penalty
 * @property frequencyPenalty Requested frequency penalty
 * @property thinkingFingerprint Opaque digest of the reasoning or thinking configuration
 * @property selectionFingerprint Opaque digest of how the host chose this model and these settings
 * @property timeout How long the caller was prepared to wait
 */
@ApiStatus.Experimental
data class ExtractionRequestedModelConfig @JvmOverloads constructor(
    val modelRole: String? = null,
    val requestedModel: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val thinkingFingerprint: String? = null,
    val selectionFingerprint: String? = null,
    val timeout: Duration? = null,
) {

    init {
        requireBoundedIdentifier(modelRole, "modelRole")
        requireBoundedIdentifier(requestedModel, "requestedModel")
        requireBoundedIdentifier(thinkingFingerprint, "thinkingFingerprint")
        requireBoundedIdentifier(selectionFingerprint, "selectionFingerprint")
        requireAtLeast(temperature, "temperature", 0.0)
        requireInRange(topP, "topP", 0.0, 1.0)
        requireFinite(presencePenalty, "presencePenalty")
        requireFinite(frequencyPenalty, "frequencyPenalty")
        if (topK != null) require(topK >= 1) { "topK must be at least 1, was $topK" }
        if (maxTokens != null) require(maxTokens >= 1) { "maxTokens must be at least 1, was $maxTokens" }
        if (timeout != null) {
            require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive, was $timeout" }
        }
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            modelRole: String? = null,
            requestedModel: String? = null,
            temperature: Double? = null,
            topP: Double? = null,
            topK: Int? = null,
            maxTokens: Int? = null,
            presencePenalty: Double? = null,
            frequencyPenalty: Double? = null,
            thinkingFingerprint: String? = null,
            selectionFingerprint: String? = null,
            timeout: Duration? = null,
        ): ExtractionRequestedModelConfig = ExtractionRequestedModelConfig(
            modelRole = modelRole,
            requestedModel = requestedModel,
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxTokens = maxTokens,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            thinkingFingerprint = thinkingFingerprint,
            selectionFingerprint = selectionFingerprint,
            timeout = timeout,
        )
    }
}
