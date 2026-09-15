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
package com.embabel.dice.entity

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.textio.template.JinjaProperties
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * `suggest_entities` renders the list of types the model is told to choose from.
 *
 * The regression these pin: the template read `domainType.clazz.simpleName`, and `clazz` exists
 * only on `JvmType`. Every [DynamicType] — the schema-declared types, which are most of them in a
 * configured world — therefore rendered with an EMPTY name under "Entity types must only come from
 * the following list", while Jinjava logged one "Cannot resolve property 'clazz'" per type per
 * chunk. A prompt that names none of its types cannot be obeyed.
 */
class SuggestEntitiesTemplateTest {

    private lateinit var renderer: JinjavaTemplateRenderer

    @BeforeEach
    fun setUp() {
        renderer = JinjavaTemplateRenderer(
            jinja = JinjaProperties(prefix = "classpath:/prompts/", suffix = ".jinja"),
            resourceLoader = DefaultResourceLoader(),
        )
    }

    private val meeting = DynamicType(
        name = "calendar.meeting",
        description = "A calendar event surfaced as a signal",
        ownProperties = listOf(
            ValuePropertyDefinition(
                name = "subject",
                type = "string",
                cardinality = Cardinality.ONE,
                description = "Event title",
            ),
        ),
    )

    private fun render(extra: Map<String, Any> = emptyMap()): String {
        val context = SourceAnalysisContext(
            schema = DataDictionary.fromDomainTypes("test", listOf(meeting)),
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("test-context"),
        )
        return renderer.renderLoadedTemplate(
            "suggest_entities",
            mapOf(
                "context" to context,
                "chunk" to Chunk.create(text = "Test chunk text", parentId = "source-1"),
            ) + extra,
        )
    }

    @Test
    fun `a dynamic type is named in the list of permitted types`() {
        val result = render()
        assertTrue(result.contains("Meeting"), "the type's label is rendered, in:\n$result")
        assertTrue(result.contains("A calendar event surfaced as a signal"), "its description too")
        assertTrue(result.contains("subject"), "and its properties")
    }

    @Test
    fun `directions render when a caller supplies them and are silent otherwise`() {
        assertFalse(
            render().contains("Consider the following direction"),
            "no directions supplied — the block stays out of the prompt",
        )
        assertTrue(
            render(mapOf("directions" to "focus on scheduling"))
                .contains("Consider the following direction: focus on scheduling"),
        )
    }
}
