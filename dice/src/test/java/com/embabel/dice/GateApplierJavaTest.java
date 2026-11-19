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
package com.embabel.dice;

import com.embabel.dice.common.DiceEvent;
import com.embabel.dice.common.PropositionRejected;
import com.embabel.dice.common.Resolutions;
import com.embabel.dice.common.SuggestedEntityResolution;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.SuggestedPropositions;
import com.embabel.dice.proposition.gate.ExtractionGate;
import com.embabel.dice.proposition.gate.ExtractionGatePipeline;
import com.embabel.dice.proposition.gate.GateApplier;
import com.embabel.dice.proposition.gate.GateContext;
import com.embabel.dice.proposition.gate.GateDecision;
import com.embabel.dice.proposition.gate.GateEvaluation;
import com.embabel.dice.proposition.gate.GatedChunk;
import com.embabel.dice.proposition.gate.ResultVerdict;
import com.embabel.dice.proposition.revision.RevisionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a host can build a gate applier and apply it to a chunk from Java, passing plain
 * lambdas for the gate, the review sink, the event listener and the context factory.
 *
 * Contract, as the tests below read it:
 * - E1: a gate and a review sink written as Java lambdas plug straight into the applier.
 * - E2: a reviewed result reaches the sink once and is left out of the chunk to persist.
 * - E3: a rejected result is dropped, reported on the verdict and sent to the listener.
 * - E4: an admitted result stays in the chunk to persist.
 * - E5: a chunk with no revision results gates each proposition on its own, with a null revision result.
 */
class GateApplierJavaTest {

    private final SuggestedPropositions suggested = new SuggestedPropositions("c", List.of());
    private final Resolutions<SuggestedEntityResolution> resolutions = new Resolutions<>(Set.of("c"), List.of());

    private final Proposition kept = proposition("p-kept");
    private final Proposition reviewed = proposition("p-review");
    private final Proposition rejected = proposition("p-reject");

    // E1: a gate written as a lambda, deciding by proposition id.
    private final ExtractionGate byId = (proposition, context) -> {
        GateDecision decision;
        if (proposition.getId().equals("p-review")) {
            decision = new GateDecision.RouteToReview("needs a human");
        } else if (proposition.getId().equals("p-reject")) {
            decision = new GateDecision.Reject("not trusted");
        } else {
            decision = GateDecision.Persist.INSTANCE;
        }
        return new GateEvaluation("by-id", proposition, decision);
    };

    @Test
    void appliesLambdaGateAndSinkToARevisedChunk() {
        List<ResultVerdict> inbox = new ArrayList<>();
        List<DiceEvent> events = new ArrayList<>();
        ChunkPropositionResult.Success chunk = new ChunkPropositionResult.Success(
                "c", suggested, resolutions,
                List.of(kept, reviewed, rejected),
                List.of(
                        new RevisionResult.New(kept),
                        new RevisionResult.New(reviewed),
                        new RevisionResult.New(rejected)));

        // E1: every hook is a lambda; nothing to subclass.
        GateApplier applier = GateApplier.of(new ExtractionGatePipeline(List.of(byId)))
                .withReviewSink(inbox::add)
                .withListener(events::add)
                .withContext(result -> new GateContext(result));

        GatedChunk gated = applier.apply(chunk);

        // E2: the reviewed result reached the sink once and is not written.
        assertEquals(1, inbox.size());
        assertEquals(List.of(reviewed), inbox.get(0).getPropositions());
        assertEquals(inbox, gated.reviewed());

        // E3: the rejected result is dropped and reported, with the gate's reason.
        ResultVerdict rejectedVerdict = gated.rejected().get(0);
        assertFalse(rejectedVerdict.isAdmitted());
        assertEquals(new GateDecision.Reject("not trusted"), rejectedVerdict.getDecision());
        PropositionRejected event = (PropositionRejected) events.stream()
                .filter(e -> e instanceof PropositionRejected)
                .findFirst()
                .orElseThrow();
        assertEquals(rejected, event.getProposition());
        assertEquals("not trusted", event.getReason());

        // E4: only the admitted result is left to persist.
        assertEquals(List.of(kept), gated.getAdmitted().propositionsToPersist());
        assertEquals(3, gated.getVerdicts().size());
        assertTrue(gated.getVerdicts().get(0).isAdmitted());
    }

    @Test
    void gatesEachPropositionWhenNoRevisionRan() {
        ChunkPropositionResult.Success chunk = new ChunkPropositionResult.Success(
                "c", suggested, resolutions, List.of(kept, rejected));

        GatedChunk gated = GateApplier.of(new ExtractionGatePipeline(List.of(byId))).apply(chunk);

        // E5: one verdict per proposition, none tied to a revision result.
        assertEquals(2, gated.getVerdicts().size());
        assertNull(gated.getVerdicts().get(0).getRevisionResult());
        ChunkPropositionResult.Success admitted =
                assertInstanceOf(ChunkPropositionResult.Success.class, gated.getAdmitted());
        assertEquals(List.of(kept), admitted.getPropositions());
    }

    @Test
    void passesAFailedChunkThrough() {
        ChunkPropositionResult failed = ChunkPropositionResult.failed("c", new IllegalStateException("boom"));

        GatedChunk gated = GateApplier.of(new ExtractionGatePipeline(List.of(byId))).apply(failed);

        assertSame(failed, gated.getAdmitted());
        assertTrue(gated.getVerdicts().isEmpty());
    }

    private Proposition proposition(String id) {
        Instant now = Instant.now();
        return Proposition.create(
                id,
                "java-tenant",
                "proposition " + id,
                List.of(),
                0.9,
                0.0,
                0.5,
                null,
                List.of(),
                now,
                now,
                now,
                PropositionStatus.ACTIVE);
    }
}
