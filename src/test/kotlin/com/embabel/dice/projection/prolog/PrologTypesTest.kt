package com.embabel.dice.projection.prolog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrologTypesTest {

    @Nested
    inner class PrologFactTests {

        @Test
        fun `toProlog formats simple fact correctly`() {
            val fact = PrologFact(
                predicate = "friend",
                args = listOf("alice", "bob"),
                confidence = 0.9,
                sourcePropositionIds = listOf("prop-1"),
            )

            assertEquals("friend('alice', 'bob').", fact.toProlog())
        }

        @Test
        fun `toPrologTerm formats without period`() {
            val fact = PrologFact(
                predicate = "expert_in",
                args = listOf("alice", "kubernetes"),
                confidence = 0.85,
                sourcePropositionIds = listOf("prop-1"),
            )

            assertEquals("expert_in('alice', 'kubernetes')", fact.toPrologTerm())
        }

        @Test
        fun `quoteAtom normalizes special characters`() {
            assertEquals("'hello_world'", PrologFact.quoteAtom("Hello World"))
            assertEquals("'alice_123'", PrologFact.quoteAtom("Alice-123"))
            assertEquals("'test_value'", PrologFact.quoteAtom("Test@Value"))
        }

        @Test
        fun `quoteAtom handles UUIDs`() {
            val uuid = "550e8400-e29b-41d4-a716-446655440000"
            val quoted = PrologFact.quoteAtom(uuid)

            assertTrue(quoted.startsWith("'"))
            assertTrue(quoted.endsWith("'"))
            // Should normalize hyphens to underscores
            assertTrue(quoted.contains("_"))
        }

        @Test
        fun `fact preserves source proposition ids`() {
            val fact = PrologFact(
                predicate = "works_at",
                args = listOf("alice", "techcorp"),
                confidence = 0.8,
                sourcePropositionIds = listOf("prop-1", "prop-2"),
            )

            assertEquals(listOf("prop-1", "prop-2"), fact.sourcePropositionIds)
        }
    }

    @Nested
    inner class ConfidenceFactTests {

        @Test
        fun `toProlog formats confidence fact correctly`() {
            val fact = PrologFact(
                predicate = "expert_in",
                args = listOf("alice", "kubernetes"),
                confidence = 0.95,
                sourcePropositionIds = listOf("prop-1"),
            )
            val confidenceFact = ConfidenceFact(fact)

            val prolog = confidenceFact.toProlog()

            assertTrue(prolog.startsWith("confidence("))
            assertTrue(prolog.contains("expert_in('alice', 'kubernetes')"))
            assertTrue(prolog.contains("0.95"))
            assertTrue(prolog.endsWith(")."))
        }
    }

    @Nested
    inner class GroundingFactTests {

        @Test
        fun `toProlog formats grounding fact correctly`() {
            val fact = PrologFact(
                predicate = "friend",
                args = listOf("alice", "bob"),
                confidence = 0.9,
                sourcePropositionIds = listOf("prop-123"),
            )
            val groundingFact = GroundingFact(fact, "prop-123")

            val prolog = groundingFact.toProlog()

            assertTrue(prolog.startsWith("grounded_by("))
            assertTrue(prolog.contains("friend('alice', 'bob')"))
            assertTrue(prolog.contains("'prop-123'"))
            assertTrue(prolog.endsWith(")."))
        }
    }


    @Nested
    inner class PredicateMappingTests {

        @Test
        fun `mapping stores relationship type and predicate`() {
            val mapping = PredicateMapping(
                relationshipType = "EXPERT_IN",
                predicate = "expert_in",
            )

            assertEquals("EXPERT_IN", mapping.relationshipType)
            assertEquals("expert_in", mapping.predicate)
            assertEquals(0, mapping.subjectArgIndex)
            assertEquals(1, mapping.objectArgIndex)
        }

        @Test
        fun `mapping with custom arg indices`() {
            val mapping = PredicateMapping(
                relationshipType = "MANAGED_BY",
                predicate = "managed_by",
                subjectArgIndex = 1,
                objectArgIndex = 0,
            )

            assertEquals(1, mapping.subjectArgIndex)
            assertEquals(0, mapping.objectArgIndex)
        }
    }

    @Nested
    inner class PrologSchemaTests {

        @Test
        fun `getMapping finds by uppercase type`() {
            val schema = PrologSchema.withRules("")

            val mapping = schema.getMapping("EXPERT_IN")

            assertNotNull(mapping)
            assertEquals("expert_in", mapping?.predicate)
        }

        @Test
        fun `getMapping converts camelCase to UPPER_SNAKE_CASE`() {
            val schema = PrologSchema.withRules("")

            val mapping = schema.getMapping("expertIn")

            assertNotNull(mapping)
            assertEquals("expert_in", mapping?.predicate)
        }

        @Test
        fun `getPredicate returns mapped predicate`() {
            val schema = PrologSchema.withRules("")

            val predicate = schema.getPredicate("WORKS_AT")

            assertEquals("works_at", predicate)
        }

        @Test
        fun `getPredicate derives snake_case for unknown types`() {
            val schema = PrologSchema.withRules("")

            val predicate = schema.getPredicate("customRelation")

            assertEquals("custom_relation", predicate)
        }

        @Test
        fun `allPredicates returns all defined predicates`() {
            val schema = PrologSchema.withRules("")

            val predicates = schema.allPredicates()

            assertTrue(predicates.contains("expert_in"))
            assertTrue(predicates.contains("works_at"))
            assertTrue(predicates.contains("friend_of"))
            assertTrue(predicates.contains("colleague_of"))
        }

        @Test
        fun `withDefaults loads rules from classpath`() {
            val schema = PrologSchema.withDefaults()

            val rules = schema.getBaseRules()

            // Should contain rules from dice-rules.pl
            assertTrue(rules.contains("coworker"))
            assertTrue(rules.contains("can_help_with"))
        }

        @Test
        fun `withRules uses provided rules string`() {
            val customRules = """
                custom_rule(X) :- fact(X).
            """.trimIndent()

            val schema = PrologSchema.withRules(customRules)

            assertEquals(customRules, schema.getBaseRules())
        }

        @Test
        fun `withDefaults accepts additional mappings`() {
            val customMapping = PredicateMapping("CUSTOM_REL", "custom_rel")
            val schema = PrologSchema.withDefaults(additionalMappings = listOf(customMapping))

            val mapping = schema.getMapping("CUSTOM_REL")

            assertNotNull(mapping)
            assertEquals("custom_rel", mapping?.predicate)
        }
    }

    @Nested
    inner class PrologProjectionResultTests {

        @Test
        fun `toTheory generates complete theory string`() {
            val facts = listOf(
                PrologFact("friend", listOf("alice", "bob"), 0.9, sourcePropositionIds = listOf("prop-1")),
                PrologFact("works_at", listOf("alice", "techcorp"), 0.85, sourcePropositionIds = listOf("prop-2")),
            )

            val result = PrologProjectionResult(
                facts = facts,
                confidenceFacts = facts.map { ConfidenceFact(it) },
                groundingFacts = facts.flatMap { f ->
                    f.sourcePropositionIds.map { GroundingFact(f, it) }
                },
            )

            val schema = PrologSchema.withRules("% Test rules")
            val theory = result.toTheory(schema)

            assertTrue(theory.contains("% Test rules"))
            assertTrue(theory.contains("friend('alice', 'bob')."))
            assertTrue(theory.contains("works_at('alice', 'techcorp')."))
            assertTrue(theory.contains("confidence("))
            assertTrue(theory.contains("grounded_by("))
        }

        @Test
        fun `toTheory includes base rules from schema`() {
            val facts = listOf(
                PrologFact("works_at", listOf("alice-id", "corp-id"), 0.9, sourcePropositionIds = listOf("prop-1")),
            )

            val result = PrologProjectionResult(
                facts = facts,
                confidenceFacts = emptyList(),
                groundingFacts = emptyList(),
            )

            val customRules = "custom_rule(X) :- works_at(X, _)."
            val theory = result.toTheory(PrologSchema.withRules(customRules))

            assertTrue(theory.contains("custom_rule"))
            assertTrue(theory.contains("works_at"))
        }

        @Test
        fun `factCount returns number of facts`() {
            val facts = listOf(
                PrologFact("a", listOf("1"), 0.9, sourcePropositionIds = listOf("p1")),
                PrologFact("b", listOf("2"), 0.8, sourcePropositionIds = listOf("p2")),
                PrologFact("c", listOf("3"), 0.7, sourcePropositionIds = listOf("p3")),
            )

            val result = PrologProjectionResult(
                facts = facts,
                confidenceFacts = emptyList(),
                groundingFacts = emptyList(),
            )

            assertEquals(3, result.factCount)
        }

        @Test
        fun `empty result has zero facts`() {
            val result = PrologProjectionResult(
                facts = emptyList(),
                confidenceFacts = emptyList(),
                groundingFacts = emptyList(),
            )

            assertEquals(0, result.factCount)
        }
    }

    @Nested
    inner class PrologRuleLoaderTests {

        @Test
        fun `loadFromResource loads dice rules`() {
            val rules = PrologRuleLoader.loadFromResource()

            assertNotNull(rules)
            assertTrue(rules.isNotBlank())
            assertTrue(rules.contains("coworker"))
        }

        @Test
        fun `tryLoadFromResource returns null for missing resource`() {
            val rules = PrologRuleLoader.tryLoadFromResource("nonexistent/file.pl")

            assertNull(rules)
        }

        @Test
        fun `loadFromResource throws for missing resource`() {
            assertThrows<IllegalArgumentException> {
                PrologRuleLoader.loadFromResource("nonexistent/file.pl")
            }
        }
    }
}
