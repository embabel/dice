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
package com.embabel.dice.proposition.revision

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

private val testContextId = ContextId("test-context")

class PropositionReviserTest {

    @Nested
    inner class RevisionResultTests {

        @Test
        fun `New result contains the proposition`() {
            val prop = createProposition("Alice is a software engineer")
            val result = RevisionResult.New(prop)

            assertTrue(result is RevisionResult.New)
            assertEquals(prop, (result as RevisionResult.New).proposition)
        }

        @Test
        fun `Merged result contains original and revised`() {
            val original = createProposition("Alice is a software engineer", confidence = 0.7)
            val revised = original.copy(confidence = 0.85)
            val result = RevisionResult.Merged(original, revised)

            assertTrue(result is RevisionResult.Merged)
            assertEquals(original, (result as RevisionResult.Merged).original)
            assertEquals(revised, result.revised)
        }

        @Test
        fun `Reinforced result contains original and revised`() {
            val original = createProposition("Alice likes Kotlin", confidence = 0.6)
            val revised = original.copy(confidence = 0.7)
            val result = RevisionResult.Reinforced(original, revised)

            assertTrue(result is RevisionResult.Reinforced)
            assertEquals(original, (result as RevisionResult.Reinforced).original)
            assertEquals(revised, result.revised)
        }

        @Test
        fun `Contradicted result contains original and new`() {
            val original = createProposition("Alice prefers Java", confidence = 0.8)
            val newProp = createProposition("Alice prefers Kotlin", confidence = 0.9)
            val result = RevisionResult.Contradicted(original, newProp)

            assertTrue(result is RevisionResult.Contradicted)
            assertEquals(original, (result as RevisionResult.Contradicted).original)
            assertEquals(newProp, result.new)
        }
    }

    @Nested
    inner class PropositionRelationTests {

        @Test
        fun `all relation types are available`() {
            val relations = PropositionRelation.entries

            assertEquals(5, relations.size)
            assertTrue(relations.contains(PropositionRelation.IDENTICAL))
            assertTrue(relations.contains(PropositionRelation.SIMILAR))
            assertTrue(relations.contains(PropositionRelation.UNRELATED))
            assertTrue(relations.contains(PropositionRelation.CONTRADICTORY))
            assertTrue(relations.contains(PropositionRelation.GENERALIZES))
        }
    }

    @Nested
    inner class ClassifiedPropositionTests {

        @Test
        fun `classified proposition stores all properties`() {
            val prop = createProposition("Test proposition")
            val classified = ClassifiedProposition(
                proposition = prop,
                relation = PropositionRelation.SIMILAR,
                similarity = 0.75,
                reasoning = "Both discuss the same topic"
            )

            assertEquals(prop, classified.proposition)
            assertEquals(PropositionRelation.SIMILAR, classified.relation)
            assertEquals(0.75, classified.similarity)
            assertEquals("Both discuss the same topic", classified.reasoning)
        }

        @Test
        fun `classified proposition with null reasoning`() {
            val prop = createProposition("Test proposition")
            val classified = ClassifiedProposition(
                proposition = prop,
                relation = PropositionRelation.UNRELATED,
                similarity = 0.1,
                reasoning = null
            )

            assertNull(classified.reasoning)
        }
    }

    @Nested
    inner class SimpleReviserTests {

        private lateinit var repository: TestPropositionRepository
        private lateinit var reviser: TestPropositionReviser

        @BeforeEach
        fun setup() {
            repository = TestPropositionRepository()
            reviser = TestPropositionReviser()
        }

        @Test
        fun `new proposition is stored when no similar exist`() {
            val prop = createProposition("Alice is a software engineer")

            val result = reviser.revise(prop, repository)

            assertTrue(result is RevisionResult.New)
            assertEquals(1, repository.count())
            assertNotNull(repository.findById(prop.id))
        }

        @Test
        fun `identical proposition is merged`() {
            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            // Configure reviser to classify as identical
            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.IDENTICAL, 0.95, "Same meaning")
            )

