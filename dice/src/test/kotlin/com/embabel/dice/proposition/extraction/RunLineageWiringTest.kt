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
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.projection.graph.RelationshipPersistenceResult
import com.embabel.dice.projection.grounding.GroundingWiringService
import com.embabel.dice.projection.grounding.GroundingWiringService.GroundingReport
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What `persistAndProject` hands to projection, grounding and lineage, and how that depends on
 * whether the analysis carries an extraction run.
 *
 * The switch matters because the two answers differ exactly when a store deduplicates. With a run,
 * everything downstream of the save runs over the propositions the repository returned, so a
 * projection of a deduplicated proposition targets the id that is stored. With no run, the
 * pre-save propositions are used, exactly as before — and these tests pin that too, because
 * "unchanged for legacy callers" is a claim that has to fail if it stops being true.
 *
 * **The survival cases below run with no ambient transaction, and that is the shape they speak
 * for.** The repository here commits each save as it makes it, so "the claim is still there after
 * the pass failed" means what it says. A host that wraps extraction in its own `@Transactional` has
 * a different shape: nothing has committed, the claims and their lineage share that transaction's
 * fate, and a failure propagating out of `persistAndProject` rolls all of it back. What these tests
 * pin for that host is narrower but still real — the *ordering*, so lineage is attempted before any
 * fallible pass rather than after all of them. `DrivineRunLineageIntegrationTest` covers the ambient
 * shape, including the server-side failure no catch can survive.
 */
class RunLineageWiringTest {

    private val tenant = ContextId("wiring-tenant")
    private val runRef = ExtractionRunRef("wiring-run")
    private val startedAt: Instant = Instant.parse("2026-08-31T10:15:30Z")

