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
package com.embabel.dice.provenance

/**
 * Links a proposition to the source material that grounds it.
 *
 * Each entry carries a [SourceLocator] identifying where the material lives,
 * together with optional detail locating the content within that source: the
 * originating chunk, a character offset range, and a content hash. This carries
 * more detail than a bare list of chunk IDs.
 *
 * All detail beyond the [locator] is optional, so coarse grounding (the source
 * document) and precise grounding (a character range within a chunk) share a
 * single representation.
 *
 * @property locator Reference to where the source material lives
 * @property chunkId Optional ID of the chunk this grounding came from
 * @property startOffset Optional inclusive start character offset within the source/chunk
 * @property endOffset Optional exclusive end character offset within the source/chunk
 * @property contentHash Optional hash of the grounded content. Comparing it against the
 *   source later can reveal that the source has since changed.
 */
data class GroundingEntry @JvmOverloads constructor(
    val locator: SourceLocator,
    val chunkId: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val contentHash: String? = null,
) {

    init {
        require(startOffset == null || startOffset >= 0) { "startOffset must be non-negative" }
        require(endOffset == null || endOffset >= 0) { "endOffset must be non-negative" }
        require(
            startOffset == null || endOffset == null || endOffset >= startOffset
        ) { "endOffset must be >= startOffset" }
    }
}
