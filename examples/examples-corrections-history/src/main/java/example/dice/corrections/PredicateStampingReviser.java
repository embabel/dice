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
package example.dice.corrections;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.revision.ClassifiedProposition;
import com.embabel.dice.proposition.revision.PropositionReviser;
import com.embabel.dice.proposition.revision.RevisionResult;

import java.util.List;

/**
 * Marks every fact about a plan as a fact about the customer's status, then hands it to the
 * real reviser.
 *
 * <p>The temporal conflict detector only treats a conflict as the world moving on when the
 * fact names a predicate that is expected to change, such as {@code status}. The model's
 * extraction reply carries no predicate, and DICE has no predicate hook in the pipeline, so
 * the app stamps it here, on the way into revision.
 *
 * <p>Only {@code revise} and {@code reviseAll} see new facts, so only they stamp.
 * {@code classify} is passed straight through.
 */
final class PredicateStampingReviser implements PropositionReviser {

    /** The predicate a plan fact gets. The temporal conflict detector treats it as evolving. */
    static final String STATUS = "status";

    private final PropositionReviser reviser;

    /**
     * @param reviser the reviser that does the work
     */
    PredicateStampingReviser(PropositionReviser reviser) {
        this.reviser = reviser;
    }

    @Override
    public RevisionResult revise(Proposition newProposition, PropositionRepository repository) {
        return reviser.revise(stamp(newProposition), repository);
    }

    /**
     * Passed on as one batch, so a reviser that classifies a batch in one model call still
     * does.
     */
    @Override
    public List<RevisionResult> reviseAll(List<Proposition> propositions, PropositionRepository repository) {
        return reviser.reviseAll(propositions.stream().map(PredicateStampingReviser::stamp).toList(), repository);
    }

    @Override
    public List<ClassifiedProposition> classify(Proposition newProposition, List<Proposition> candidates) {
        return reviser.classify(newProposition, candidates);
    }

    /**
     * @param proposition a newly extracted fact
     * @return the fact marked with the {@code status} predicate when it mentions a plan, or
     *         the fact as it was
     */
    static Proposition stamp(Proposition proposition) {
        var mentionsAPlan = proposition.getMentions().stream()
                .anyMatch(mention -> Plan.class.getSimpleName().equalsIgnoreCase(mention.getType()));
        return mentionsAPlan ? proposition.withMetadataValue(Proposition.PREDICATE, STATUS) : proposition;
    }
}
