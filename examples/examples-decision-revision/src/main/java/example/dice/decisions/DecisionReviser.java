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
package example.dice.decisions;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.Questions;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides what a new fact means for the stored ones by asking an Embabel decision, in place of
 * DICE's built-in LLM reviser.
 *
 * <p>The candidates are the active facts in the new fact's context that name one of the same
 * entities. The decision gets one choice per candidate: duplicate, reinforces, contradicts or
 * unrelated. The first candidate that is not unrelated is merged, reinforced or contradicted
 * with the same arithmetic the built-in reviser uses. If none is related, the fact is new.
 *
 * <p>A decision that fails, or answers any choice with something other than one of the four
 * options, leaves every stored fact alone. The new fact is stored as new and a warning names it.
 */
public class DecisionReviser implements PropositionReviser {

    /** The decision role this reviser asks. The application maps it to a decision service. */
    public static final String ROLE = "dice-revision";

    static final String DUPLICATE = "duplicate";
    static final String REINFORCES = "reinforces";
    static final String CONTRADICTS = "contradicts";
    static final String UNRELATED = "unrelated";

    private static final Logger logger = LoggerFactory.getLogger(DecisionReviser.class);

    private final Ai ai;

    /**
     * @param ai where the decision service comes from
     */
    public DecisionReviser(Ai ai) {
        this.ai = ai;
    }

    /**
     * Revises one new fact against the stored ones. Nothing is saved here. The pipeline's caller
     * stores the result.
     *
     * @param newProposition the fact just extracted
     * @param repository     the store to read candidates from, as the pipeline passes it
     * @return what the new fact does to the stored ones
     */
    @Override
    public RevisionResult revise(Proposition newProposition, PropositionRepository repository) {
        var candidates = candidatesFor(newProposition, repository);
        // Nothing to compare against, so there is nothing to ask.
        if (candidates.isEmpty()) {
            return new RevisionResult.New(newProposition);
        }

        var answers = ask(newProposition, candidates);
        if (answers == null) {
            return new RevisionResult.New(newProposition);
        }
        // A single unusable answer means the decision cannot be trusted for this fact, so no
        // stored fact is changed on the strength of the others.
        for (var answer : answers) {
            if (relationOf(answer) == null) {
                logger.warn("The revision decision gave no usable answer for fact {} ({}): {}. Storing it as new.",
                        newProposition.getId(), newProposition.getText(), answer);
                return new RevisionResult.New(newProposition);
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            var original = candidates.get(i);
            var relation = Objects.requireNonNull(relationOf(answers.get(i)));
            if (relation.equals(DUPLICATE)) {
                return new RevisionResult.Merged(original, merge(original, newProposition));
            }
            if (relation.equals(REINFORCES)) {
                return new RevisionResult.Reinforced(original, reinforce(original, newProposition));
            }
            if (relation.equals(CONTRADICTS)) {
                return new RevisionResult.Contradicted(
                        contradict(original), newProposition, ConflictType.Contradiction.INSTANCE);
            }
            // Unrelated, so the next candidate is looked at.
        }
        return new RevisionResult.New(newProposition);
    }

    /**
     * Asks the same decision about candidates the caller has chosen, and reports one
     * classification per candidate, in the order given. An answer that is not one of the four
     * options, or a failed decision, is reported as unrelated with a similarity of 0.
     *
     * @param newProposition the fact just extracted
     * @param candidates     the stored facts to compare it with
     * @return one classification per candidate
     */
    @Override
    public List<ClassifiedProposition> classify(Proposition newProposition, List<Proposition> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        var answers = ask(newProposition, candidates);
        var classified = new ArrayList<ClassifiedProposition>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            var answer = answers == null ? null : answers.get(i);
            var relationId = answer == null ? null : relationOf(answer);
            if (relationId == null) {
                classified.add(new ClassifiedProposition(candidate, PropositionRelation.UNRELATED, 0.0,
                        "The revision decision gave no usable answer"));
                continue;
            }
            var selected = (ClassificationResult.Selected) answer;
            var relation = switch (relationId) {
                case DUPLICATE -> PropositionRelation.IDENTICAL;
                case REINFORCES -> PropositionRelation.SIMILAR;
                case CONTRADICTS -> PropositionRelation.CONTRADICTORY;
                default -> PropositionRelation.UNRELATED;
            };
            // The decision gives a confidence only when the service reports one. Without it, a
            // related answer counts as a full match and an unrelated one as none.
            var confidence = selected.getConfidence();
            double similarity = confidence != null ? confidence
                    : relation == PropositionRelation.UNRELATED ? 0.0 : 1.0;
            classified.add(new ClassifiedProposition(candidate, relation, similarity, "Answered " + relationId));
        }
        return classified;
    }