    /** `DrivinePropositionRepository`'s dedup rule, in memory. */
    private class DeduplicatingRepository(
        private val delegate: PropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {

        override fun save(proposition: Proposition): Proposition {
            val existing = delegate.findAll().firstOrNull {
                it.contextId == proposition.contextId &&
                    it.text == proposition.text &&
                    it.id != proposition.id
            }
            val isUpdate = delegate.findById(proposition.id) != null
            return if (existing != null && !isUpdate) existing else delegate.save(proposition)
        }

        override fun saveAll(propositions: Collection<Proposition>) {
            propositions.forEach { save(it) }
        }

        override fun saveAllReturningCanonical(
            propositions: Collection<Proposition>,
        ): PropositionPersistenceResult {
            val inputs = propositions.toList()
            return PropositionPersistenceResult.of(inputs, inputs.map { save(it) })
        }
    }

    private fun proposition(text: String, id: String) = Proposition(
        id = id,
        contextId = tenant,
        text = text,
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "e-alice", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    private fun user(): NamedEntity = mockk<NamedEntity>(relaxed = true).also {
        every { it.id } returns tenant.value
        every { it.name } returns "Alice"
    }

    /**
     * A wired-up extractor plus the two capture points: what projection was asked to project, and
     * what lineage was asked to link.
     */
    private class Harness(
        val repository: DeduplicatingRepository,
        val extraction: IncrementalPropositionExtraction,
        val projected: MutableList<List<Proposition>>,
        val grounded: MutableList<List<Proposition>>,
        val linkStore: RecordingLinkStore?,
    )

    private class RecordingLinkStore(
        private val runsPresent: Set<ExtractionRunKey>,
        private val failWith: RuntimeException? = null,
    ) : PropositionRunLinkStore {

        val linked = mutableListOf<Pair<ExtractionRunKey, List<String>>>()

        override fun link(key: ExtractionRunKey, propositionIds: Collection<String>): Int {
            failWith?.let { throw it }
            if (key !in runsPresent) throw ExtractionRunNotFoundException(key)
            linked += key to propositionIds.toList()
            return propositionIds.distinct().size
        }

        override fun runsOf(contextIdValue: String, propositionId: String, limit: Int) =
            linked.filter { propositionId in it.second }.map { it.first.runRef }

        override fun propositionsOf(key: ExtractionRunKey, limit: Int) =
            linked.filter { it.first == key }.flatMap { it.second }
    }

    private fun harness(
        stored: Proposition?,
        extracted: Proposition,
        linkStore: RecordingLinkStore?,
        projectionFails: Boolean = false,
        structuralWiringFails: Boolean = false,
        groundingFails: Boolean = false,
    ): Harness {
        val repository = DeduplicatingRepository()
        stored?.let { repository.save(it) }

        val pipeline = mockk<PropositionPipeline>()
        every { pipeline.processOnce(any(), any(), any(), any(), any(), any()) } returns
            ChunkPropositionResult.Success(
                chunkId = "chunk-1",
                suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
                entityResolutions = Resolutions<SuggestedEntityResolution>(setOf("chunk-1"), emptyList()),
                propositions = listOf(extracted),
            )

        val projected = mutableListOf<List<Proposition>>()
        val projection = mockk<GraphProjectionService>()
        val captured = slot<List<Proposition>>()
        every { projection.projectAndPersist(capture(captured)) } answers {
            projected += captured.captured
            if (projectionFails) throw IllegalStateException("projector is down")
            ProjectionResults<ProjectedRelationship>(emptyList()) to
                RelationshipPersistenceResult(persistedCount = 0, failedCount = 0)
        }

        // A real grounding service is wired in, so what it is handed is asserted rather than
        // assumed. With none supplied the grounding half of "consumes the canonical results" would
        // be untested — the call is null-safe and would simply not happen.
        val grounded = mutableListOf<List<Proposition>>()
        val grounding = mockk<GroundingWiringService>()
        val groundingCaptured = slot<List<Proposition>>()
        every { grounding.wire(capture(groundingCaptured)) } answers {
            grounded += groundingCaptured.captured
            if (groundingFails) throw IllegalStateException("grounding is down")
            GroundingReport.EMPTY
        }

        // Structural wiring runs through mergeRelationship, so this is where a failure in it comes
        // from. It is the first fallible thing after the save, which is what makes it the test for
        // where lineage sits.
        val entityRepository = mockk<NamedEntityDataRepository>(relaxed = true)
        if (structuralWiringFails) {
            every { entityRepository.mergeRelationship(any(), any(), any()) } throws
                IllegalStateException("structural wiring is down")
        }

        val extraction = IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = mockk<ChunkHistoryStore>(relaxed = true),
            dataDictionary = DataDictionary.fromClasses("wiring"),
            relations = Relations.empty(),
            propositionRepository = repository,
            entityRepository = entityRepository,
            entityResolver = mockk<EntityResolver>(relaxed = true),
            graphProjectionService = projection,
            properties = PropositionExtractionProperties(),
            groundingWiringService = grounding,
        ).withRunLineage(linkStore)
        return Harness(repository, extraction, projected, grounded, linkStore)
    }

    private fun IncrementalPropositionExtraction.remember(currentRun: ExtractionRunRef?) =
        rememberText(
            "Alice likes coffee",
            "source-1",
            user(),
            emptyList(),
            null,
            null,
            null,
            currentRun,
        )

    // ---- the legacy path is unchanged ----

    @Test
    fun `with no run the pre-save propositions are projected, exactly as before`() {
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = null,
        )

        harness.extraction.remember(currentRun = null)

        // The minted id, which the store does not hold. Wrong, and the same wrong it has always
        // been: a host that never asked for extraction runs gets the behaviour it already has.
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
        assertThat(harness.grounded.single().map { it.id }).containsExactly("minted")
        assertThat(harness.repository.findById("minted")).isNull()
    }

    @Test
    fun `with no run and a link store present nothing is linked`() {
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
        )

        harness.extraction.remember(currentRun = null)

