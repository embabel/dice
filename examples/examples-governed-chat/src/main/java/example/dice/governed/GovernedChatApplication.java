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

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.agent.ContextBudget;
import com.embabel.dice.agent.Memory;
import com.embabel.dice.audit.AuditEventListener;
import com.embabel.dice.audit.AuditingPropositionRepository;
import com.embabel.dice.audit.InMemoryAuditLog;
import com.embabel.dice.common.DiceEvent;
import com.embabel.dice.common.PropositionRejected;
import com.embabel.dice.common.PropositionRoutedToReview;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.gate.ConfidenceGate;
import com.embabel.dice.proposition.gate.ConflictClassificationGate;
import com.embabel.dice.proposition.gate.ExtractionGatePipeline;
import com.embabel.dice.proposition.gate.GateApplier;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.List;

@SpringBootApplication
class GovernedChatApplication {

    @Bean
    InMemoryAuditLog auditLog() {
        var audit = new InMemoryAuditLog();
        audit.initializeBaseline(GovernedChat.CONTEXT, Instant.now(), List.of());
        return audit;
    }

    @Bean
    PropositionRepository facts(InMemoryAuditLog audit) {
        return AuditingPropositionRepository.wrapping(new InMemoryPropositionRepository(), audit);
    }

    @Bean
    GovernedChat governedChat(Ai ai, PropositionRepository facts, InMemoryAuditLog audit) {
        var schema = DataDictionary.fromClasses("refunds", ApprovalRole.class);
        var entities = new InMemoryNamedEntityDataRepository(schema);
        var source = SourceAnalysisContext.withContextId(GovernedChat.CONTEXT)
                .withEntityResolver(EscalatingEntityResolver.withoutVector(entities))
                .withSchema(schema)
                .withMintNewEntities(true);
        var extractor = LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai);
        var approvalPolicy = new ApprovalPolicy();
        var pipeline = PropositionPipeline.withExtractor(extractor).withRevision(approvalPolicy, facts);
        var auditEvents = new AuditEventListener(audit);
        var gates = GateApplier.of(new ExtractionGatePipeline(List.of(
                        new ConfidenceGate(0.6), new ConflictClassificationGate())))
                .withListener(event -> recordIncomingDisposition(auditEvents, event));
        var memory = Memory.forContext(GovernedChat.CONTEXT).withRepository(facts)
                .withEagerQuery(query -> query.withStatuses(PropositionStatus.ACTIVE).withMinConfidence(0.0))
                .withContextBudget(ContextBudget.ofTokens(4));
        return new GovernedChat(pipeline, source, gates, approvalPolicy, facts, entities, audit, memory);
    }

    private static void recordIncomingDisposition(AuditEventListener listener, DiceEvent event) {
        var claim = switch (event) {
            case PropositionRejected rejected -> rejected.getProposition();
            case PropositionRoutedToReview reviewed -> reviewed.getProposition();
            default -> null;
        };
        // A conflict gates the whole revision. This chat records the disposition of the proposal.
        if (claim != null && !claim.getPinned()) {
            listener.onEvent(event);
        }
    }

    public record ApprovalRole(String name) {
    }
}