    /**
     * The active facts in the new fact's context that name one of its resolved entities, ordered
     * by id so the order does not depend on the store.
     */
    private static List<Proposition> candidatesFor(Proposition newProposition, PropositionRepository repository) {
        var entityIds = newProposition.getMentions().stream()
                .map(EntityMention::getResolvedId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // An empty entity filter would match every fact, so a fact that names no known entity
        // has no candidates.
        if (entityIds.isEmpty()) {
            return List.of();
        }
        return repository.query(
                        PropositionQuery.againstContext(newProposition.getContextIdValue())
                                .withStatus(PropositionStatus.ACTIVE)
                                .withAnyEntityIds(entityIds))
                .stream()
                .filter(candidate -> !candidate.getId().equals(newProposition.getId()))
                .sorted(Comparator.comparing(Proposition::getId))
                .toList();
    }

    /**
     * Asks the decision once, with one choice per candidate.
     *
     * @return the answers in candidate order, or null when the request failed
     */
    private @Nullable List<ClassificationResult> ask(Proposition newProposition, List<Proposition> candidates) {
        var questions = new ArrayList<ChoiceQuestionSpec>(candidates.size());
        var input = new StringBuilder("New proposition: ").append(newProposition.getText());
        for (int i = 0; i < candidates.size(); i++) {
            int number = i + 1;
            questions.add(Questions.named("relation-" + number)
                    .choice("How does the new proposition relate to candidate " + number + "?")
                    .option(DUPLICATE, "Says the same thing")
                    .option(REINFORCES, "Adds support for it")
                    .option(CONTRADICTS, "Cannot both be true")
                    .option(UNRELATED, "About something else")
                    .build());
            input.append('\n').append("Candidate ").append(number).append(": ").append(candidates.get(i).getText());
        }
        try {
            DecisionResponse response = ai.decisions().byRole(ROLE)
                    .ask(input.toString(), DecisionSpec.of(List.<Question<?>>copyOf(questions)));
            if (response.getRequestFailure() != null) {
                logger.warn("The revision decision failed for fact {} ({}): {}. Storing it as new.",
                        newProposition.getId(), newProposition.getText(), response.getRequestFailure());
                return null;
            }
            return questions.stream().map(response::answer).toList();
        } catch (RuntimeException e) {
            logger.warn("The revision decision failed for fact {} ({}). Storing it as new.",
                    newProposition.getId(), newProposition.getText(), e);
            return null;
        }
    }

    /**
     * The option the decision chose, or null when the answer is not one of the four options.
     */
    private static @Nullable String relationOf(ClassificationResult answer) {
        if (answer instanceof ClassificationResult.Selected selected) {
            var id = selected.getCategoryId();
            if (id.equals(DUPLICATE) || id.equals(REINFORCES) || id.equals(CONTRADICTS) || id.equals(UNRELATED)) {
                return id;
            }
        }
        return null;
    }

    /**
     * The stored fact after a duplicate: it gains the new fact's evidence, its confidence rises
     * by three tenths of the new fact's up to 0.99 and never falls, and it decays more slowly.
     */
    private static Proposition merge(Proposition original, Proposition incoming) {
        double confidence = Math.max(original.getConfidence(),
                Math.min(original.getConfidence() + 0.3 * incoming.getConfidence(), 0.99));
        return original.absorbEvidence(incoming).toDraft()
                .withConfidence(confidence)
                .withDecay(original.getDecay() * 0.7)
                .toProposition();
    }

    /**
     * The stored fact after support: it gains the new fact's evidence, its confidence rises by
     * a tenth of the new fact's up to 0.95 and never falls, and it decays a little more slowly.
     */
    private static Proposition reinforce(Proposition original, Proposition incoming) {
        double confidence = Math.max(original.getConfidence(),
                Math.min(original.getConfidence() + 0.1 * incoming.getConfidence(), 0.95));
        return original.absorbEvidence(incoming).toDraft()
                .withConfidence(confidence)
                .withDecay(original.getDecay() * 0.85)
                .toProposition();
    }

    /**
     * The stored fact after a contradiction: its confidence drops to three tenths, but not below
     * 0.05, it decays faster and is marked contradicted. A pinned fact is kept as it is, so the
     * clash waits for someone to resolve it.
     */
    private static Proposition contradict(Proposition original) {
        if (original.getPinned()) {
            return original;
        }
        return original.toDraft()
                .withConfidence(Math.max(original.getConfidence() * 0.3, 0.05))
                .withDecay(Math.min(original.getDecay() + 0.15, 1.0))
                .withStatus(PropositionStatus.CONTRADICTED)
                .toProposition();
    }
}
