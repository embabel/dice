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
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What `persistAndProject` hands to projection, grounding and lineage.
 *
 * The headline is that a run does not change any of it. Projection and grounding run over the
 * propositions the repository returned on every call, so a projection of a deduplicated proposition
 * targets the id that is stored whether or not anyone asked for an audit trail. A run adds the
 * lineage write and nothing else, and the byte-identical case below is what holds that line: the
 * same extraction with and without a run must reach the repository the same way.
 *
 * These tests deliberately pin the *opposite* of what the first cut of this slice did, where the run
 * chose between canonical and pre-save ids. That made an audit setting decide whether the graph was
 * written correctly. See `persistAndProject`.
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
        val entityRepository: NamedEntityDataRepository,
    ) {
        /** Whether structural wiring reached the entity repository. */
        val structurallyWired: Boolean
            get() = runCatching {
                verify { entityRepository.mergeRelationship(any(), any(), any()) }
            }.isSuccess
    }

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
        policy: LineageFailurePolicy = LineageFailurePolicy.DEFAULT,
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
        ).withRunLineage(linkStore, policy)
        return Harness(repository, extraction, projected, grounded, linkStore, entityRepository)
    }

    private fun IncrementalPropositionExtraction.remember(currentRun: ExtractionRunRef?) =
        rememberText(
            "Alice likes coffee",
            "source-1",
            user(),
            emptyList(),
            null,
            null,
            ExtractionRequest(currentRun = currentRun),
        )

    // ---- canonical persistence is the only path ----

    @Test
    fun `with no run the projection of a deduplicated proposition targets the canonical id`() {
        // The discriminating case for "canonical persistence is the only path". Extraction minted
        // "minted"; the deduplicating store answered with "canonical" and never stored "minted".
        // Projection and grounding must target what the store holds, with no run anywhere in sight.
        //
        // This is the behavioural fix. Before it, a no-run extraction projected and grounded against
        // "minted" — an id the store does not hold — so the edges pointed at a node that was never
        // written.
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = null,
        )

        harness.extraction.remember(currentRun = null)

        assertThat(harness.projected.single().map { it.id })
            .describedAs("projection targets the stored id, with no run involved")
            .containsExactly("canonical")
        assertThat(harness.grounded.single().map { it.id })
            .describedAs("grounding targets the stored id too")
            .containsExactly("canonical")
        assertThat(harness.repository.findById("minted"))
            .describedAs("the minted id was never stored, which is why projecting it was wrong")
            .isNull()
    }

    @Test
    fun `with no run and a link store present nothing is linked`() {
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
        )

        harness.extraction.remember(currentRun = null)

        assertThat(links.linked)
            .describedAs("no run, no lineage: the store is bound and never touched")
            .isEmpty()
        assertThat(harness.projected.single().map { it.id }).containsExactly("canonical")
    }

    @Test
    fun `a run adds the lineage write and changes nothing else about persistence`() {
        // The other half of the operator rule. The no-run and run-present extractions are run over
        // identical inputs, and everything except the lineage write has to match.
        fun run(currentRun: ExtractionRunRef?): Harness {
            val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
            val harness = harness(
                stored = proposition("Alice likes coffee", id = "canonical"),
                extracted = proposition("Alice likes coffee", id = "minted"),
                linkStore = links,
            )
            harness.extraction.remember(currentRun = currentRun)
            return harness
        }

        val without = run(currentRun = null)
        val with = run(currentRun = runRef)

        // Compared by id, because a `Proposition` carries creation and revision timestamps taken
        // from the wall clock, so two runs of the same extraction differ in fields that have nothing
        // to do with the run. The ids are what says which claims were
        // wired, which is the thing a run must not change.
        fun ids(passes: List<List<Proposition>>) = passes.map { pass -> pass.map { it.id } }

        assertThat(ids(with.projected))
            .describedAs("a run does not change what projection is handed")
            .isEqualTo(ids(without.projected))
        assertThat(ids(with.grounded))
            .describedAs("a run does not change what grounding is handed")
            .isEqualTo(ids(without.grounded))
        assertThat(with.repository.findAll().map { it.id })
            .describedAs("the same claims are stored either way")
            .isEqualTo(without.repository.findAll().map { it.id })

        // The one and only difference.
        assertThat(without.linkStore!!.linked).isEmpty()
        assertThat(with.linkStore!!.linked.single().second).containsExactly("canonical")
    }

    // ---- the run-present path consumes canonical results ----

    @Test
    fun `the projection of a deduplicated proposition targets the canonical id`() {
        // The same claim as the no-run case above, with a run present. Both hold, which is the point.
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

    // ---- attribution fails loud by policy ----

    @Test
    fun `a run with no link store bound fails the extraction under STRICT`() {
        // The host asked for attribution and nothing can record it. That is a wiring mistake, true
        // of every call this extractor will make, and the default policy says so out loud. A
        // success with a silent gap in the audit is the outcome being prevented.
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = null,
            policy = LineageFailurePolicy.STRICT,
        )

        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(LineageNotRecordedException::class.java)
            .hasMessageContaining("no PropositionRunLinkStore is bound")
            .hasMessageContaining(runRef.runId)
    }

    @Test
    fun `STRICT is what an unqualified binding gets`() {
        // The default is the whole point of the policy, so it is pinned here; the enum's
        // declaration order does not get to decide it quietly.
        assertThat(LineageFailurePolicy.DEFAULT).isEqualTo(LineageFailurePolicy.STRICT)

        val extraction = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = null,
        ).extraction

        assertThatThrownBy { extraction.remember(currentRun = runRef) }
            .describedAs("harness bound no policy, so the default had to be the strict one")
            .isInstanceOf(LineageNotRecordedException::class.java)
    }

    @Test
    fun `a run with no link store records nothing and carries on under LENIENT`() {
        val harness = harness(
            stored = proposition("Alice likes coffee", id = "canonical"),
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = null,
            policy = LineageFailurePolicy.LENIENT,
        )

        harness.extraction.remember(currentRun = runRef)

        assertThat(harness.projected.single().map { it.id })
            .describedAs("the extraction completed, on the canonical ids like every other call")
            .containsExactly("canonical")
    }

    @Test
    fun `a link write that throws fails the extraction under STRICT`() {
        val links = RecordingLinkStore(
            runsPresent = emptySet(),
            failWith = IllegalStateException("link store is down"),
        )
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            policy = LineageFailurePolicy.STRICT,
        )

        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(LineageNotRecordedException::class.java)
            .hasMessageContaining("could not attribute")
            .hasRootCauseMessage("link store is down")

        // The claim was saved before lineage was attempted, and with no ambient transaction it
        // stands. The caller learns that a stored claim is unattributed, which is the point.
        assertThat(harness.repository.findById("minted")).isNotNull()
    }

    @Test
    fun `a run whose link store rejects the scope fails the extraction under STRICT`() {
        // A scope rejection is a pipeline bug. It has to be at least as loud as an outage.
        val links = RecordingLinkStore(runsPresent = emptySet())
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            policy = LineageFailurePolicy.STRICT,
        )

        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(LineageNotRecordedException::class.java)
            .hasCauseInstanceOf(ExtractionRunNotFoundException::class.java)
    }

    @Test
    fun `a structural wiring throw means lineage is never attempted`() {
        // Lineage runs last, so a pass that throws before it means attribution is never attempted.
        // The claims are durable and the graph around them is incomplete, which is the honest report
        // of what happened: the extraction failed partway, and nothing claims a run produced it.
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
        assertThat(links.linked)
            .describedAs("the pipeline failed before attribution, so no run claims this work")
            .isEmpty()
        assertThat(harness.projected)
            .describedAs("projection never ran either")
            .isEmpty()
    }

    @Test
    fun `a throwing projector stops the extraction before lineage`() {
        val links = RecordingLinkStore(runsPresent = setOf(ExtractionRunKey(tenant, runRef)))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            projectionFails = true,
        )

        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("projector is down")

        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(harness.grounded)
            .describedAs("grounding never ran")
            .isEmpty()
        assertThat(links.linked)
            .describedAs("and lineage, which comes after grounding, was never reached")
            .isEmpty()
    }

    @Test
    fun `a grounding failure stops the extraction before lineage`() {
        // Grounding is the last of the three wiring passes, and lineage sits behind it. A grounding
        // failure therefore reaches the caller with the claims stored, the structural edges written
        // and the projection done, and no attribution.
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
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
        assertThat(harness.grounded.single().map { it.id })
            .describedAs("grounding ran and threw, so it was reached")
            .containsExactly("minted")
        assertThat(links.linked).isEmpty()
    }

    // ---- the end state a lineage failure leaves behind ----

    @Test
    fun `a STRICT lineage failure leaves a complete extraction with no run edge`() {
        // The discriminating test for where lineage sits. Every pass succeeds; only the link write
        // fails. Because lineage is last, the state the caller is left with is a whole extraction —
        // claims saved, structural edges wired, projection done, grounding done — missing exactly
        // one thing, the PRODUCED_BY_RUN edge, which is what the raised failure is about.
        //
        // Ordering lineage earlier would make this a partial state instead: the claims would be
        // saved and projection and grounding would be skipped by the raise, with nothing declaring
        // that.
        val links = RecordingLinkStore(
            runsPresent = emptySet(),
            failWith = IllegalStateException("link store is down"),
        )
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            policy = LineageFailurePolicy.STRICT,
        )

        // The operation is reported as failed.
        assertThatThrownBy { harness.extraction.remember(currentRun = runRef) }
            .isInstanceOf(LineageNotRecordedException::class.java)
            .hasRootCauseMessage("link store is down")

        // ...and everything the extraction was going to do is done.
        assertThat(harness.repository.findById("minted"))
            .describedAs("claims persisted")
            .isNotNull()
        assertThat(harness.structurallyWired)
            .describedAs("structural edges wired")
            .isTrue()
        assertThat(harness.projected.single().map { it.id })
            .describedAs("projection ran over the canonical ids")
            .containsExactly("minted")
        assertThat(harness.grounded.single().map { it.id })
            .describedAs("grounding ran over the canonical ids")
            .containsExactly("minted")
        assertThat(links.linked)
            .describedAs("and the one missing thing is the PRODUCED_BY_RUN edge")
            .isEmpty()
    }

    @Test
    fun `a LENIENT lineage failure reaches the same end state and reports success`() {
        val links = RecordingLinkStore(
            runsPresent = emptySet(),
            failWith = IllegalStateException("link store is down"),
        )
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            policy = LineageFailurePolicy.LENIENT,
        )

        // Reported as success.
        harness.extraction.remember(currentRun = runRef)

        // Same end state as the STRICT case above, asserted the same way so the two are comparable.
        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(harness.structurallyWired).isTrue()
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
        assertThat(harness.grounded.single().map { it.id }).containsExactly("minted")
        assertThat(links.linked).isEmpty()
    }

    @Test
    fun `lineage that cannot be written does not fail the extraction under LENIENT`() {
        // The documented downgrade. A host that has decided the claims outweigh their audit trail
        // says so in configuration and gets the old best-effort behaviour, explicitly.
        val links = RecordingLinkStore(runsPresent = emptySet(), failWith = IllegalStateException("no store"))
        val harness = harness(
            stored = null,
            extracted = proposition("Alice likes coffee", id = "minted"),
            linkStore = links,
            policy = LineageFailurePolicy.LENIENT,
        )

        harness.extraction.remember(currentRun = runRef)

        assertThat(harness.repository.findById("minted")).isNotNull()
        assertThat(harness.projected.single().map { it.id }).containsExactly("minted")
        assertThat(links.linked).isEmpty()
    }
}
