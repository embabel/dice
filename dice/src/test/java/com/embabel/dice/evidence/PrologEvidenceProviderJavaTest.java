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
package com.embabel.dice.evidence;

import com.embabel.dice.projection.prolog.ConfidenceFact;
import com.embabel.dice.projection.prolog.GroundingFact;
import com.embabel.dice.projection.prolog.PrologEvidenceProvider;
import com.embabel.dice.projection.prolog.PrologFact;
import com.embabel.dice.projection.prolog.PrologProjectionResult;
import com.embabel.dice.projection.prolog.PrologSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller builds a Prolog evidence provider from projected facts and asks it a rule goal.
 *
 * Contract:
 * E1: a provable, declared goal gives a Derived carrying the rule id and the fact's source id.
 * E2: a goal nothing proves gives a Missing that names the goal.
 * E3: conclusions returns every derived answer as a list.
 */
class PrologEvidenceProviderJavaTest {

    private final PrologFact expert = new PrologFact("expert_in", List.of("alice", "kubernetes"), 0.9, 0.0, List.of("prop-1"));
    private final PrologFact otherExpert = new PrologFact("expert_in", List.of("bob", "kotlin"), 0.8, 0.0, List.of("prop-2"));

    private final List<PrologFact> facts = List.of(expert, otherExpert);

    private final PrologProjectionResult projection = new PrologProjectionResult(
            facts,
            List.of(new ConfidenceFact(expert), new ConfidenceFact(otherExpert)),
            List.of(new GroundingFact(expert, "prop-1"), new GroundingFact(otherExpert, "prop-2")));

    // withRules has no @JvmStatic or default-argument overloads, so Java goes through Companion and passes both.
    private final PrologSchema schema = PrologSchema.Companion.withRules(
            "can_help(X, Topic) :- expert_in(X, Topic).", List.of());

    private final PrologEvidenceProvider provider = PrologEvidenceProvider.of(projection, schema)
            .withDeclaration(RuleDeclaration.of("can_help(X, Topic)").withInput("expert_in(X, Topic)"));

    // E1: a provable, declared goal gives a Derived carrying the rule id and the fact's source id.
    @Test
    void provableGoalGivesDerivedWithRuleAndInputIds() {
        Evidence evidence = provider.evidence(RuleGoal.of("can_help('alice', T)"));

        assertTrue(evidence instanceof Evidence.Derived);
        Evidence.Derived derived = (Evidence.Derived) evidence;
        assertEquals("can_help('alice', 'kubernetes')", derived.getConclusion());
        assertEquals("can_help/2", derived.getRule().getRuleId());
        assertEquals("kubernetes", derived.getRule().getParameters().get("binding.T"));
        assertEquals(List.of("prop-1"), derived.getInputIds());
        assertEquals(0.9, derived.getConfidence());
    }

    // E2: a goal nothing proves gives a Missing that names the goal.
    @Test
    void unprovableGoalGivesMissingNamingTheGoal() {
        Evidence evidence = provider.evidence(RuleGoal.of("can_help('zed', T)").withContext("ctx-main"));

        assertTrue(evidence instanceof Evidence.Missing);
        Searched searched = ((Evidence.Missing) evidence).getSearched();
        assertEquals("can_help('zed', T)", searched.getQuery());
        assertEquals(java.util.Set.of("ctx-main"), searched.getContextIdValues());
    }

    // E3: conclusions returns every derived answer as a list.
    @Test
    void conclusionsReturnsEveryAnswer() {
        List<Evidence.Derived> conclusions = provider.conclusions(RuleGoal.of("can_help(X, T)"));

        assertEquals(2, conclusions.size());
        assertEquals(List.of("prop-2"), conclusions.get(1).getInputIds());
    }
}