        assertThat(links.linked).isEmpty()
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
    }

    // ---- the run-present path consumes canonical results ----

    @Test
    fun `the projection of a deduplicated proposition targets the canonical id`() {
        // The headline of the seam. Under a run, projection is handed what the store holds.
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef))),
        )

        harness.extraction.remember(currentRun = runRef)

        assertThat(harness.projected.single().map { it.id }).containsExactly("canonical")
        assertThat(harness.grounded.single().map { it.id })
            .describedAs("grounding gets the canonical propositions too, not just projection")
            .containsExactly("canonical")
        assertThat(harness.repository.findById("canonical")).isNotNull()
        assertThat(harness.repository.count()).isEqualTo(1)
    }

    @Test
    fun `a run-present flow links the canonical ids to its run`() {
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
        )

        harness.extraction.remember(currentRun = runRef)

        assertThat(links.linked).hasSize(1)
        val (key, ids) = links.linked.single()
        assertThat(key).isEqualTo(ExtractionRunKey(tenant, runRef))
        assertThat(ids).containsExactly("canonical")
    }

    @Test
    fun `a run with no link store still takes the canonical path`() {
        // The switch is the run, not the store. An analysis attributed to a run gets correct edges
        // whether or not anyone is recording lineage.
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = null,
        )

        harness.extraction.remember(currentRun = runRef)

        assertThat(harness.projected.single().map { it.id }).containsExactly("canonical")
    }

    @Test
    fun `lineage is recorded even when structural wiring throws`() {
        // Structural wiring is the *first* fallible thing after the save, and it used to sit inside
        // the same call that did the saving — so lineage could not be attempted until it returned.
        // A throwing mergeRelationship therefore left durable claims with no record of the run that
        // produced them, which is the same hole the projector case closed one step later.
        //
        // "Immediately after the propositions are durable" has to mean before *any* fallible
        // wiring, not before the fallible wiring that happened to be easy to move.
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            structuralWiringFails = true,
        )

        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("structural wiring is down")

        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(links.linked.single().second)
            .describedAs("the claim is durable, so its attribution is too")
            .containsExactly("minted")
        assertThat(harness.projected)
            .describedAs("projection never ran, which is what places lineage before the wiring")
            .isEmpty()
    }

    @Test
    fun `lineage is recorded even when projection throws`() {
        // Lineage runs directly behind the save, not at the end. Running it last meant a throwing
        // projector left durable claims with no record of the run that produced them — precisely
        // when something has gone wrong and the audit is worth most. The claims are already stored
        // when attribution happens, so nothing downstream can take it away.
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            projectionFails = true,
        )

        // The projector's failure still surfaces; it is not being swallowed to make this pass.
        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("projector is down")

        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(links.linked.single().second)
            .describedAs("the claim is durable, so its attribution is too")
            .containsExactly("minted")
        assertThat(harness.grounded)
            .describedAs("grounding never ran, which is what makes the ordering observable")
            .isEmpty()
    }

    @Test
    fun `a grounding failure leaves the claims, the links and the projection standing`() {
        // Grounding is the last pass, so unlike the structural and projection cases this one cannot
        // show lineage being rescued by ordering — everything upstream has already happened. What it
        // does pin is that being last is not the same as being safe to be vague about: the three
        // things written before it are durable and stay durable, and the failure still reaches the
        // caller rather than being swallowed because there is nothing after it to protect.
        //
        // Two reviewers disagreed about whether this test asserts anything. It asserts the terminal
        // pass's contract, which nothing else covers.
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            groundingFails = true,
        )

        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("grounding is down")

        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(links.linked.single().second).containsExactly("minted")
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
        assertThat(harness.grounded.single().map { it.id })
            .describedAs("grounding ran and threw, rather than never being reached")
            .containsExactly("minted")
    }

    @Test
    fun `lineage that cannot be written does not fail the extraction that produced it`() {
        // Lineage is an audit record written after the claims are durable. Failing it must not undo
        // them, and must not report an extraction that produced nothing.
        val links = RecordingLinkStore(runsPresent = emptySet(), failWith = IllegalStateException("no store"))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
        )

        harness.extraction.remember(currentRun = runRef)

        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
        assertThat(links.linked).isEmpty()
    }
}