            val newProp = createProposition("Alice is a software engineer", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Merged)
            val merged = result as RevisionResult.Merged
            assertEquals(existing.id, merged.original.id)
            // Merged confidence should be boosted
            assertTrue(merged.revised.confidence > existing.confidence)
        }

        @Test
        fun `similar proposition is reinforced`() {
            val existing = createProposition("Alice is a developer", confidence = 0.6)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.SIMILAR, 0.7, "Related topic")
            )

            val newProp = createProposition("Alice works as a software engineer", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Reinforced)
            val reinforced = result as RevisionResult.Reinforced
            assertEquals(existing.id, reinforced.original.id)
            assertTrue(reinforced.revised.confidence > existing.confidence)
        }

        @Test
        fun `contradictory proposition reduces original confidence`() {
            val existing = createProposition("Alice prefers Java", confidence = 0.8)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.CONTRADICTORY, 0.1, "Opposite preference")
            )

            val newProp = createProposition("Alice prefers Kotlin over Java", confidence = 0.9)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Contradicted)
            val contradicted = result as RevisionResult.Contradicted
            assertTrue(contradicted.original.confidence < 0.8)
            assertEquals(PropositionStatus.CONTRADICTED, contradicted.original.status)
            assertEquals(2, repository.count()) // Both stored
        }

        @Test
        fun `reviseAll processes multiple propositions`() {
            val props = listOf(
                createProposition("Alice is an engineer"),
                createProposition("Bob is a designer"),
                createProposition("Charlie is a manager")
            )

            val results = reviser.reviseAll(props, repository)

            assertEquals(3, results.size)
            assertTrue(results.all { it is RevisionResult.New })
            assertEquals(3, repository.count())
        }

        @Test
        fun `classify returns empty list for no candidates`() {
            val newProp = createProposition("Test proposition")
            val classified = reviser.classify(newProp, emptyList())

            assertTrue(classified.isEmpty())
        }
    }

    @Nested
    inner class AutoMergeTests {

        private lateinit var repository: TestPropositionRepository
        private lateinit var reviser: TestPropositionReviser

        @BeforeEach
        fun setup() {
            repository = TestPropositionRepository(defaultScore = 0.97)
            reviser = TestPropositionReviser(autoMergeThreshold = 0.95)
        }

        @Test
        fun `high embedding score triggers auto-merge without classification`() {
            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            val newProp = createProposition("Alice works as a software engineer", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Merged, "Expected Merged but got ${result::class.simpleName}")
            val merged = result as RevisionResult.Merged
            assertEquals(existing.id, merged.original.id)
            assertTrue(merged.revised.confidence > existing.confidence)
            // Should NOT have used the LLM classify path
            assertEquals(0, reviser.classifyCallCount, "Auto-merge should skip LLM classification")
        }

        @Test
        fun `score below threshold falls through to classification`() {
            // Use a lower score that won't trigger auto-merge
            repository = TestPropositionRepository(defaultScore = 0.90)
            reviser = TestPropositionReviser(autoMergeThreshold = 0.95)

            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            // Set up classification response since auto-merge won't fire
            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.SIMILAR, 0.7, "Related")
            )

            val newProp = createProposition("Alice works as a developer", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            // Should have fallen through to classification
            assertTrue(result is RevisionResult.Reinforced)
            assertEquals(1, reviser.classifyCallCount, "Should have used LLM classification")
        }

        @Test
        fun `auto-merge disabled with threshold above 1`() {
            repository = TestPropositionRepository(defaultScore = 0.99)
            reviser = TestPropositionReviser(autoMergeThreshold = 1.1)

            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.IDENTICAL, 0.99, "Same")
            )

            val newProp = createProposition("Alice works as a software engineer", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            // Even with score 0.99, should NOT auto-merge because threshold is 1.1
            assertEquals(1, reviser.classifyCallCount, "Should have used LLM classification when auto-merge disabled")
            assertTrue(result is RevisionResult.Merged)
        }

        @Test
        fun `reviseAll auto-merges high-score propositions in batch`() {
            // High score: auto-merge kicks in
            repository = TestPropositionRepository(defaultScore = 0.97)
            reviser = TestPropositionReviser(autoMergeThreshold = 0.95)

            val existing1 = createProposition("Alice is a software engineer", confidence = 0.7)
            val existing2 = createProposition("Bob is a designer", confidence = 0.6)
            repository.save(existing1)
            repository.save(existing2)

            val props = listOf(
                createProposition("Alice works as a software engineer", confidence = 0.8),
                createProposition("Bob works as a designer", confidence = 0.8),
            )

            val results = reviser.reviseAll(props, repository)

            assertEquals(2, results.size)
            assertTrue(results.all { it is RevisionResult.Merged }, "All should be auto-merged")
            assertEquals(0, reviser.classifyCallCount, "Auto-merge should skip all LLM calls")
        }
    }

    @Nested
    inner class DecayAdjustmentTests {

        private lateinit var repository: TestPropositionRepository
        private lateinit var reviser: TestPropositionReviser

        @BeforeEach
        fun setup() {
            repository = TestPropositionRepository()
            reviser = TestPropositionReviser()
        }

        @Test
        fun `contradicted proposition gets accelerated decay`() {
            val existing = createProposition("Alice is 30 years old", confidence = 0.8, decay = 0.1)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.CONTRADICTORY, 0.1, "Different age")
            )

            val newProp = createProposition("Alice is 35 years old", confidence = 0.9)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Contradicted)
            val contradicted = result as RevisionResult.Contradicted
            // Decay should increase by 0.15: 0.1 + 0.15 = 0.25
            assertEquals(0.25, contradicted.original.decay, 0.001)
            assertTrue(contradicted.original.decay > existing.decay)
        }

        @Test
        fun `contradicted proposition with zero decay gets nonzero decay`() {
            val existing = createProposition("Alice has 2 cats", confidence = 0.8, decay = 0.0)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.CONTRADICTORY, 0.1, "Different count")
            )

            val newProp = createProposition("Alice has 3 cats", confidence = 0.9)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Contradicted)
            val contradicted = result as RevisionResult.Contradicted
            assertEquals(0.15, contradicted.original.decay, 0.001)
        }

        @Test
        fun `merged proposition gets slowed decay`() {
            val existing = createProposition("Alice works at Google", confidence = 0.7, decay = 0.2)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.IDENTICAL, 0.95, "Same fact")
            )

            val newProp = createProposition("Alice is employed at Google", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Merged)
            val merged = result as RevisionResult.Merged
            // Decay should slow: 0.2 * 0.7 = 0.14
            assertEquals(0.14, merged.revised.decay, 0.001)
            assertTrue(merged.revised.decay < existing.decay)
        }

        @Test
        fun `reinforced proposition gets slightly slowed decay`() {
            val existing = createProposition("Alice likes Kotlin", confidence = 0.6, decay = 0.2)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.SIMILAR, 0.75, "Related")
            )

            val newProp = createProposition("Alice enjoys programming in Kotlin", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Reinforced)
            val reinforced = result as RevisionResult.Reinforced
            // Decay should slow: 0.2 * 0.85 = 0.17
            assertEquals(0.17, reinforced.revised.decay, 0.001)
            assertTrue(reinforced.revised.decay < existing.decay)
        }
    }

    @Nested
    inner class ReinforceCountTests {

        private lateinit var repository: TestPropositionRepository
        private lateinit var reviser: TestPropositionReviser

        @BeforeEach
        fun setup() {
            repository = TestPropositionRepository()
            reviser = TestPropositionReviser()
        }

        @Test
        fun `new proposition has zero reinforceCount`() {
            val prop = createProposition("Alice likes music")
            assertEquals(0, prop.reinforceCount)
        }

        @Test
        fun `merged proposition increments reinforceCount`() {
            val existing = createProposition("Alice works at Google", confidence = 0.7)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.IDENTICAL, 0.95, "Same fact")
            )

            val newProp = createProposition("Alice is employed at Google", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Merged)
            assertEquals(1, (result as RevisionResult.Merged).revised.reinforceCount)
        }

        @Test
        fun `reinforced proposition increments reinforceCount`() {
            val existing = createProposition("Alice likes Kotlin", confidence = 0.6)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.SIMILAR, 0.75, "Related")
            )

            val newProp = createProposition("Alice enjoys programming in Kotlin", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Reinforced)
            assertEquals(1, (result as RevisionResult.Reinforced).revised.reinforceCount)
        }

        @Test
        fun `reinforceCount accumulates across multiple merges`() {
            val existing = createProposition("Alice works at Google", confidence = 0.7)
                .copy(reinforceCount = 3)
            repository.save(existing)

            reviser.nextClassification = listOf(
                ClassifiedProposition(existing, PropositionRelation.IDENTICAL, 0.95, "Same fact")
            )

            val newProp = createProposition("Alice is employed at Google", confidence = 0.8)
            val result = reviser.revise(newProp, repository)

            assertTrue(result is RevisionResult.Merged)
            assertEquals(4, (result as RevisionResult.Merged).revised.reinforceCount)
        }
    }

    @Nested
    inner class EntityOverlapFilterTests {

        @Test
        fun `hasEntityOverlap returns true when resolved IDs match`() {
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )
            val a = createProposition(
                "Alice works at Google",
                mentions = listOf(
                    EntityMention("Alice", "Person", resolvedId = "entity-1", role = MentionRole.SUBJECT),
                    EntityMention("Google", "Company", resolvedId = "entity-2", role = MentionRole.OBJECT),
                ),
            )
            val b = createProposition(
                "Alice lives in NYC",
                mentions = listOf(
                    EntityMention("Alice", "Person", resolvedId = "entity-1", role = MentionRole.SUBJECT),
                    EntityMention("NYC", "City", resolvedId = "entity-3", role = MentionRole.OBJECT),
                ),
            )
            assertTrue(reviser.hasEntityOverlap(a, b))
        }

        @Test
        fun `hasEntityOverlap returns false when no entities overlap`() {
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )
            val a = createProposition(
                "Alice works at Google",
                mentions = listOf(
                    EntityMention("Alice", "Person", resolvedId = "entity-1", role = MentionRole.SUBJECT),
                ),
            )
            val b = createProposition(
                "Bob likes hiking",
                mentions = listOf(
                    EntityMention("Bob", "Person", resolvedId = "entity-99", role = MentionRole.SUBJECT),
                ),
            )
            assertFalse(reviser.hasEntityOverlap(a, b))
        }

        @Test
        fun `hasEntityOverlap falls back to span match when no resolved IDs`() {
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )
            val a = createProposition(
                "Alice works at Google",
                mentions = listOf(EntityMention("Alice", "Person")),
            )
            val b = createProposition(
                "Alice likes hiking",
                mentions = listOf(EntityMention("alice", "Person")), // lowercase
            )
            assertTrue(reviser.hasEntityOverlap(a, b))
        }

        @Test
        fun `hasEntityOverlap returns true when either has no mentions`() {
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )
            val withMentions = createProposition(
                "Alice works at Google",
                mentions = listOf(EntityMention("Alice", "Person", resolvedId = "entity-1")),
            )
            val withoutMentions = createProposition("Something happened")
            assertTrue(reviser.hasEntityOverlap(withMentions, withoutMentions))
            assertTrue(reviser.hasEntityOverlap(withoutMentions, withMentions))
        }

        @Test
        fun `retrieveAndFastPath filters out candidates with no entity overlap`() {
            val repository = TestPropositionRepository(defaultScore = 0.8)
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
                autoMergeThreshold = 1.1, // disable auto-merge
            )

            // Existing proposition about Bob
            val existing = createProposition(
                "Bob is a designer",
                mentions = listOf(EntityMention("Bob", "Person", resolvedId = "bob-1", role = MentionRole.SUBJECT)),
            )
            repository.save(existing)

            // New proposition about Alice — no entity overlap with Bob
            val newProp = createProposition(
                "Alice is an engineer",
                mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice-1", role = MentionRole.SUBJECT)),
            )

            val result = reviser.retrieveAndFastPath(newProp, repository)

            // Should be New because entity-overlap filter eliminated the only candidate
            assertTrue(result is RevisionResult.New, "Expected New but got ${result::class.simpleName}")
        }

        @Test
        fun `retrieveAndFastPath keeps candidates with entity overlap`() {
            val repository = TestPropositionRepository(defaultScore = 0.8)
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
                autoMergeThreshold = 1.1, // disable auto-merge
            )

            // Existing proposition about Alice
            val existing = createProposition(
                "Alice is a designer",
                mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice-1", role = MentionRole.SUBJECT)),
            )
            repository.save(existing)

            // New proposition also about Alice — entity overlap exists
            val newProp = createProposition(
                "Alice is an engineer",
                mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice-1", role = MentionRole.SUBJECT)),
            )

            val result = reviser.retrieveAndFastPath(newProp, repository)

            // Should be PendingClassification because entity overlap keeps the candidate
            assertTrue(result is PendingClassification, "Expected PendingClassification but got ${result::class.simpleName}")
            assertEquals(1, (result as PendingClassification).candidates.size)
        }

        @Test
        fun `retrieveAndFastPath skips filter when new proposition has no mentions`() {
            val repository = TestPropositionRepository(defaultScore = 0.8)
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
                autoMergeThreshold = 1.1, // disable auto-merge
            )

            val existing = createProposition(
                "Bob is a designer",
                mentions = listOf(EntityMention("Bob", "Person", resolvedId = "bob-1")),
            )
            repository.save(existing)

            // New proposition with no mentions — filter should be bypassed
            val newProp = createProposition("Something about design")

            val result = reviser.retrieveAndFastPath(newProp, repository)

            // Should be PendingClassification (filter bypassed, candidate kept)
            assertTrue(result is PendingClassification, "Expected PendingClassification but got ${result::class.simpleName}")
        }

        @Test
        fun `entity overlap filter can be disabled`() {
            val repository = TestPropositionRepository(defaultScore = 0.8)
            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
                autoMergeThreshold = 1.1, // disable auto-merge
                entityOverlapFilter = false,
            )

            // Different entities — would be filtered if enabled
            val existing = createProposition(
                "Bob is a designer",
                mentions = listOf(EntityMention("Bob", "Person", resolvedId = "bob-1")),
            )
            repository.save(existing)

            val newProp = createProposition(
                "Alice is an engineer",
                mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice-1")),
            )

            val result = reviser.retrieveAndFastPath(newProp, repository)

            // Filter disabled — should pass through to PendingClassification
            assertTrue(result is PendingClassification, "Expected PendingClassification but got ${result::class.simpleName}")
        }
    }

    @Nested
    inner class CanonicalTextDedupTests {

        private lateinit var repository: TestPropositionRepository

        @BeforeEach
        fun setup() {
            // Score below similarity threshold (0.5) so vector search returns empty
            repository = TestPropositionRepository(defaultScore = 0.3)
        }

        @Test
        fun `exact duplicate is merged even when vector search returns empty`() {
            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )

            val newProp = createProposition("Alice is a software engineer", confidence = 0.8)
            val result = reviser.retrieveAndFastPath(newProp, repository)

            assertTrue(result is RevisionResult.Merged, "Expected Merged but got ${result::class.simpleName}")
            val merged = result as RevisionResult.Merged
            assertEquals(existing.id, merged.original.id)
            assertTrue(merged.revised.confidence > existing.confidence)
        }

        @Test
        fun `canonical match ignores punctuation and case`() {
            val existing = createProposition("Alice is a software engineer.", confidence = 0.7)
            repository.save(existing)

            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )

            val newProp = createProposition("Alice is a Software Engineer", confidence = 0.8)
            val result = reviser.retrieveAndFastPath(newProp, repository)

            assertTrue(result is RevisionResult.Merged, "Expected Merged but got ${result::class.simpleName}")
        }

        @Test
        fun `different text is not merged when vector search is empty`() {
            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )

            val newProp = createProposition("Bob is a designer", confidence = 0.8)
            val result = reviser.retrieveAndFastPath(newProp, repository)

            assertTrue(result is RevisionResult.New, "Expected New but got ${result::class.simpleName}")
        }

        @Test
        fun `canonical match only checks same context`() {
            val existing = createProposition("Alice is a software engineer", confidence = 0.7)
            repository.save(existing)

            val reviser = LlmPropositionReviser(
                llmOptions = io.mockk.mockk(),
                ai = io.mockk.mockk(),
            )

            // Different context
            val newProp = Proposition(
                contextId = com.embabel.agent.core.ContextId("other-context"),
                text = "Alice is a software engineer",
                mentions = emptyList(),
                confidence = 0.8,
            )
            val result = reviser.retrieveAndFastPath(newProp, repository)

            assertTrue(result is RevisionResult.New, "Expected New for different context but got ${result::class.simpleName}")
        }
    }

    @Nested
    inner class BatchClassificationTests {

        @Test
        fun `batch response data classes are properly structured`() {
            val item = ClassificationItem(
                propositionId = "test-id",
                relation = "IDENTICAL",
                similarity = 0.95,
                reasoning = "Same fact"
            )
            val propClassifications = PropositionClassifications(
                propositionIndex = 0,
                classifications = listOf(item),
            )
            val response = BatchClassificationResponse(
                propositions = listOf(propClassifications),
            )

            assertEquals(1, response.propositions.size)
            assertEquals(0, response.propositions[0].propositionIndex)
            assertEquals(1, response.propositions[0].classifications.size)
            assertEquals("test-id", response.propositions[0].classifications[0].propositionId)
        }

        @Test
        fun `batch reviseAll maps results back in order`() {
            val repository = TestPropositionRepository(defaultScore = 0.7)
            val reviser = TestPropositionReviser(autoMergeThreshold = 1.1) // disable auto-merge

            // Add some existing propositions
            val existing1 = createProposition("Alice is an engineer", confidence = 0.7)
            val existing2 = createProposition("Bob is a designer", confidence = 0.6)
            repository.save(existing1)
            repository.save(existing2)

            // Configure batch classification results
            reviser.batchClassifications = mapOf(
                0 to listOf(
                    ClassifiedProposition(existing1, PropositionRelation.IDENTICAL, 0.95, "Same")
                ),
                1 to listOf(
                    ClassifiedProposition(existing2, PropositionRelation.SIMILAR, 0.75, "Related")
                ),
            )

            val newProps = listOf(
                createProposition("Alice works as an engineer", confidence = 0.8),
                createProposition("Bob works in design", confidence = 0.8),
            )

            val results = reviser.reviseAll(newProps, repository)

            assertEquals(2, results.size)
            assertTrue(results[0] is RevisionResult.Merged, "First should be merged (IDENTICAL)")
            assertTrue(results[1] is RevisionResult.Reinforced, "Second should be reinforced (SIMILAR)")
        }

        @Test
        fun `batch with no candidates returns new`() {
            val repository = TestPropositionRepository(defaultScore = 0.7)
            val reviser = TestPropositionReviser(autoMergeThreshold = 1.1)

            // No existing propositions -> all new
            val props = listOf(
                createProposition("Alice is an engineer"),
                createProposition("Bob is a designer"),
                createProposition("Charlie is a manager"),
            )

            val results = reviser.reviseAll(props, repository)

            assertEquals(3, results.size)
            assertTrue(results.all { it is RevisionResult.New })
        }

        @Test
        fun `mixed batch with new and classification results`() {
            // First prop has no candidates (nothing in repo), second has candidates for classification
            val repository = TestPropositionRepository(defaultScore = 0.7)
            val reviser = TestPropositionReviser(autoMergeThreshold = 1.1) // disable auto-merge

            val existing = createProposition("Bob is a designer", confidence = 0.6)
            repository.save(existing)

            // First new prop: after save, repo is searched but since we add props sequentially
            // the reviseAll will find "Bob is a designer" for both queries.
            // Use batch classifications: index 0 = first prop, index 1 = second prop
            reviser.batchClassifications = mapOf(
                0 to listOf(
                    ClassifiedProposition(existing, PropositionRelation.UNRELATED, 0.2, "Different topic")
                ),
                1 to listOf(
                    ClassifiedProposition(existing, PropositionRelation.SIMILAR, 0.75, "Related")
                ),
            )

            val newProps = listOf(
                createProposition("Alice is an engineer", confidence = 0.8),
                createProposition("Bob works in design", confidence = 0.8),
            )

            val results = reviser.reviseAll(newProps, repository)

            assertEquals(2, results.size)
            // First: classified as UNRELATED → treated as new
            assertTrue(results[0] is RevisionResult.New, "First should be new (UNRELATED)")
            // Second: classified as SIMILAR → reinforced
            assertTrue(results[1] is RevisionResult.Reinforced, "Second should be reinforced (SIMILAR)")
        }
    }

    private fun createProposition(
        text: String,
        confidence: Double = 0.8,
        decay: Double = 0.1,
        mentions: List<EntityMention> = emptyList(),
    ) = Proposition(
        contextId = testContextId,
        text = text,
        mentions = mentions,
        confidence = confidence,
        decay = decay,
    )
}

