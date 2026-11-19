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
package com.embabel.dice.proposition.gate

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.extraction.PersistUnit
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that the gate applier folds gate decisions into whole revision results and hands back
 * the chunk the host should persist, following the design rulings one test group at a time.
 */
class GateApplierTest {

    private val tenant = ContextId("gate-tenant")

    // Propositions a chunk can carry. Each test picks the ones it needs.
    private val fresh = proposition("p-new", entity = "e-alice")
    private val storedA = proposition("p-stored-a", confidence = 0.6)
    private val storedARevised = storedA.copy(confidence = 0.8, reinforceCount = 1)
    private val storedAWithSecondSource = storedA.copy(confidence = 0.8, grounding = listOf("chunk-1", "chunk-2"))
    private val storedB = proposition("p-stored-b", confidence = 0.6)
    private val storedBRevised = storedB.copy(confidence = 0.7, reinforceCount = 1)
    private val contradictedOriginal = proposition("p-orig", status = PropositionStatus.CONTRADICTED, confidence = 0.2)
    private val contradictingNew = proposition("p-contra", entity = "e-bob")
    private val general = proposition("p-general", entity = "e-carol")
    private val specific = proposition("p-specific")

    // --- Ruling: whole-result verdict, one gated claim per result, per revision kind ---

    @Test
    fun `whole-result verdict admits a New result and keeps its proposition`() {
        val gated = applier(rejecting()).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        assertEquals(listOf(fresh), gated.admitted.propositionsToPersist())
        val verdict = gated.verdicts.single()
        assertTrue(verdict.isAdmitted)
        assertEquals(listOf(fresh), verdict.propositions)
        assertEquals(GateDecision.Persist, verdict.decision)
    }

    @Test
    fun `whole-result verdict rejects a New result and drops its proposition`() {
        val gated = applier(rejecting(fresh.id)).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertEquals(1, gated.rejected().size)
        assertFalse(gated.rejected().single().isAdmitted)
    }

    @Test
    fun `whole-result verdict gates the revised claim of a Merged result and admits it`() {
        val seen = mutableListOf<Proposition>()
        val merged = RevisionResult.Merged(storedA, storedARevised)

        val gated = applier(recording(seen)).apply(chunk(listOf(storedA), listOf(merged)))

        assertEquals(listOf(storedARevised), seen)
        assertEquals(listOf(storedARevised), gated.admitted.propositionsToPersist())
        assertSame(merged, gated.verdicts.single().revisionResult)
        assertEquals(listOf(storedARevised), gated.verdicts.single().propositions)
    }

    @Test
    fun `whole-result verdict rejects a Merged result and drops its revised proposition`() {
        val merged = RevisionResult.Merged(storedA, storedARevised)

        val gated = applier(rejecting(storedA.id)).apply(chunk(listOf(storedA), listOf(merged)))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertTrue(gated.admitted.revisionResults.isEmpty())
    }

    @Test
    fun `whole-result verdict gates the revised claim of a Reinforced result and admits it`() {
        val seen = mutableListOf<Proposition>()
        val reinforced = RevisionResult.Reinforced(storedA, storedARevised)

        val gated = applier(recording(seen)).apply(chunk(listOf(storedA), listOf(reinforced)))

        assertEquals(listOf(storedARevised), seen)
        assertEquals(listOf(storedARevised), gated.admitted.propositionsToPersist())
    }

    @Test
    fun `whole-result verdict rejects a Reinforced result and drops its revised proposition`() {
        val reinforced = RevisionResult.Reinforced(storedA, storedARevised)

        val gated = applier(rejecting(storedA.id)).apply(chunk(listOf(storedA), listOf(reinforced)))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertEquals(listOf(storedARevised), gated.rejected().single().propositions)
    }

    @Test
    fun `whole-result verdict admits a Contradicted result with both halves`() {
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)

        val gated = applier(rejecting()).apply(chunk(listOf(contradictingNew), listOf(contradicted)))

