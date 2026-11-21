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

import com.embabel.dice.audit.AuditCoverageException;
import com.embabel.dice.audit.AuditKind;
import com.embabel.dice.audit.AuditRecord;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ScriptedModel.class)
class GovernedChatTests {

    @Autowired
    GovernedChat chat;

    @Autowired
    PropositionRepository facts;

    @Test
    void aKnownIdFromAnotherAccountCannotBePinnedOrExplained() {
        var other = facts.save(Proposition.draft("another-account", "Private policy", 0.98)
                .withGrounding(List.of("private-chat")).toProposition());

        assertThatThrownBy(() -> chat.pin(other.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(facts.findById(other.getId()).getPinned()).isFalse();
        assertThat(chat.explain(other.getId()).groundingIds()).isEmpty();
        assertThat(chat.explain(other.getId()).history()).isEmpty();
    }

    @Test
    void protectsPolicyAndExplainsWhatTheChatAdmitted() {
        assertThatThrownBy(() -> chat.beliefsAt(Instant.EPOCH)).isInstanceOf(AuditCoverageException.class);

        var policy = chat.tell("policy-1", "Only supervisors may approve refunds.");
        var rule = policy.admitted().getFirst();
        chat.pin(rule.getId());

        var dispute = chat.tell("chat-2", "Support agents may approve refunds.");
        assertThat(dispute.reviewed()).hasSize(1);
        assertThat(dispute.admitted()).isEmpty();

        var hearsay = chat.tell("chat-3", "Maybe refunds must be mailed.");
        assertThat(hearsay.rejected()).hasSize(1);
        assertThat(hearsay.admitted()).isEmpty();

        var preference = chat.tell("chat-4", "The customer prefers refund updates by email.");
        var accepted = preference.admitted().getFirst();

        var memory = chat.promptMemory();
        assertThat(memory.isOverBudget()).isTrue();
        assertThat(memory.getSelected()).extracting(Proposition::getId).containsExactly(rule.getId());
        assertThat(memory.getDropped()).extracting(Proposition::getId).containsExactly(accepted.getId());

        var explanation = chat.explain(rule.getId());
        assertThat(explanation.groundingIds()).containsExactly("policy-1");
        assertThat(explanation.history()).extracting(AuditRecord::getKind)
                .containsSubsequence(AuditKind.CREATED, AuditKind.PINNED);
        assertThat(explanation.history()).filteredOn(record -> record.getKind() == AuditKind.PINNED)
                .singleElement().satisfies(record -> {
                    assertThat(record.getBefore().getPinned()).isFalse();
                    assertThat(record.getAfter().getPinned()).isTrue();
                });

        var reviewed = dispute.reviewed().getFirst();
        assertThat(chat.explain(reviewed.getId()).history())
                .singleElement().satisfies(record -> {
                    assertThat(record.getKind()).isEqualTo(AuditKind.GATE_REVIEW);
                    assertThat(record.getReason()).contains("review the conflict");
                });
        var declined = hearsay.rejected().getFirst();
        assertThat(chat.explain(declined.getId()).history())
                .singleElement().satisfies(record -> {
                    assertThat(record.getKind()).isEqualTo(AuditKind.GATE_REJECTED);
                    assertThat(record.getReason()).contains("confidence");
                });
        assertThat(chat.beliefsAt(Instant.now())).containsExactlyInAnyOrder(rule.getId(), accepted.getId());
    }
}