/**
 * Simple test implementation of PropositionRepository that doesn't require embeddings.
 * @param defaultScore Default similarity score returned for all propositions
 */
class TestPropositionRepository(
    private val defaultScore: Double = 0.8,
) : PropositionRepository {
    private val propositions = ConcurrentHashMap<String, Proposition>()

    /** Per-proposition-text score overrides (matched against stored proposition text) */
    val scoreOverrides = mutableMapOf<String, Double>()

    override fun save(proposition: Proposition): Proposition {
        propositions[proposition.id] = proposition
        return proposition
    }

    override val luceneSyntaxNotes: String
        get() = "not supported"

    override fun findById(id: String): Proposition? = propositions[id]

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        propositions.values.filter { prop ->
            prop.mentions.any { it.resolvedId == entityIdentifier.id }
        }

    override fun findSimilar(request: TextSimilaritySearchRequest): List<Proposition> =
        propositions.values.toList().take(request.topK)

    override fun findSimilarWithScores(
        request: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> =
        propositions.values
            .map { SimilarityResult(match = it, score = scoreOverrides[it.text] ?: defaultScore) }
            .filter { it.score >= request.similarityThreshold }
            .sortedByDescending { it.score }
            .take(request.topK)

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        propositions.values.filter { it.status == status }

    override fun findByGrounding(chunkId: String): List<Proposition> =
        propositions.values.filter { chunkId in it.grounding }

    override fun findByMinLevel(minLevel: Int): List<Proposition> =
        propositions.values.filter { it.level >= minLevel }

    override fun findByContextId(contextId: com.embabel.agent.core.ContextId): List<Proposition> =
        propositions.values.filter { it.contextId == contextId }

    override fun findAll(): List<Proposition> = propositions.values.toList()

    override fun delete(id: String): Boolean = propositions.remove(id) != null

    override fun count(): Int = propositions.size

    fun clear() = propositions.clear()
}

/**
 * Test implementation of PropositionReviser with configurable classification behavior,
 * auto-merge support, and batch classification tracking.
 */
class TestPropositionReviser(
    private val autoMergeThreshold: Double = 1.1,
) : PropositionReviser {
    var nextClassification: List<ClassifiedProposition> = emptyList()
    var classifyCallCount: Int = 0

    /** Per-batch-index classification results for batch tests */
    var batchClassifications: Map<Int, List<ClassifiedProposition>> = emptyMap()
    private var batchIndex: Int = 0

    override fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult {
        // Get similar propositions from repository
        val similar = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(
                query = newProposition.text,
                topK = 5,
                similarityThreshold = 0.5,
            )
        ).filter { it.match.status == PropositionStatus.ACTIVE }

        if (similar.isEmpty()) {
            repository.save(newProposition)
            return RevisionResult.New(newProposition)
        }

        // Auto-merge fast path
        val topCandidate = similar.first()
        if (topCandidate.score >= autoMergeThreshold) {
            val original = repository.findById(topCandidate.match.id) ?: topCandidate.match
            val merged = mergePropositions(original, newProposition)
            repository.save(merged)
            return RevisionResult.Merged(original, merged)
        }

        // Use configured classification or default to empty
        val classified = if (nextClassification.isNotEmpty()) {
            classifyCallCount++
            nextClassification.also { nextClassification = emptyList() }
        } else {
            classifyCallCount++
            classify(newProposition, similar.map { it.match })
        }

        val identical = classified.find { it.relation == PropositionRelation.IDENTICAL }
        val contradictory = classified.find { it.relation == PropositionRelation.CONTRADICTORY }
        val mostSimilar = classified
            .filter { it.relation == PropositionRelation.SIMILAR }
            .maxByOrNull { it.similarity }

        return when {
            identical != null -> {
                val original = repository.findById(identical.proposition.id) ?: identical.proposition
                val merged = mergePropositions(original, newProposition)
                repository.save(merged)
                RevisionResult.Merged(original, merged)
            }

            contradictory != null -> {
                val original = repository.findById(contradictory.proposition.id) ?: contradictory.proposition
                val reducedConfidence = (original.confidence * 0.3).coerceAtLeast(0.05)
                val acceleratedDecay = (original.decay + 0.15).coerceAtMost(1.0)
                val contradicted = original
                    .withConfidence(reducedConfidence)
                    .withStatus(PropositionStatus.CONTRADICTED)
                    .copy(decay = acceleratedDecay)
                repository.save(contradicted)
                repository.save(newProposition)
                RevisionResult.Contradicted(contradicted, newProposition)
            }

            mostSimilar != null -> {
                val original = repository.findById(mostSimilar.proposition.id) ?: mostSimilar.proposition
                val revised = reinforceProposition(original, newProposition)
                repository.save(revised)
                RevisionResult.Reinforced(original, revised)
            }

            else -> {
                repository.save(newProposition)
                RevisionResult.New(newProposition)
            }
        }
    }

    override fun reviseAll(
        propositions: List<Proposition>,
        repository: PropositionRepository,
    ): List<RevisionResult> {
        // If batch classifications are configured, use the batch flow
        if (batchClassifications.isNotEmpty()) {
            batchIndex = 0
            val results = mutableListOf<RevisionResult>()
            for (prop in propositions) {
                val similar = repository.findSimilarWithScores(
                    TextSimilaritySearchRequest(
                        query = prop.text,
                        topK = 5,
                        similarityThreshold = 0.5,
                    )
                ).filter { it.match.status == PropositionStatus.ACTIVE }

                if (similar.isEmpty()) {
                    repository.save(prop)
                    results.add(RevisionResult.New(prop))
                    batchIndex++
                    continue
                }

                // Auto-merge fast path
                val topCandidate = similar.first()
                if (topCandidate.score >= autoMergeThreshold) {
                    val original = repository.findById(topCandidate.match.id) ?: topCandidate.match
                    val merged = mergePropositions(original, prop)
                    repository.save(merged)
                    results.add(RevisionResult.Merged(original, merged))
                    batchIndex++
                    continue
                }

                // Use batch classification for this index
                val classified = batchClassifications[batchIndex] ?: emptyList()
                batchIndex++

                val identical = classified.find { it.relation == PropositionRelation.IDENTICAL }
                val contradictory = classified.find { it.relation == PropositionRelation.CONTRADICTORY }
                val mostSimilar = classified
                    .filter { it.relation == PropositionRelation.SIMILAR }
                    .maxByOrNull { it.similarity }

                results.add(
                    when {
                        identical != null -> {
                            val original = repository.findById(identical.proposition.id) ?: identical.proposition
                            val merged = mergePropositions(original, prop)
                            repository.save(merged)
                            RevisionResult.Merged(original, merged)
                        }
                        contradictory != null -> {
                            val original = repository.findById(contradictory.proposition.id) ?: contradictory.proposition
                            val reducedConfidence = (original.confidence * 0.3).coerceAtLeast(0.05)
                            val acceleratedDecay = (original.decay + 0.15).coerceAtMost(1.0)
                            val contradicted = original.withConfidence(reducedConfidence).withStatus(PropositionStatus.CONTRADICTED)
                                .copy(decay = acceleratedDecay)
                            repository.save(contradicted)
                            repository.save(prop)
                            RevisionResult.Contradicted(contradicted, prop)
                        }
                        mostSimilar != null -> {
                            val original = repository.findById(mostSimilar.proposition.id) ?: mostSimilar.proposition
                            val revised = reinforceProposition(original, prop)
                            repository.save(revised)
                            RevisionResult.Reinforced(original, revised)
                        }
                        else -> {
                            repository.save(prop)
                            RevisionResult.New(prop)
                        }
                    }
                )
            }
            return results
        }

        // Default: delegate to per-proposition revise
        return propositions.map { revise(it, repository) }
    }

    override fun classify(
        newProposition: Proposition,
        candidates: List<Proposition>,
    ): List<ClassifiedProposition> {
        // Default: classify all as unrelated
        return candidates.map {
            ClassifiedProposition(
                proposition = it,
                relation = PropositionRelation.UNRELATED,
                similarity = 0.3,
                reasoning = null
            )
        }
    }

    private fun mergePropositions(existing: Proposition, new: Proposition): Proposition {
        val boostedConfidence = (existing.confidence + new.confidence * 0.3).coerceAtMost(0.99)
        val slowedDecay = (existing.decay * 0.7).coerceAtLeast(0.0)
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            decay = slowedDecay,
            grounding = combinedGrounding,
            reinforceCount = existing.reinforceCount + 1,
        )
    }

    private fun reinforceProposition(existing: Proposition, new: Proposition): Proposition {
        val boostedConfidence = (existing.confidence + new.confidence * 0.1).coerceAtMost(0.95)
        val slowedDecay = (existing.decay * 0.85).coerceAtLeast(0.0)
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            decay = slowedDecay,
            grounding = combinedGrounding,
            reinforceCount = existing.reinforceCount + 1,
        )
    }
}
