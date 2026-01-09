package com.embabel.dice.proposition.revision

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
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

    private fun createProposition(
        text: String,
        confidence: Double = 0.8,
        decay: Double = 0.1,
    ) = Proposition(
        contextId = testContextId,
        text = text,
        mentions = emptyList(),
        confidence = confidence,
        decay = decay,
    )
}

/**
 * Simple test implementation of PropositionRepository that doesn't require embeddings.
 */
class TestPropositionRepository : PropositionRepository {
    private val propositions = ConcurrentHashMap<String, Proposition>()

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
            .map { SimilarityResult(match = it, score = 0.8) }
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
 * Test implementation of PropositionReviser with configurable classification behavior.
 */
class TestPropositionReviser : PropositionReviser {
    var nextClassification: List<ClassifiedProposition> = emptyList()

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

        // Use configured classification or default to empty
        val classified = if (nextClassification.isNotEmpty()) {
            nextClassification.also { nextClassification = emptyList() }
        } else {
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
                val contradicted = original
                    .withConfidence(reducedConfidence)
                    .withStatus(PropositionStatus.CONTRADICTED)
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
        val avgDecay = (existing.decay + new.decay) / 2
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            decay = avgDecay,
            grounding = combinedGrounding,
        )
    }

    private fun reinforceProposition(existing: Proposition, new: Proposition): Proposition {
        val boostedConfidence = (existing.confidence + new.confidence * 0.1).coerceAtMost(0.95)
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            grounding = combinedGrounding,
        )
    }
}

