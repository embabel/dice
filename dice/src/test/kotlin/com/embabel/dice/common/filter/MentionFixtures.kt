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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition

/**
 * Builders for the mentions, propositions and analysis contexts used in mention filter tests.
 */
object MentionFixtures {

    /**
     * Builds a known person entity.
     *
     * @param id The entity id
     * @param name The entity name
     * @param aliases Other names the person goes by
     */
    @JvmStatic
    fun knownPerson(id: String, name: String, vararg aliases: String): KnownEntity =
        KnownEntity(
            SimpleNamedEntityData(
                id = id,
                name = name,
                description = name,
                labels = setOf("Person"),
                properties = emptyMap(),
            ),
            role = "A known person",
            aliases = aliases.toList(),
        )

    /**
     * Builds an analysis context holding the given known entities.
     *
     * @param known The known entities of the run
     */
    @JvmStatic
    fun context(vararg known: KnownEntity): SourceAnalysisContext =
        SourceAnalysisContext(
            schema = DataDictionary.fromClasses("test"),
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("mention-test"),
            knownEntities = known.toList(),
        )

    /**
     * Builds a Person mention with the given span.
     *
     * @param span The mention text
     */
    @JvmStatic
    fun mention(span: String): SuggestedMention = SuggestedMention(span = span, type = "Person", role = "OTHER")

    /**
     * Builds a Person mention with the given span and suggested id.
     *
     * @param span The mention text
     * @param suggestedId The id suggested for the mention, or null
     */
    @JvmStatic
    fun mention(span: String, suggestedId: String?): SuggestedMention =
        SuggestedMention(span = span, type = "Person", suggestedId = suggestedId, role = "OTHER")

    /**
     * Builds a proposition with the given text and mentions.
     *
     * @param text The proposition text
     * @param mentions The mentions of the proposition
     */
    @JvmStatic
    fun proposition(text: String, vararg mentions: SuggestedMention): SuggestedProposition =
        SuggestedProposition(text = text, mentions = mentions.toList(), confidence = 0.9)
}
