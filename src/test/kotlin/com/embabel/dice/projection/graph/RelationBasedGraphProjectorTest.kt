package com.embabel.dice.projection.graph

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.Semantics
import com.embabel.agent.core.With
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.*
import com.fasterxml.jackson.annotation.JsonClassDescription
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RelationBasedGraphProjectorTest {

    private val contextId = ContextId("test")
    private val emptySchema = DataDictionary.fromDomainTypes(emptyList())

    // Test domain classes with @Semantics predicate annotations
    @JsonClassDescription("A person")
    data class Person(
        override val id: String,
        override val name: String,
        override val description: String = "",
        @field:Semantics([With(key = Proposition.PREDICATE, value = "works at")])
        val employer: Company? = null,
    ) : NamedEntity

    @JsonClassDescription("A company")
    data class Company(
        override val id: String,
        override val name: String,
        override val description: String = "",
    ) : NamedEntity

    private fun proposition(
        text: String,
        subjectSpan: String,
        subjectType: String,
        subjectId: String?,
        objectSpan: String,
        objectType: String,
        objectId: String?,
        confidence: Double = 0.9,
    ): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = listOf(
            EntityMention(
                span = subjectSpan,
                type = subjectType,
                resolvedId = subjectId,
                role = MentionRole.SUBJECT,
            ),
            EntityMention(
                span = objectSpan,
                type = objectType,
                resolvedId = objectId,
                role = MentionRole.OBJECT,
            ),
        ),
        confidence = confidence,
    )

    @Nested
    inner class PredicateMatchingTests {

        @Test
        fun `projects proposition matching predicate`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )

            val result = projector.project(prop, emptySchema)

            assertTrue(result is ProjectionSuccess)
            val success = result as ProjectionSuccess
            assertEquals("LIKES", success.projected.type)
            assertEquals("alice-1", success.projected.sourceId)
            assertEquals("genre-jazz", success.projected.targetId)
        }

        @Test
        fun `converts multi-word predicate to UPPER_SNAKE_CASE`() {
            val relations = Relations.empty()
                .withSemantic("works at")

            val projector = RelationBasedGraphProjector.from(relations)
            val prop = proposition(
                text = "Bob works at Acme Corp",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "Acme Corp", objectType = "Company", objectId = "company-acme",
            )

            val result = projector.project(prop, emptySchema)

            assertTrue(result is ProjectionSuccess)
            assertEquals("WORKS_AT", (result as ProjectionSuccess).projected.type)
        }

        @Test
        fun `fails when no predicate matches`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)
            val prop = proposition(
                text = "Alice hates broccoli",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "broccoli", objectType = "Food", objectId = "food-broccoli",
            )

            val result = projector.project(prop, emptySchema)

            assertTrue(result is ProjectionFailed)
            assertTrue((result as ProjectionFailed).reason.contains("No matching predicate"))
        }

        @Test
        fun `case insensitive matching by default`() {
            val relations = Relations.empty()
                .withProcedural("LIKES")

            val projector = RelationBasedGraphProjector.from(relations)
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSuccess)
        }

        @Test
        fun `case sensitive matching when configured`() {
            val relations = Relations.empty()
                .withProcedural("LIKES")

            val projector = RelationBasedGraphProjector.from(relations)
                .withCaseSensitive(true)

            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionFailed) // lowercase "likes" doesn't match "LIKES"
        }
    }

    @Nested
    inner class TypeConstraintTests {

        @Test
        fun `validates subject type constraint`() {
            val relations = Relations.empty()
                .withProceduralForSubject("Person", "likes", "preference")

            val projector = RelationBasedGraphProjector.from(relations)

            // Matching subject type - should succeed
            val personProp = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )
            val personResult = projector.project(personProp, emptySchema)
            assertTrue(personResult is ProjectionSuccess)

            // Wrong subject type - should fail
            val companyProp = proposition(
                text = "Acme Corp likes jazz",
                subjectSpan = "Acme Corp", subjectType = "Company", subjectId = "company-acme",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )
            val companyResult = projector.project(companyProp, emptySchema)
            assertTrue(companyResult is ProjectionFailed)
            assertTrue((companyResult as ProjectionFailed).reason.contains("Subject type"))
        }

        @Test
        fun `validates both subject and object type constraints`() {
            val relations = Relations.empty()
                .withSemanticBetween("Person", "Company", "works at", "employment")

            val projector = RelationBasedGraphProjector.from(relations)

            // Correct types - should succeed
            val validProp = proposition(
                text = "Bob works at Acme",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "Acme", objectType = "Company", objectId = "company-acme",
            )
            assertTrue(projector.project(validProp, emptySchema) is ProjectionSuccess)

            // Wrong object type - should fail
            val wrongObjectProp = proposition(
                text = "Bob works at home",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "home", objectType = "Location", objectId = "loc-home",
            )
            val wrongObjectResult = projector.project(wrongObjectProp, emptySchema)
            assertTrue(wrongObjectResult is ProjectionFailed)
            assertTrue((wrongObjectResult as ProjectionFailed).reason.contains("Object type"))
        }
    }

    @Nested
    inner class PolicyTests {

        @Test
        fun `skips low confidence propositions with default policy`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
                confidence = 0.5, // Below default threshold
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSkipped)
        }

        @Test
        fun `skips unresolved entities with default policy`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = null, // Unresolved
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSkipped)
        }

        @Test
        fun `accepts with lenient policy`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)
                .withPolicy(LenientProjectionPolicy(0.7))

            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
                confidence = 0.75,
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSuccess)
        }

        @Test
        fun `accepts all with always project policy`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)
                .withPolicy(AlwaysProjectPolicy)

            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
                confidence = 0.3, // Would normally be rejected
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSuccess)
        }
    }

    @Nested
    inner class ProjectAllTests {

        @Test
        fun `projects multiple propositions`() {
            val relations = Relations.empty()
                .withProcedural("likes")
                .withSemantic("works at")

            val projector = RelationBasedGraphProjector.from(relations)

            val props = listOf(
                proposition(
                    text = "Alice likes jazz",
                    subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                    objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
                ),
                proposition(
                    text = "Bob works at Acme",
                    subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                    objectSpan = "Acme", objectType = "Company", objectId = "company-acme",
                ),
            )

            val results = projector.projectAll(props, emptySchema)

            assertEquals(2, results.successCount)
            assertEquals(0, results.failureCount)
            assertEquals("LIKES", results.projected[0].type)
            assertEquals("WORKS_AT", results.projected[1].type)
        }
    }

    @Nested
    inner class RelationshipTypeConversionTests {

        @Test
        fun `converts simple predicate`() {
            assertEquals("LIKES", RelationBasedGraphProjector.toRelationshipType("likes"))
        }

        @Test
        fun `converts two-word predicate`() {
            assertEquals("WORKS_AT", RelationBasedGraphProjector.toRelationshipType("works at"))
        }

        @Test
        fun `converts three-word predicate`() {
            assertEquals("IS_EXPERT_IN", RelationBasedGraphProjector.toRelationshipType("is expert in"))
        }

        @Test
        fun `handles extra whitespace`() {
            assertEquals("WORKS_AT", RelationBasedGraphProjector.toRelationshipType("  works   at  "))
        }
    }

    @Nested
    inner class BuilderTests {

        @Test
        fun `withRelations adds more relations`() {
            val initial = Relations.empty().withProcedural("likes")
            val additional = Relations.empty().withSemantic("works at")

            val projector = RelationBasedGraphProjector.from(initial)
                .withRelations(additional)

            val prop1 = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )
            val prop2 = proposition(
                text = "Bob works at Acme",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "Acme", objectType = "Company", objectId = "company-acme",
            )

            assertTrue(projector.project(prop1, emptySchema) is ProjectionSuccess)
            assertTrue(projector.project(prop2, emptySchema) is ProjectionSuccess)
        }
    }

    @Nested
    inner class SchemaMatchingTests {

        private val schemaWithPredicate = DataDictionary.fromClasses(Person::class.java, Company::class.java)

        @Test
        fun `uses schema relationship predicate and property name`() {
            // Person.employer has @Semantics predicate="works at"
            val projector = RelationBasedGraphProjector()

            val prop = proposition(
                text = "Bob works at Acme",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "Acme", objectType = "Company", objectId = "company-acme",
            )

            val result = projector.project(prop, schemaWithPredicate)

            assertTrue(result is ProjectionSuccess)
            // Uses property name "employer" not derived "WORKS_AT"
            assertEquals("employer", (result as ProjectionSuccess).projected.type)
        }

        @Test
        fun `schema takes priority over relations`() {
            // Both schema and relations have "works at" predicate
            val relations = Relations.empty()
                .withSemantic("works at") // Would produce "WORKS_AT"

            val projector = RelationBasedGraphProjector.from(relations)

            val prop = proposition(
                text = "Bob works at Acme",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "Acme", objectType = "Company", objectId = "company-acme",
            )

            val result = projector.project(prop, schemaWithPredicate)

            assertTrue(result is ProjectionSuccess)
            // Schema wins - uses "employer" not "WORKS_AT"
            assertEquals("employer", (result as ProjectionSuccess).projected.type)
        }

        @Test
        fun `falls back to relations when schema has no matching predicate`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val projector = RelationBasedGraphProjector.from(relations)

            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-jazz",
            )

            val result = projector.project(prop, schemaWithPredicate)

            assertTrue(result is ProjectionSuccess)
            // Falls back to relations - derives "LIKES"
            assertEquals("LIKES", (result as ProjectionSuccess).projected.type)
        }

        @Test
        fun `validates schema type constraints`() {
            val projector = RelationBasedGraphProjector()

            // Wrong subject type - schema says Person.employer -> Company
            val wrongSubjectProp = proposition(
                text = "Acme Corp works at Office",
                subjectSpan = "Acme Corp", subjectType = "Company", subjectId = "company-acme",
                objectSpan = "Office", objectType = "Location", objectId = "loc-office",
            )

            val result = projector.project(wrongSubjectProp, schemaWithPredicate)

            assertTrue(result is ProjectionFailed)
            assertTrue((result as ProjectionFailed).reason.contains("Subject type"))
        }

        @Test
        fun `validates schema object type constraints`() {
            val projector = RelationBasedGraphProjector()

            // Wrong object type - schema says Person.employer -> Company
            val wrongObjectProp = proposition(
                text = "Bob works at Home",
                subjectSpan = "Bob", subjectType = "Person", subjectId = "bob-1",
                objectSpan = "Home", objectType = "Location", objectId = "loc-home",
            )

            val result = projector.project(wrongObjectProp, schemaWithPredicate)

            assertTrue(result is ProjectionFailed)
            assertTrue((result as ProjectionFailed).reason.contains("Object type"))
        }
    }
}
