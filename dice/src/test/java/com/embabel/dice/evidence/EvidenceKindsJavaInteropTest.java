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

import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.spi.ConflictType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller builds each kind of evidence and what each kind accepts.
 */
class EvidenceKindsJavaInteropTest {

    private final EvidenceItem source1 =
            EvidenceItem.of("Ada worked with Babbage", Citation.of("p-1", "support-team"), 0.8);

    private final EvidenceItem source2 =
            EvidenceItem.of("Ada wrote notes on the engine", Citation.of("p-2", "support-team"), 0.8);

    private final Evidence.Derived derived =
            Evidence.derived("Ada was a programmer", 0.7, DerivationRule.of("prolog"), List.of("p-2"));

    private final EvidenceItem moved =
            EvidenceItem.of("Ada lives in London", Citation.of("p-3", "support-team"), 0.9);

    private final EvidenceItem stayed =
            EvidenceItem.of("Ada lives in Marylebone", Citation.of("p-4", "support-team"), 0.6)
                    .withStatus(PropositionStatus.SUPERSEDED);

    @Test
    void inputOutsideTheInputIdsIsRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> derived.withInputs(List.of(source1)));

        assertTrue(thrown.getMessage().contains("inputs[0]"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("p-1"), thrown.getMessage());
    }

    @Test
    void inputsMayBeASubsetOfTheInputIds() {
        Evidence.Derived twoIds =
                Evidence.derived("Ada was a programmer", 0.7, DerivationRule.of("prolog"), List.of("p-1", "p-2"));

        Evidence.Derived withInputs = twoIds.withInputs(List.of(source2));

        assertEquals(List.of(source2), withInputs.getInputs());
        assertEquals(List.of("p-1", "p-2"), withInputs.getInputIds());
    }

    @Test
    void switchOverEvidenceNamesEachOfTheFiveKinds() {
        List<Evidence> values = List.of(
                Evidence.recorded(List.of(source1)),
                derived,
                Evidence.missing(Searched.of("support-team")),
                Evidence.conflicting(List.of(source1, source2), ConflictType.Contradiction.INSTANCE),
                Evidence.superseded(moved, List.of(stayed), ConflictType.WorldProgression.INSTANCE));

        List<String> labels = values.stream().map(EvidenceKindsJavaInteropTest::kind).toList();

        assertEquals(List.of("recorded", "derived", "missing", "conflicting", "superseded"), labels);
    }

    @Test
    void switchOverADisagreementNeedsTwoCases() {
        Evidence.Disagreement conflict =
                Evidence.conflicting(List.of(source1, source2), ConflictType.Contradiction.INSTANCE);
        Evidence.Disagreement replacement =
                Evidence.superseded(moved, List.of(stayed), ConflictType.Revision.INSTANCE);

        assertEquals("2 claims disagree", disagreement(conflict));
        assertEquals("p-3 replaced p-4", disagreement(replacement));
        assertEquals(ConflictType.Revision.INSTANCE, replacement.getConflictType());
    }

    @Test
    void supersededReadsBackCurrentPreviousAndConflictType() {
        Evidence.Superseded superseded =
                Evidence.superseded(moved, List.of(stayed), ConflictType.WorldProgression.INSTANCE);

        assertEquals(moved, superseded.getCurrent());
        assertEquals(List.of(stayed), superseded.getPrevious());
        assertEquals(PropositionStatus.SUPERSEDED, superseded.getPrevious().get(0).getStatus());
        assertEquals(ConflictType.WorldProgression.INSTANCE, superseded.getConflictType());
    }

    @Test
    void supersededRejectsAnEmptyPrevious() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Evidence.superseded(moved, List.of(), ConflictType.Revision.INSTANCE));

        assertEquals("previous must not be empty", thrown.getMessage());
    }

    @Test
    void supersededRejectsAPreviousClaimThatIsTheCurrentClaim() {
        EvidenceItem sameClaim = EvidenceItem.of("Ada lives in Paris", Citation.of("p-3", "support-team"), 0.4);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Evidence.superseded(moved, List.of(stayed, sameClaim), ConflictType.Revision.INSTANCE));

        assertEquals("current and previous[1] cite the same claim 'p-3' in context 'support-team'",
                thrown.getMessage());
    }

    @Test
    void conflictingRejectsALaterItemThatRepeatsAnEarlierClaim() {
        EvidenceItem repeat = EvidenceItem.of("Ada worked with Babbage", Citation.of("p-1", "support-team"), 0.5);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Evidence.conflicting(List.of(source1, source2, repeat), ConflictType.Contradiction.INSTANCE));

        assertEquals("items[0] and items[2] cite the same claim 'p-1' in context 'support-team'",
                thrown.getMessage());
    }

    @Test
    void supersededAcceptsTheSameIdInAnotherContext() {
        EvidenceItem elsewhere = EvidenceItem.of("Ada lives in Paris", Citation.of("p-3", "billing-team"), 0.4);

        Evidence.Superseded superseded =
                Evidence.superseded(moved, List.of(elsewhere), ConflictType.Revision.INSTANCE);

        assertEquals(List.of(elsewhere), superseded.getPrevious());
    }

    @Test
    void supersededRejectsAContradiction() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Evidence.superseded(moved, List.of(stayed), ConflictType.Contradiction.INSTANCE));

        assertEquals("conflictType Contradiction is a conflict, use Evidence.conflicting", thrown.getMessage());
    }

    @Test
    void conflictingRejectsARevisionAndAWorldChange() {
        IllegalArgumentException revision = assertThrows(IllegalArgumentException.class,
                () -> Evidence.conflicting(List.of(moved, stayed), ConflictType.Revision.INSTANCE));
        IllegalArgumentException worldChange = assertThrows(IllegalArgumentException.class,
                () -> Evidence.conflicting(List.of(moved, stayed), ConflictType.WorldProgression.INSTANCE));

        assertEquals("conflictType Revision is a replacement, use Evidence.superseded", revision.getMessage());
        assertEquals("conflictType WorldProgression is a replacement, use Evidence.superseded",
                worldChange.getMessage());
    }

    @Test
    void bothFactoriesAcceptACustomConflictType() {
        ConflictType custom = new ConflictType.Custom("policy-change");

        Evidence.Conflicting conflicting = Evidence.conflicting(List.of(moved, stayed), custom);
        Evidence.Superseded superseded = Evidence.superseded(moved, List.of(stayed), custom);

        assertEquals(custom, conflicting.getConflictType());
        assertEquals(custom, superseded.getConflictType());
    }

    @Test
    void supersededValuesFromEqualPartsAreEqualAndHideClaimText() {
        Evidence.Superseded first = Evidence.superseded(moved, List.of(stayed), ConflictType.Revision.INSTANCE);
        Evidence.Superseded second = Evidence.superseded(moved, List.of(stayed), ConflictType.Revision.INSTANCE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, Evidence.superseded(moved, List.of(stayed), ConflictType.WorldProgression.INSTANCE));
        assertNotEquals(first, Evidence.superseded(stayed, List.of(moved), ConflictType.Revision.INSTANCE));
        assertFalse(first.toString().contains("London"), first.toString());
        assertFalse(first.toString().contains("Marylebone"), first.toString());
        assertTrue(first.toString().startsWith("Superseded(current="), first.toString());
    }

    @Test
    void ruleTextLeavesOutTheExpressionAndParameterValues() {
        DerivationRule rule = DerivationRule.of("prolog")
                .withRuleId("grandparent")
                .withExpression("grandparent(X, Y)")
                .withParameter("maxHops", "secret-value");

        String text = rule.toString();

        assertFalse(text.contains("grandparent(X, Y)"), text);
        assertFalse(text.contains("secret-value"), text);
        assertTrue(text.contains("hasExpression=true"), text);
        assertTrue(text.contains("maxHops"), text);
        assertEquals(
                "DerivationRule(method=prolog, ruleId=grandparent, hasExpression=true, parameterNames=[maxHops])",
                text);
    }

    @Test
    void derivedTextLeavesOutTheConclusionAndTheRuleExpression() {
        DerivationRule rule = DerivationRule.of("prolog").withExpression("grandparent(X, Y)");
        Evidence.Derived grandparent =
                Evidence.derived("Ada is a grandparent of Byron", 0.8, rule, List.of("p-17"));

        String text = grandparent.toString();

        assertFalse(text.contains("Ada is a grandparent of Byron"), text);
        assertFalse(text.contains("grandparent(X, Y)"), text);
        assertEquals("Derived(confidence=0.8, rule=" + rule + ", inputIds=[p-17])", text);
    }

    @Test
    void derivedValuesFromEqualPartsAreEqual() {
        DerivationRule rule = DerivationRule.of("prolog").withRuleId("grandparent").withParameter("maxHops", "3");
        Evidence.Derived first = Evidence.derived("Ada was a programmer", 0.7, rule, List.of("p-1", "p-2"))
                .withInputs(List.of(source2));
        Evidence.Derived second = Evidence.derived("Ada was a programmer", 0.7, rule, List.of("p-1", "p-2"))
                .withInputs(List.of(source2));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, second.withInputs(List.of(source1)));
    }

    /**
     * Names the kind of an evidence value with a switch that has no default branch.
     *
     * @param evidence the value to name
     * @return a different label for each of the five kinds
     */
    private static String kind(Evidence evidence) {
        return switch (evidence) {
            case Evidence.Recorded recorded -> "recorded";
            case Evidence.Derived derived -> "derived";
            case Evidence.Missing missing -> "missing";
            case Evidence.Conflicting conflicting -> "conflicting";
            case Evidence.Superseded superseded -> "superseded";
        };
    }

    /**
     * Describes a disagreement with a switch over its two kinds and no default branch.
     *
     * @param disagreement the disagreement to describe
     * @return the number of claims for a conflict, or the current and replaced claim ids for a replacement
     */
    private static String disagreement(Evidence.Disagreement disagreement) {
        return switch (disagreement) {
            case Evidence.Conflicting conflicting -> conflicting.getItems().size() + " claims disagree";
            case Evidence.Superseded superseded -> superseded.getCurrent().getCitation().getPropositionId()
                    + " replaced " + superseded.getPrevious().get(0).getCitation().getPropositionId();
        };
    }
}
