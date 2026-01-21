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
package com.embabel.dice.common.filter

import com.embabel.dice.proposition.SuggestedMention
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag

/**
 * Decorator that adds observability to any MentionFilter.
 *
 * Tracks filtered vs. valid mentions by entity type using Micrometer metrics.
 * This allows monitoring of mention quality in production environments.
 *
 * Example usage:
 * ```kotlin
 * val baseFilter = SchemaValidatedMentionFilter(dataDictionary)
 * val observableFilter = ObservableMentionFilter(baseFilter, meterRegistry)
 * ```
 *
 * Exposed metrics:
 * - `dice.mentions.validated{entity.type=Company,valid=true}` - Valid company mentions
 * - `dice.mentions.validated{entity.type=Company,valid=false}` - Filtered company mentions
 * - etc. for each entity type
 *
 * @property delegate The underlying filter to wrap
 * @property meterRegistry Micrometer registry for recording metrics
 */
class ObservableMentionFilter(
    private val delegate: MentionFilter,
    private val meterRegistry: MeterRegistry
) : MentionFilter {

    override fun isValid(mention: SuggestedMention, propositionText: String): Boolean {
        val valid = delegate.isValid(mention, propositionText)

        val tags = listOf(
            Tag.of("entity.type", mention.type),
            Tag.of("valid", valid.toString())
        )

        meterRegistry.counter("dice.mentions.validated", tags).increment()

        return valid
    }

    override fun rejectionReason(mention: SuggestedMention): String? {
        return delegate.rejectionReason(mention)
    }
}
