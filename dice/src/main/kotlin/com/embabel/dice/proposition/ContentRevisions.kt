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
package com.embabel.dice.proposition

import com.embabel.dice.common.DiceMetadataKeys
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.TemporalAccessor

/**
 * Reads and writes the content-revision record, the [DiceMetadataKeys.CONTENT_REVISED] entry in a
 * proposition's metadata. The entry holds the revision time as ISO-8601 text, which every store
 * hands back unchanged.
 */
internal object ContentRevisions {

    /**
     * Reads the recorded revision time.
     *
     * @param metadata the proposition's metadata
     * @return the time held by the entry, or null when there is no entry or its value is not a time
     */
    @JvmSynthetic
    fun recordedAt(metadata: Map<String, Any>): Instant? =
        when (val value = metadata[DiceMetadataKeys.CONTENT_REVISED]) {
            is Instant -> value
            is TemporalAccessor -> try {
                Instant.from(value)
            } catch (e: DateTimeException) {
                null
            }
            is String -> try {
                Instant.parse(value.trim())
            } catch (e: DateTimeException) {
                null
            }
            else -> null
        }

    /**
     * Records a content revision.
     *
     * @param proposition the revised proposition
     * @param at when the content changed
     * @return a copy with `contentRevised` set to [at] and the entry holding [at]
     */
    @JvmSynthetic
    fun record(proposition: Proposition, at: Instant): Proposition =
        proposition.copy(
            contentRevised = at,
            metadata = proposition.metadata + (DiceMetadataKeys.CONTENT_REVISED to at.toString()),
        )

    /**
     * Copies one proposition's record, exactly as stored, onto another.
     *
     * @param from the proposition whose record is copied
     * @param to the proposition that receives it
     * @return [to] with the entry [from] holds, or [to] unchanged when [from] has no entry
     */
    @JvmSynthetic
    fun carryRecord(from: Proposition, to: Proposition): Proposition {
        val value = from.metadata[DiceMetadataKeys.CONTENT_REVISED] ?: return to
        return to.copy(metadata = to.metadata + (DiceMetadataKeys.CONTENT_REVISED to value))
    }

    /**
     * Drops the record, for metadata copied into a new claim.
     *
     * @param metadata the metadata to copy
     * @return [metadata] without the entry
     */
    @JvmSynthetic
    fun withoutRecord(metadata: Map<String, Any>): Map<String, Any> =
        metadata - DiceMetadataKeys.CONTENT_REVISED
}
