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

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.revision.ClassifiedProposition;
import com.embabel.dice.proposition.revision.PropositionRelation;
import com.embabel.dice.proposition.revision.PropositionReviser;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.spi.ConflictType;

import java.util.List;
import java.util.Optional;

/**
 * In this chat an approval rule names one role. A different named role conflicts with the
 * protected rule. Other messages are independent facts. The chat's gate handles the conflict.
 */
final class ApprovalPolicy implements PropositionReviser {

    @Override
    public RevisionResult revise(Proposition incoming, PropositionRepository repository) {
        if (approvalRole(incoming).isEmpty()) {
            return new RevisionResult.New(incoming);
        }
        var pinnedRules = repository.query(PropositionQuery.againstContext(incoming.getContextIdValue())
                        .withStatuses(PropositionStatus.ACTIVE))
                .stream().filter(Proposition::getPinned).toList();
        return classify(incoming, pinnedRules).stream()
                .filter(classified -> classified.getRelation() == PropositionRelation.CONTRADICTORY)
                .<RevisionResult>map(classified -> new RevisionResult.Contradicted(
                        classified.getProposition(), incoming, ConflictType.Contradiction.INSTANCE))
                .findFirst().orElseGet(() -> new RevisionResult.New(incoming));
    }

    @Override
    public List<ClassifiedProposition> classify(Proposition incoming, List<Proposition> candidates) {
        var incomingRole = approvalRole(incoming);
        return candidates.stream().map(candidate -> {
            boolean conflicts = incomingRole.isPresent() && approvalRole(candidate)
                    .filter(role -> !role.equalsIgnoreCase(incomingRole.get())).isPresent();
            return new ClassifiedProposition(candidate,
                    conflicts ? PropositionRelation.CONTRADICTORY : PropositionRelation.UNRELATED,
                    conflicts ? 1 : 0, conflicts ? "A different role is named" : "No change to the approval role");
        }).toList();
    }

    private static Optional<String> approvalRole(Proposition proposition) {
        return proposition.getMentions().stream()
                .filter(mention -> mention.getType().equals(GovernedChatApplication.ApprovalRole.class.getSimpleName()))
                .map(EntityMention::getSpan)
                .findFirst();
    }
}
