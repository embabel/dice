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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.Semantics
import com.embabel.agent.core.With
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.common.textio.template.JinjaProperties
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.Relation
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SchemaAdherence
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.annotation.JsonClassDescription
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * Tests for proposition extraction Jinja templates.
 * Uses JinjavaTemplateRenderer directly to verify template rendering without Spring context.
 */
class PropositionExtractionTemplateTest {

    private lateinit var renderer: JinjavaTemplateRenderer

    @BeforeEach
    fun setUp() {
        renderer = JinjavaTemplateRenderer(
            jinja = JinjaProperties(prefix = "classpath:/prompts/", suffix = ".jinja"),
            resourceLoader = DefaultResourceLoader()
        )
    }

    // Test domain classes
    @JsonClassDescription("A person in the system")
    data class Person(
        override val id: String,
        override val name: String,
        override val description: String,
        @field:Semantics([
            With(key = Proposition.PREDICATE, value = "works at")
        ])
        val employer: Company? = null,
    ) : NamedEntity

    @JsonClassDescription("A company or organization")
    data class Company(
        override val id: String,
        override val name: String,
        override val description: String,
    ) : NamedEntity

    private fun createTestContext(
        knownEntities: List<KnownEntity> = emptyList(),
        relations: Relations = Relations.empty(),
    ): SourceAnalysisContext {
        val schema = DataDictionary.fromClasses("test", Person::class.java, Company::class.java)
        return SourceAnalysisContext(
            schema = schema,
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("test-context"),
            knownEntities = knownEntities,
            relations = relations,
        )
    }

    private fun createTemplateModel(
        context: SourceAnalysisContext,
        schemaAdherence: SchemaAdherence = SchemaAdherence.DEFAULT,
        existingPropositions: List<Proposition> = emptyList(),
        chunkText: String = "Test chunk text",
    ): TemplateModel {
        return TemplateModel(
            context = context,
            chunk = Chunk.create(
                text = chunkText,
                parentId = "source-1",
            ),
            schemaAdherence = schemaAdherence,
            existingPropositions = existingPropositions,
        )
    }

    @Nested
    inner class SchemaHintsTemplateTests {

        @Test
        fun `renders entity types from schema`() {
            val context = createTestContext()
            val model = createTemplateModel(context)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("Person"))
            assertTrue(result.contains("Company"))
        }

        @Test
        fun `renders relationship predicates from schema annotations`() {
            val context = createTestContext()
            val model = createTemplateModel(context)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("\"works at\""))
            assertTrue(result.contains("Company"))
        }

        @Test
        fun `renders known entities with roles`() {
            val user = Person("user-1", "Alice", "A music enthusiast")
            val context = createTestContext(
                knownEntities = listOf(
                    KnownEntity.asCurrentUser(user),
                )
            )
            val model = createTemplateModel(context)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("Alice"))
            assertTrue(result.contains("user-1"))
            assertTrue(result.contains("The user in the conversation"))
        }

        @Test
        fun `renders additional relations`() {
            val context = createTestContext(
                relations = Relations.of(
                    Relation.proceduralForSubject("likes", "expresses positive preference for", "Person"),
                    Relation.proceduralForSubject("dislikes", "expresses negative preference for", "Person"),
                    Relation.semanticBetween("is expert in", "has deep knowledge of", "Person", "Topic"),
                )
            )
            val model = createTemplateModel(context)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("\"likes\""))
            assertTrue(result.contains("expresses positive preference for"))
            assertTrue(result.contains("\"dislikes\""))
            assertTrue(result.contains("subject: Person"))
            assertTrue(result.contains("\"is expert in\""))
            assertTrue(result.contains("object: Topic"))
        }

        @Test
        fun `does not render additional relations section when empty`() {
            val context = createTestContext(relations = Relations.empty())
            val model = createTemplateModel(context)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertFalse(result.contains("Additional relation types:"))
        }

        @Test
        fun `renders strict entity adherence message`() {
            val context = createTestContext()
            val model = createTemplateModel(context, schemaAdherence = SchemaAdherence.STRICT)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("ENTITY MENTION RULES"))
            assertTrue(result.contains("ONLY create entity mentions for the schema types"))
        }

        @Test
        fun `renders relaxed entity adherence message`() {
            val context = createTestContext()
            val model = createTemplateModel(context, schemaAdherence = SchemaAdherence.RELAXED)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("Prefer propositions with the above entity types"))
            assertTrue(result.contains("feel free to extract IMPORTANT propositions"))
        }

        @Test
        fun `renders strict predicate adherence message`() {
            val context = createTestContext()
            val model = createTemplateModel(context, schemaAdherence = SchemaAdherence.STRICT)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("use the predicates defined in the schema"))
        }

        @Test
        fun `renders first-person pronoun guidance`() {
            val context = createTestContext()
            val model = createTemplateModel(context)

            val result = renderer.renderLoadedTemplate(
                "dice/schema_hints",
                mapOf("model" to model)
            )

            assertTrue(result.contains("First-person pronouns"))
            assertTrue(result.contains("refer to the user entity"))
        }
    }

    @Nested
    inner class ExistingPropositionsTemplateTests {

        @Test
        fun `renders existing propositions`() {
            val context = createTestContext()
            val existingProps = listOf(
                Proposition(
                    contextId = context.contextId,
                    text = "Alice works at Acme Corp",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
                Proposition(
                    contextId = context.contextId,
                    text = "Bob knows Alice",
                    mentions = emptyList(),
                    confidence = 0.8,
                ),
            )
            val model = createTemplateModel(context, existingPropositions = existingProps)

            val result = renderer.renderLoadedTemplate(
                "dice/existing_propositions",
                mapOf("model" to model)
            )

            assertTrue(result.contains("Alice works at Acme Corp"))
            assertTrue(result.contains("Bob knows Alice"))
            assertTrue(result.contains("DO NOT duplicate"))
        }

        @Test
        fun `renders no propositions message when empty`() {
            val context = createTestContext()
            val model = createTemplateModel(context, existingPropositions = emptyList())

            val result = renderer.renderLoadedTemplate(
                "dice/existing_propositions",
                mapOf("model" to model)
            )

            assertTrue(result.contains("NO propositions have been extracted yet"))
        }
    }

    @Nested
    inner class ExtractPropositionsTemplateTests {

        @Test
        fun `renders complete extraction prompt`() {
            val user = Person("user-1", "Rod", "A classical music fan")
            val context = createTestContext(
                knownEntities = listOf(KnownEntity.asCurrentUser(user)),
                relations = Relations.of(
                    Relation.proceduralForSubject("likes", "expresses positive preference for", "Person"),
                )
            )
            val model = createTemplateModel(context, chunkText = "Rod said he likes Reger")

            val result = renderer.renderLoadedTemplate(
                "dice/extract_propositions",
                mapOf("model" to model)
            )

            // Verify includes schema hints
            assertTrue(result.contains("Person"))
            assertTrue(result.contains("Company"))

            // Verify includes known entities
            assertTrue(result.contains("Rod"))

            // Verify includes additional relations
            assertTrue(result.contains("\"likes\""))

            // Verify includes guidelines
            assertTrue(result.contains("Single fact per proposition"))
            assertTrue(result.contains("SUBJECT"))
            assertTrue(result.contains("OBJECT"))

            // Verify includes chunk text
            assertTrue(result.contains("Rod said he likes Reger"))
        }
    }
}
