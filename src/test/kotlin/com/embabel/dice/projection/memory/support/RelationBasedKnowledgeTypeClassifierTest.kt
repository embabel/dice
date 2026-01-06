package com.embabel.dice.projection.memory.support

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.common.Relation
import com.embabel.dice.common.Relations
import com.embabel.dice.projection.memory.KnowledgeTypeClassifier
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RelationBasedKnowledgeTypeClassifierTest {

    private val contextId = ContextId("test")

    private fun proposition(text: String, confidence: Double = 0.8, decay: Double = 0.1): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = decay,
        )

    @Nested
    inner class RelationMatchingTests {

        @Test
        fun `classifies based on matching procedural relation predicate`() {
            val relations = Relations.empty()
                .withProcedural("likes")
                .withProcedural("prefers")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Alice likes jazz")))
            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Bob prefers tea over coffee")))
        }

        @Test
        fun `classifies based on matching semantic relation predicate`() {
            val relations = Relations.empty()
                .withSemantic("works at")
                .withSemantic("is located in")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            assertEquals(KnowledgeType.SEMANTIC, classifier.classify(proposition("Alice works at Acme Corp")))
            assertEquals(KnowledgeType.SEMANTIC, classifier.classify(proposition("Paris is located in France")))
        }

        @Test
        fun `classifies based on matching episodic relation predicate`() {
            val relations = Relations.empty()
                .withEpisodic("met")
                .withEpisodic("visited")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            assertEquals(KnowledgeType.EPISODIC, classifier.classify(proposition("Alice met Bob yesterday")))
            assertEquals(KnowledgeType.EPISODIC, classifier.classify(proposition("Charlie visited the office")))
        }

        @Test
        fun `case insensitive matching by default`() {
            val relations = Relations.empty()
                .withProcedural("LIKES")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Alice likes jazz")))
            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Alice LIKES jazz")))
        }

        @Test
        fun `first matching relation wins`() {
            val relations = Relations.of(
                Relation.procedural("likes", "preference"),
                Relation.semantic("likes", "affection"), // same predicate, different type
            )

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            // First match (procedural) wins
            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Alice likes jazz")))
        }
    }

    @Nested
    inner class FallbackTests {

        @Test
        fun `uses heuristic fallback when no relation matches`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            // No matching relation, high confidence + low decay = SEMANTIC
            assertEquals(KnowledgeType.SEMANTIC, classifier.classify(proposition("Alice works at Acme", confidence = 0.9, decay = 0.1)))
        }

        @Test
        fun `fallback classifies high decay as episodic`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            // High decay suggests episodic
            assertEquals(KnowledgeType.EPISODIC, classifier.classify(proposition("Something happened", confidence = 0.5, decay = 0.8)))
        }

        @Test
        fun `fallback classifies low confidence as working`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)

            // Low confidence, moderate decay = WORKING
            assertEquals(KnowledgeType.WORKING, classifier.classify(proposition("Maybe something", confidence = 0.5, decay = 0.4)))
        }

        @Test
        fun `custom fallback classifier is used`() {
            val relations = Relations.empty()
                .withProcedural("likes")

            val alwaysSemantic = KnowledgeTypeClassifier { KnowledgeType.SEMANTIC }
            val classifier = RelationBasedKnowledgeTypeClassifier(relations, fallback = alwaysSemantic)

            // No match, uses custom fallback
            assertEquals(KnowledgeType.SEMANTIC, classifier.classify(proposition("No matching predicate here")))
        }
    }

    @Nested
    inner class BuilderTests {

        @Test
        fun `withRelations adds more relations`() {
            val initial = Relations.empty().withProcedural("likes")
            val additional = Relations.empty().withSemantic("works at")

            val classifier = RelationBasedKnowledgeTypeClassifier.from(initial)
                .withRelations(additional)

            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Alice likes jazz")))
            assertEquals(KnowledgeType.SEMANTIC, classifier.classify(proposition("Alice works at Acme")))
        }

        @Test
        fun `withFallback changes fallback classifier`() {
            val relations = Relations.empty().withProcedural("likes")
            val alwaysEpisodic = KnowledgeTypeClassifier { KnowledgeType.EPISODIC }

            val classifier = RelationBasedKnowledgeTypeClassifier.from(relations)
                .withFallback(alwaysEpisodic)

            // Match uses relation
            assertEquals(KnowledgeType.PROCEDURAL, classifier.classify(proposition("Alice likes jazz")))
            // No match uses new fallback
            assertEquals(KnowledgeType.EPISODIC, classifier.classify(proposition("Unknown statement")))
        }
    }

    @Nested
    inner class HeuristicClassifierTests {

        @Test
        fun `high decay classifies as episodic`() {
            assertEquals(
                KnowledgeType.EPISODIC,
                HeuristicKnowledgeTypeClassifier.classify(proposition("Event", decay = 0.7))
            )
        }

        @Test
        fun `high confidence low decay classifies as semantic`() {
            assertEquals(
                KnowledgeType.SEMANTIC,
                HeuristicKnowledgeTypeClassifier.classify(proposition("Fact", confidence = 0.9, decay = 0.1))
            )
        }

        @Test
        fun `moderate values classify as working`() {
            assertEquals(
                KnowledgeType.WORKING,
                HeuristicKnowledgeTypeClassifier.classify(proposition("Something", confidence = 0.5, decay = 0.4))
            )
        }
    }
}
