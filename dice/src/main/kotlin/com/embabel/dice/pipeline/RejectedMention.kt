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
package com.embabel.dice.pipeline

import com.embabel.dice.proposition.SuggestedMention

/** What the pipeline does with a mention that one of its checks rejected. */
enum class RejectedMentionHandling {
    /** Reported only. The mention is resolved like any other and stays on its proposition. */
    KEEP,

    /** Not offered to the entity resolver. The mention stays on its proposition. */
    LEAVE_UNRESOLVED,

    /** Not offered to the entity resolver and taken off its proposition. */
    DROP,
}

/**
 * A mention one of the pipeline's checks rejected, and what the pipeline did with it.
 *
 * The pipeline builds these and a caller reads them from the result it returns.
 *
 * @property chunkId the id of the chunk the proposition came from
 * @property propositionIndex the position of the proposition in the list the extractor returned for that chunk
 * @property propositionText the text of that proposition
 * @property mention the mention as the model suggested it
 * @property reason the reason the check gave
 * @property check which check rejected the mention
 * @property handling what the pipeline did with the mention for that check
 */
data class RejectedMention(
    val chunkId: String,
    val propositionIndex: Int,
    val propositionText: String,
    val mention: SuggestedMention,
    val reason: String,
    val check: Check,
    val handling: RejectedMentionHandling,
) {
    /** Which of the pipeline's two checks rejected the mention. */
    enum class Check { GROUNDING, FILTER }
}
