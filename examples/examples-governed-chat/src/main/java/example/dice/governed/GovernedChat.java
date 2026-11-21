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
package example.dice.governed;

import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.dice.agent.BudgetedSelection;
import com.embabel.dice.agent.Memory;
import com.embabel.dice.audit.AuditRecord;
import com.embabel.dice.audit.AuditedProposition;
import com.embabel.dice.audit.AuditLogQuery;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.gate.GateApplier;
import com.embabel.dice.proposition.gate.ResultVerdict;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.provenance.ConnectorRef;

import java.time.Instant;
import java.util.List;

public class GovernedChat {

    static final String CONTEXT = "support";

    private final PropositionPipeline pipeline;
    private final SourceAnalysisContext source;
    private final GateApplier gates;
    private final ApprovalPolicy approvalPolicy;
    private final PropositionRepository facts;
    private final NamedEntityDataRepository entities;
    private final AuditLogQuery audit;
    private final Memory memory;

    GovernedChat(PropositionPipeline pipeline, SourceAnalysisContext source, GateApplier gates,
                 ApprovalPolicy approvalPolicy, PropositionRepository facts, NamedEntityDataRepository entities, AuditLogQuery audit, Memory memory) {
        this.pipeline = pipeline;
        this.source = source;
        this.gates = gates;
        this.approvalPolicy = approvalPolicy;
        this.facts = facts;
        this.entities = entities;
        this.audit = audit;
        this.memory = memory;
    }

    /**
     * Extracts a turn, applies the chat's admission policy, and stores only the admitted portion.
     * One turn at a time keeps revision and the write in order.
     */
    public synchronized Turn tell(String messageId, String text) {
        var result = pipeline.processText(text, messageId, source.withSourceLocator(new ConnectorRef("chat", messageId)));
        if (result instanceof ChunkPropositionResult.Failed failed) {
            throw new IllegalStateException(failed.getError(), failed.getCause());
        }
        var gated = gates.apply(result);
        var stored = gated.getAdmitted().persistReturningCanonical(facts, entities);
        return new Turn(stored.getDistinctCanonicalPropositions(), claims(gated.reviewed()), claims(gated.rejected()));
    }

    /** Protects an admitted rule from ordinary conflict handling and prompt trimming. */
    public synchronized void pin(String propositionId) {
        var claim = inContext(propositionId);
        if (claim == null) {
            throw new IllegalArgumentException("Unknown support proposition: " + propositionId);
        }
        if (approvalPolicy.revise(claim, facts) instanceof RevisionResult.Contradicted) {
            throw new IllegalStateException("Resolve the conflict with the pinned approval rule before pinning this claim");
        }
        if (facts.pin(propositionId) == null) {
            throw new IllegalArgumentException("Unknown support proposition: " + propositionId);
        }
    }

    public BudgetedSelection promptMemory() {
        return memory.eagerSelection();
    }

    /** Evidence for a stored fact and the recorded disposition of any proposed fact. */
    public Explanation explain(String propositionId) {
        var current = inContext(propositionId);
        return new Explanation(current == null ? List.of() : current.getGrounding(),
                audit.history(CONTEXT, propositionId));
    }

    public List<String> beliefsAt(Instant at) {
        return audit.beliefSetAsOf(CONTEXT, at).stream().map(AuditedProposition::getId).toList();
    }

    private Proposition inContext(String propositionId) {
        var fact = facts.findById(propositionId);
        return fact != null && CONTEXT.equals(fact.getContextIdValue()) ? fact : null;
    }

    private static List<Proposition> claims(List<ResultVerdict> verdicts) {
        return verdicts.stream().map(verdict -> verdict.getGated().getFirst().getProposition()).toList();
    }

    public record Turn(List<Proposition> admitted, List<Proposition> reviewed, List<Proposition> rejected) {
    }

    public record Explanation(List<String> groundingIds, List<AuditRecord> history) {
    }
}
