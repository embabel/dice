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

import com.embabel.dice.common.filter.CompositeMentionFilter;
import com.embabel.dice.common.filter.GroundedMentionFilter;
import com.embabel.dice.common.filter.MentionFilter;
import com.embabel.dice.common.filter.MentionFixtures;
import com.embabel.dice.common.filter.PropositionDuplicateFilter;
import com.embabel.dice.common.validation.ValidationResult;
import com.embabel.dice.proposition.SuggestedProposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedMentionFilterJavaInteropTest {

    @Test
    void acceptsASpanInTheTextAndRejectsOneThatIsNot() {
        MentionFilter grounding = new GroundedMentionFilter();

        assertTrue(grounding.isValid(MentionFixtures.mention("Alice"), "Alice met Bob"));
        assertFalse(grounding.isValid(MentionFixtures.mention("Carol"), "Alice met Bob"));

        List<ValidationResult> results = grounding.validateMentions(
                MentionFixtures.proposition("Alice met Bob", MentionFixtures.mention("Carol")), MentionFixtures.context());
        assertEquals(1, results.size());
        ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class, results.get(0));
        assertEquals("SPAN_NOT_IN_PROPOSITION", invalid.getReason());
    }

    @Test
    void acceptsAKnownEntityTheTextNames() {
        MentionFilter grounding = new GroundedMentionFilter();
        SuggestedProposition proposition =
                MentionFixtures.proposition("Tom moved to Paris", MentionFixtures.mention("Thomas", "user-1"));

        List<ValidationResult> known = grounding.validateMentions(
                proposition, MentionFixtures.context(MentionFixtures.knownPerson("user-1", "Thomas", "Tom")));
        assertEquals(List.of(ValidationResult.Valid.INSTANCE), known);

        List<ValidationResult> unknown = grounding.validateMentions(proposition, MentionFixtures.context());
        assertEquals(1, unknown.size());
        assertInstanceOf(ValidationResult.Invalid.class, unknown.get(0));
    }

    @Test
    void composesWithOtherFilters() {
        MentionFilter composite =
                new CompositeMentionFilter(List.of(new GroundedMentionFilter(), new PropositionDuplicateFilter(50)));

        List<ValidationResult> results = composite.validateMentions(
                MentionFixtures.proposition("Alice met Bob", MentionFixtures.mention("Alice")), MentionFixtures.context());
        assertEquals(List.of(ValidationResult.Valid.INSTANCE), results);
    }
}