        assertEquals(
            setOf(contradictedOriginal, contradictingNew),
            gated.admitted.propositionsToPersist().toSet(),
        )
        assertEquals(listOf(contradictedOriginal, contradictingNew), gated.verdicts.single().propositions)
    }

    @Test
    fun `whole-result verdict rejects a Contradicted result and drops both original and new`() {
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)

        val gated = applier(rejecting(contradictingNew.id)).apply(chunk(listOf(contradictingNew), listOf(contradicted)))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertEquals(listOf(contradictedOriginal, contradictingNew), gated.rejected().single().propositions)
    }

    @Test
    fun `only Contradicted new is gated so a gate rejecting the demoted original alone does not reject the result`() {
        val seen = mutableListOf<Proposition>()
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)
        val pipeline = ExtractionGatePipeline(
            listOf(
                ExtractionGate { p, _ -> seen += p; GateEvaluation("seen", p, GateDecision.Persist) },
                // Would reject the demoted original for its lowered confidence, if it were ever asked.
                ExtractionGate { p, _ ->
                    GateEvaluation("confidence", p, if (p.confidence < 0.5) GateDecision.Reject("too weak") else GateDecision.Persist)
                },
            ),
        )

        val gated = GateApplier.of(pipeline).apply(chunk(listOf(contradictingNew), listOf(contradicted)))

        assertEquals(listOf(contradictingNew), seen)
        assertTrue(gated.verdicts.single().isAdmitted)
        assertTrue(contradictedOriginal in gated.admitted.propositionsToPersist())
    }

    @Test
    fun `whole-result verdict gates the proposition of a Generalized result and admits it`() {
        val generalized = RevisionResult.Generalized(general, listOf(specific))

        val gated = applier(rejecting()).apply(chunk(listOf(general), listOf(generalized)))

        assertEquals(listOf(general), gated.admitted.propositionsToPersist())
    }

    @Test
    fun `whole-result verdict rejects a Generalized result and drops its proposition`() {
        val generalized = RevisionResult.Generalized(general, listOf(specific))

        val gated = applier(rejecting(general.id)).apply(chunk(listOf(general), listOf(generalized)))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertTrue(gated.admitted.propositions.isEmpty())
    }

    @Test
    fun `whole-result verdict uses the pipeline's own ordering so the first non-Persist decision wins`() {
        val pipeline = ExtractionGatePipeline(
            listOf(
                gate("first", GateDecision.SkipProjection("not yet")),
                gate("second", GateDecision.Reject("too late")),
                gate("third", GateDecision.RouteToReview("never asked")),
            ),
        )

        val gated = GateApplier.of(pipeline).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        val verdict = gated.verdicts.single()
        assertEquals(GateDecision.SkipProjection("not yet"), verdict.decision)
        assertEquals(1, verdict.gated.size)
        // Reject short-circuits, so the third gate never ran.
        assertEquals(listOf("first", "second"), verdict.gated.single().evaluations.map { it.gateName })
        assertEquals(verdict.gated.single().finalDecision, verdict.decision)
    }

    @Test
    fun `whole-result verdict hands each gate the context from the default factory`() {
        val contexts = mutableListOf<GateContext>()
        val result = RevisionResult.New(fresh)
        val pipeline = ExtractionGatePipeline(
            listOf(ExtractionGate { p, c -> contexts += c; GateEvaluation("ctx", p, GateDecision.Persist) }),
        )

        GateApplier.of(pipeline).apply(chunk(listOf(fresh), listOf(result)))

        assertEquals(listOf(GateContext(revisionResult = result)), contexts)
    }

    @Test
    fun `whole-result verdict hands each gate the context a host factory builds`() {
        val contexts = mutableListOf<GateContext>()
        val result = RevisionResult.New(fresh)
        val pipeline = ExtractionGatePipeline(
            listOf(ExtractionGate { p, c -> contexts += c; GateEvaluation("ctx", p, GateDecision.Persist) }),
        )

        GateApplier.of(pipeline)
            .withContext { _, r -> GateContext(revisionResult = r, trustScore = 0.3) }
            .apply(chunk(listOf(fresh), listOf(result)))

        assertEquals(listOf(GateContext(revisionResult = result, trustScore = 0.3)), contexts)
    }

    @Test
    fun `context factory receives the claim being gated with each revision result`() {
        val claims = mutableListOf<Proposition>()
        val results = mutableListOf<RevisionResult?>()
        val merged = RevisionResult.Merged(storedA, storedARevised)
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)

        applier(rejecting())
            .withContext { claim, r -> claims += claim; results += r; GateContext(revisionResult = r) }
            .apply(chunk(listOf(storedA, contradictingNew), listOf(merged, contradicted)))

        assertEquals(listOf(storedARevised, contradictingNew), claims)
        assertEquals(listOf<RevisionResult?>(merged, contradicted), results)
    }

    @Test
    fun `context factory gives each claim its own context when no revision ran`() {
        val trusted = fresh.copy(metadata = mapOf(TRUST_SCORE to 0.9))
        val doubtful = general.copy(metadata = mapOf(TRUST_SCORE to 0.2))
        val reviewed = mutableListOf<ResultVerdict>()

        val gated = GateApplier.of(ExtractionGatePipeline(listOf(TrustGate(0.5))))
            .withReviewSink { reviewed += it }
            .withContext { claim, r -> GateContext(revisionResult = r, trustScore = claim.metadata[TRUST_SCORE] as Double?) }
            .apply(chunk(listOf(trusted, doubtful), emptyList()))

        assertEquals(listOf(trusted), gated.admitted.propositionsToPersist())
        assertEquals(listOf(doubtful), reviewed.single().propositions)
        assertEquals(listOf(true, false), gated.verdicts.map { it.isAdmitted })
    }

    @Test
    fun `whole-result verdict routes a result to review by calling the sink once with the whole result and dropping it`() {
        val reviewed = mutableListOf<ResultVerdict>()
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)
        val pipeline = ExtractionGatePipeline(listOf(gate("review", GateDecision.RouteToReview("check this"))))

        val gated = GateApplier.of(pipeline)
            .withReviewSink { reviewed += it }
            .apply(chunk(listOf(contradictingNew), listOf(contradicted)))

        assertEquals(1, reviewed.size)
        assertSame(contradicted, reviewed.single().revisionResult)
        assertEquals(listOf(contradictedOriginal, contradictingNew), reviewed.single().propositions)
        assertEquals(GateDecision.RouteToReview("check this"), reviewed.single().decision)
        assertEquals(reviewed, gated.reviewed())
        assertTrue(gated.rejected().isEmpty())
        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
    }

    @Test
    fun `whole-result verdict admits a Demote decision and carries it on the verdict without applying it`() {
        val demote = GateDecision.Demote("MAY_WORK_AT", "weak evidence")
        val pipeline = ExtractionGatePipeline(listOf(gate("demote", demote)))

        val gated = GateApplier.of(pipeline).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        val verdict = gated.verdicts.single()
        assertTrue(verdict.isAdmitted)
        assertEquals(demote, verdict.decision)
        // The proposition is written exactly as extracted; the host reads the demotion.
        assertEquals(listOf(fresh), gated.admitted.propositionsToPersist())
        assertTrue(gated.rejected().isEmpty())
        assertTrue(gated.reviewed().isEmpty())
    }

    @Test
    fun `whole-result verdict admits a SkipProjection decision and carries it on the verdict`() {
        val skip = GateDecision.SkipProjection("low confidence")
        val pipeline = ExtractionGatePipeline(listOf(gate("skip", skip)))

        val gated = GateApplier.of(pipeline).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        assertTrue(gated.verdicts.single().isAdmitted)
        assertEquals(skip, gated.verdicts.single().decision)
        assertEquals(listOf(fresh), gated.admitted.propositionsToPersist())
    }

    // --- Ruling: no revision ran ---

    @Test
    fun `no revision ran so each proposition is its own result with a null revision result`() {
        val contexts = mutableListOf<GateContext>()
        val pipeline = ExtractionGatePipeline(
            listOf(
                ExtractionGate { p, c ->
                    contexts += c
                    GateEvaluation("by-id", p, if (p.id == storedA.id) GateDecision.Reject("no") else GateDecision.Persist)
                },
            ),
        )

        val gated = GateApplier.of(pipeline).apply(chunk(listOf(fresh, storedA, general), emptyList()))

        assertEquals(3, gated.verdicts.size)
        assertTrue(gated.verdicts.all { it.revisionResult == null })
        assertEquals(listOf(listOf(fresh), listOf(storedA), listOf(general)), gated.verdicts.map { it.propositions })
        assertTrue(contexts.all { it.revisionResult == null })
        assertEquals(listOf(fresh, general), gated.admitted.propositions)
        assertEquals(listOf(fresh, general), gated.admitted.propositionsToPersist())
    }

    // --- Ruling: the filtered chunk, the empty-results trap and Failed ---

    @Test
    fun `filtered chunk empties propositions when every revision result is dropped`() {
        val results = listOf(
            RevisionResult.New(fresh),
            RevisionResult.Merged(storedA, storedARevised),
            RevisionResult.Generalized(general, listOf(specific)),
        )
        val pipeline = ExtractionGatePipeline(listOf(gate("no", GateDecision.Reject("nothing passes"))))

        val gated = GateApplier.of(pipeline).apply(chunk(listOf(fresh, storedA, general), results))

        assertTrue(gated.admitted.revisionResults.isEmpty())
        assertTrue(gated.admitted.propositions.isEmpty())
        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertEquals(3, gated.rejected().size)
    }

    @Test
    fun `filtered chunk is a copy that keeps the chunk id, entities and rejected mentions`() {
        val original = chunk(listOf(fresh), listOf(RevisionResult.New(fresh)), entities = listOf(newEntity("e-alice")))

        val admitted = applier(rejecting()).apply(original).admitted

        val success = assertInstanceOf(ChunkPropositionResult.Success::class.java, admitted)
        assertEquals(original.chunkId, success.chunkId)
        assertEquals(original.entityResolutions, success.entityResolutions)
        assertEquals(original.suggestedPropositions, success.suggestedPropositions)
        assertEquals(original.rejectedMentions, success.rejectedMentions)
    }

    @Test
    fun `filtered chunk passes a Failed result through with no verdicts`() {
        val failed = ChunkPropositionResult.failed("chunk-1", IllegalStateException("boom"))
        val reviewed = mutableListOf<ResultVerdict>()
        val listener = RecordingDiceEventListener()

        val gated = applier(rejecting(fresh.id)).withReviewSink { reviewed += it }.withListener(listener).apply(failed)

        assertSame(failed, gated.admitted)
        assertTrue(gated.verdicts.isEmpty())
        assertTrue(reviewed.isEmpty())
        assertEquals(0, listener.count())
    }

    // --- Ruling: partial drops ---

    @Test
    fun `partial drop removes dropped New, Contradicted new and Generalized ids from propositions`() {
        val kept = proposition("p-kept")
        val results = listOf(
            RevisionResult.New(fresh),
            RevisionResult.New(kept),
            RevisionResult.Contradicted(contradictedOriginal, contradictingNew),
            RevisionResult.Generalized(general, listOf(specific)),
        )

        val gated = applier(rejecting(fresh.id, contradictingNew.id, general.id))
            .apply(chunk(listOf(fresh, kept, contradictingNew, general), results))

        assertEquals(listOf(kept), gated.admitted.propositions)
        assertEquals(listOf(kept), gated.admitted.propositionsToPersist())
    }

    @Test
    fun `partial drop keeps extracted claims behind a dropped Merged result without writing them`() {
        val extractedClaim = proposition("p-extracted-claim")
        val results = listOf(
            RevisionResult.Merged(storedA, storedARevised),
            RevisionResult.New(fresh),
        )

        val gated = applier(rejecting(storedA.id)).apply(chunk(listOf(extractedClaim, fresh), results))

        // The claim the merge folded in has its own id, so it stays in propositions,
        // but what is written comes from the remaining revision results.
        assertEquals(listOf(extractedClaim, fresh), gated.admitted.propositions)
        assertEquals(listOf(fresh), gated.admitted.propositionsToPersist())
    }

    @Test
    fun `partial drop keeps admitted and replayed results in their original order`() {
        val replay = RevisionResult.Reinforced(storedB, storedB)
        val dropped = RevisionResult.New(fresh)
        val admittedMerge = RevisionResult.Merged(storedA, storedARevised)
        val admittedNew = RevisionResult.Generalized(general, listOf(specific))

        val gated = applier(rejecting(fresh.id))
            .apply(chunk(listOf(fresh, storedA, storedB, general), listOf(admittedMerge, dropped, replay, admittedNew)))

        assertEquals(listOf(admittedMerge, replay, admittedNew), gated.admitted.revisionResults)
    }

    // --- Ruling: replays are not gated ---

    @Test
    fun `replays are not gated and are kept with no verdict, sink call or event`() {
        val seen = mutableListOf<Proposition>()
        val reviewed = mutableListOf<ResultVerdict>()
        val listener = RecordingDiceEventListener()
        val replay = RevisionResult.Merged(storedA, storedA)
        val pipeline = ExtractionGatePipeline(
            listOf(ExtractionGate { p, _ -> seen += p; GateEvaluation("review", p, GateDecision.RouteToReview("look")) }),
        )

        val gated = GateApplier.of(pipeline)
            .withReviewSink { reviewed += it }
            .withListener(listener)
            .apply(chunk(listOf(storedA), listOf(replay)))

        assertTrue(seen.isEmpty())
        assertTrue(gated.verdicts.isEmpty())
        assertTrue(reviewed.isEmpty())
        assertEquals(0, listener.count())
        assertEquals(listOf(replay), gated.admitted.revisionResults)
        assertEquals(listOf(storedA), gated.admitted.propositionsToPersist())
    }

    @Test
    fun `replays follow a reviewed changer of the same stored id so withheld evidence is not written`() {
        val listener = RecordingDiceEventListener()
        val reviewed = mutableListOf<ResultVerdict>()
        val changer = RevisionResult.Merged(storedA, storedAWithSecondSource)
        val replay = RevisionResult.Merged(storedAWithSecondSource, storedAWithSecondSource)
        val pipeline = ExtractionGatePipeline(listOf(gateFor(storedA.id, GateDecision.RouteToReview("second source"))))

        val gated = GateApplier.of(pipeline)
            .withReviewSink { reviewed += it }
            .withListener(listener)
            .apply(chunk(listOf(storedA), listOf(changer, replay)))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertTrue(gated.admitted.revisionResults.isEmpty())
        assertEquals(1, reviewed.size)
        assertSame(changer, reviewed.single().revisionResult)
        assertSame(changer, gated.verdicts.single().revisionResult)
        // Only the changer emits; the replay sends nothing of its own.
        assertEquals(1, listener.count())
        assertInstanceOf(PropositionRoutedToReview::class.java, listener.events.single())
    }

    @Test
    fun `replays follow a rejected changer of the same stored id even when the replay comes first`() {
        val listener = RecordingDiceEventListener()
        val changer = RevisionResult.Merged(storedA, storedAWithSecondSource)
        val replay = RevisionResult.Merged(storedAWithSecondSource, storedAWithSecondSource)

        val gated = applier(rejecting(storedA.id))
            .withListener(listener)
            .apply(chunk(listOf(storedA), listOf(replay, changer)))

        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
        assertTrue(gated.admitted.revisionResults.isEmpty())
        assertSame(changer, gated.verdicts.single().revisionResult)
        assertEquals(listOf(storedAWithSecondSource), listener.events.map { (it as PropositionRejected).proposition })
    }

    @Test
    fun `replays follow a rejected Reinforced changer and drop the unchanged stored copy too`() {
        val replay = RevisionResult.Merged(storedA, storedA)
        val changer = RevisionResult.Reinforced(storedA, storedARevised)

        val gated = applier(rejecting(storedA.id)).apply(chunk(listOf(storedA), listOf(replay, changer)))

        assertTrue(gated.admitted.revisionResults.isEmpty())
        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
    }

    @Test
    fun `replays follow a rejected Contradicted result that changed the same stored id`() {
        val stored = proposition("p-orig")
        val replay = RevisionResult.Reinforced(stored, stored)
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)

        val gated = applier(rejecting(contradictingNew.id))
            .apply(chunk(listOf(contradictingNew), listOf(contradicted, replay)))

        assertTrue(gated.admitted.revisionResults.isEmpty())
        assertTrue(gated.admitted.propositionsToPersist().isEmpty())
    }

    @Test
    fun `replays follow an admitted changer so both stay and the revised copy is written once`() {
        val changer = RevisionResult.Merged(storedA, storedAWithSecondSource)
        val replay = RevisionResult.Merged(storedAWithSecondSource, storedAWithSecondSource)

        val gated = applier(rejecting()).apply(chunk(listOf(storedA), listOf(changer, replay)))

        assertEquals(listOf(changer, replay), gated.admitted.revisionResults)
        assertEquals(listOf(storedAWithSecondSource), gated.admitted.propositionsToPersist())
        assertSame(changer, gated.verdicts.single().revisionResult)
    }

    @Test
    fun `replays with no changer of their stored id in the chunk are kept when another result is rejected`() {
        val replay = RevisionResult.Reinforced(storedB, storedB)
        val changer = RevisionResult.Merged(storedA, storedAWithSecondSource)

        val gated = applier(rejecting(storedA.id)).apply(chunk(listOf(storedA, storedB), listOf(changer, replay)))

        assertEquals(listOf(replay), gated.admitted.revisionResults)
        assertEquals(listOf(storedB), gated.admitted.propositionsToPersist())
    }

    // --- Ruling: events ---

    @Test
    fun `events are sent only when a listener is set`() {
        val listener = RecordingDiceEventListener()
        val base = applier(rejecting(fresh.id))
        base.withListener(listener)

        base.apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        assertEquals(0, listener.count())
    }

    @Test
    fun `events send PropositionRejected for each proposition of a rejected result with the decision's reason`() {
        val listener = RecordingDiceEventListener()
        val contradicted = RevisionResult.Contradicted(contradictedOriginal, contradictingNew)
        val pipeline = ExtractionGatePipeline(listOf(gate("no", GateDecision.Reject("contradiction not trusted"))))

        GateApplier.of(pipeline).withListener(listener).apply(chunk(listOf(contradictingNew), listOf(contradicted)))

        val events = listener.events.map { it as PropositionRejected }
        assertEquals(listOf(contradictedOriginal, contradictingNew), events.map { it.proposition })
        assertTrue(events.all { it.reason == "contradiction not trusted" })
    }

    @Test
    fun `events send PropositionRoutedToReview for each proposition of a reviewed result with the decision's reason`() {
        val listener = RecordingDiceEventListener()
        val pipeline = ExtractionGatePipeline(listOf(gate("review", GateDecision.RouteToReview("needs a human"))))

        GateApplier.of(pipeline).withListener(listener).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        val event = listener.events.single() as PropositionRoutedToReview
        assertEquals(fresh, event.proposition)
        assertEquals("needs a human", event.reason)
    }

    @Test
    fun `events are not sent for admitted results`() {
        val listener = RecordingDiceEventListener()
        val pipeline = ExtractionGatePipeline(listOf(gate("skip", GateDecision.SkipProjection("later"))))

        GateApplier.of(pipeline).withListener(listener).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))

        assertEquals(0, listener.count())
    }

    // --- Ruling: call order and failures ---

    @Test
    fun `call order evaluates results and calls the sink in result order`() {
        val seen = mutableListOf<String>()
        val reviewed = mutableListOf<String>()
        val pipeline = ExtractionGatePipeline(
            listOf(ExtractionGate { p, _ -> seen += p.id; GateEvaluation("review", p, GateDecision.RouteToReview("r")) }),
        )
        val results = listOf(
            RevisionResult.Generalized(general, listOf(specific)),
            RevisionResult.New(fresh),
            RevisionResult.Merged(storedA, storedARevised),
        )

        GateApplier.of(pipeline)
            .withReviewSink { reviewed += it.gated.single().proposition.id }
            .apply(chunk(listOf(general, fresh, storedA), results))

        assertEquals(listOf(general.id, fresh.id, storedA.id), seen)
        assertEquals(seen, reviewed)
    }

    @Test
    fun `call order lets an exception from the sink reach the caller`() {
        val pipeline = ExtractionGatePipeline(listOf(gate("review", GateDecision.RouteToReview("r"))))
        val applier = GateApplier.of(pipeline).withReviewSink { throw IllegalStateException("inbox down") }

        val thrown = assertThrows(IllegalStateException::class.java) {
            applier.apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))
        }
        assertEquals("inbox down", thrown.message)
    }

    @Test
    fun `call order lets an exception from a gate reach the caller`() {
        val pipeline = ExtractionGatePipeline(listOf(ExtractionGate { _, _ -> throw IllegalArgumentException("bad gate") }))

        assertThrows(IllegalArgumentException::class.java) {
            GateApplier.of(pipeline).apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))
        }
    }

    // --- Ruling: what gets written ---

    @Test
    fun `partial drop leaves PersistUnit of admitted with only admitted propositions and no entity only a dropped result referenced`() {
        val results = listOf(
            RevisionResult.New(fresh),
            RevisionResult.Contradicted(contradictedOriginal, contradictingNew),
        )
        val entities = listOf(newEntity("e-alice"), newEntity("e-bob"))

        val gated = applier(rejecting(contradictingNew.id))
            .apply(chunk(listOf(fresh, contradictingNew), results, entities))

        val unit = PersistUnit.of(gated.admitted)
        assertEquals(listOf(fresh), unit.propositions)
        assertEquals(listOf("e-alice"), unit.newEntities.map { it.id })
    }

    @Test
    fun `partial drop means persist writes only admitted propositions to a recording repository`() {
        val repository = RecordingPropositionRepository()
        val entityRepository = mockk<NamedEntityDataRepository>(relaxed = true)
        val savedEntities = mutableListOf<NamedEntityData>()
        val relationshipTargets = mutableListOf<String>()
        val entity = slot<NamedEntityData>()
        every { entityRepository.save(capture(entity)) } answers { savedEntities += entity.captured; entity.captured }
        every { entityRepository.mergeRelationship(any(), any(), any()) } answers {
            relationshipTargets += firstArg<RetrievableIdentifier>().id
            relationshipTargets += secondArg<RetrievableIdentifier>().id
            Unit
        }
        val results = listOf(
            RevisionResult.New(fresh),
            RevisionResult.Generalized(general, listOf(specific)),
            RevisionResult.Contradicted(contradictedOriginal, contradictingNew),
        )
        val entities = listOf(newEntity("e-alice"), newEntity("e-bob"), newEntity("e-carol"))

        val gated = applier(rejecting(general.id, contradictingNew.id))
            .apply(chunk(listOf(fresh, general, contradictingNew), results, entities))
        gated.admitted.persist(repository, entityRepository)

        assertEquals(listOf(fresh.id), repository.saved.map { it.id })
        assertEquals(listOf("e-alice"), savedEntities.map { it.id })
        // No structural relationship touches a dropped proposition.
        assertFalse(relationshipTargets.any { it == general.id || it == contradictingNew.id || it == contradictedOriginal.id })
        assertTrue(relationshipTargets.contains(fresh.id))
    }

    @Test
    fun `with chains return a new applier and leave the original unchanged`() {
        val reviewed = mutableListOf<ResultVerdict>()
        val pipeline = ExtractionGatePipeline(listOf(gate("review", GateDecision.RouteToReview("r"))))
        val base = GateApplier.of(pipeline)
        val withSink = base.withReviewSink { reviewed += it }

        base.apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))
        assertTrue(reviewed.isEmpty())

        withSink.apply(chunk(listOf(fresh), listOf(RevisionResult.New(fresh))))
        assertEquals(1, reviewed.size)
    }

    /** A proposition repository that remembers every proposition saved through it. */
    private class RecordingPropositionRepository(
        private val inner: InMemoryPropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by inner {
        val saved = mutableListOf<Proposition>()

        override fun save(proposition: Proposition): Proposition {
            saved += proposition
            return inner.save(proposition)
        }

        override fun saveAll(propositions: Collection<Proposition>) {
            propositions.forEach { save(it) }
        }
    }

    private companion object {
        const val TRUST_SCORE = "dice.trust.score"
    }

    private fun applier(pipeline: ExtractionGatePipeline): GateApplier = GateApplier.of(pipeline)

    /** A pipeline whose single gate rejects the propositions with the given ids and passes the rest. */
    private fun rejecting(vararg ids: String): ExtractionGatePipeline = ExtractionGatePipeline(
        listOf(
            ExtractionGate { p, _ ->
                GateEvaluation("by-id", p, if (p.id in ids) GateDecision.Reject("rejected ${p.id}") else GateDecision.Persist)
            },
        ),
    )

    /** A pipeline that passes everything and writes down each proposition it was asked about. */
    private fun recording(seen: MutableList<Proposition>): ExtractionGatePipeline = ExtractionGatePipeline(
        listOf(ExtractionGate { p, _ -> seen += p; GateEvaluation("seen", p, GateDecision.Persist) }),
    )

    /** A gate that gives [decision] for the proposition with [id] and passes the rest. */
    private fun gateFor(id: String, decision: GateDecision) =
        ExtractionGate { p, _ -> GateEvaluation("by-id", p, if (p.id == id) decision else GateDecision.Persist) }

    /** A gate that always gives [decision]. */
    private fun gate(name: String, decision: GateDecision) = ExtractionGate { p, _ -> GateEvaluation(name, p, decision) }

    private fun chunk(
        propositions: List<Proposition>,
        results: List<RevisionResult>,
        entities: List<SuggestedEntityResolution> = emptyList(),
    ): ChunkPropositionResult.Success = ChunkPropositionResult.success(
        "chunk-1",
        SuggestedPropositions("chunk-1", emptyList()),
        Resolutions(setOf("chunk-1"), entities),
        propositions,
        results,
    )

    private fun newEntity(id: String): SuggestedEntityResolution =
        NewEntity(SuggestedEntity(labels = listOf("Person"), name = id, summary = "test", chunkId = "chunk-1", id = id))

    private fun proposition(
        id: String,
        confidence: Double = 0.9,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        entity: String? = null,
    ): Proposition = Proposition(
        id = id,
        contextId = tenant,
        text = "proposition $id",
        mentions = entity?.let {
            listOf(EntityMention(span = it, type = "Person", resolvedId = it, role = MentionRole.SUBJECT))
        } ?: emptyList(),
        confidence = confidence,
        status = status,
        grounding = listOf("chunk-1"),
    )
}
