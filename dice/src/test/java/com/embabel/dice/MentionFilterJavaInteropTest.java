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

import com.embabel.dice.common.KnownEntity;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.filter.CompositeMentionFilter;
import com.embabel.dice.common.filter.MentionFilter;
import com.embabel.dice.common.filter.MentionFixtures;
import com.embabel.dice.common.validation.ValidationResult;
import com.embabel.dice.proposition.SuggestedProposition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class MentionFilterJavaInteropTest {

    private final MentionFilter longSpans = (mention, text) -> mention.getSpan().length() > 2;

    static class KnownEntitiesOnly implements MentionFilter {
        @Override
        public boolean isValid(com.embabel.dice.proposition.SuggestedMention mention, String propositionText) {
            return true;
        }

        @Override
        public List<ValidationResult> validateMentions(SuggestedProposition proposition, SourceAnalysisContext context) {
            Set<String> names = context.getKnownEntities().stream()
                    .map(KnownEntity::getName)
                    .collect(Collectors.toSet());
            return proposition.getMentions().stream()
                    .<ValidationResult>map(m -> names.contains(m.getSpan())
                            ? ValidationResult.Valid.INSTANCE
                            : new ValidationResult.Invalid("not a known entity"))
                    .collect(Collectors.toList());
        }
    }

    private final SourceAnalysisContext context =
            MentionFixtures.context(MentionFixtures.knownPerson("user-1", "Thomas", "Tom"));

    @Test
    void lambdaFilterGetsTheDefaultValidateMentions() {
        SuggestedProposition proposition = MentionFixtures.proposition(
                "Al met Alice", MentionFixtures.mention("Al"), MentionFixtures.mention("Alice"));

        List<ValidationResult> results = longSpans.validateMentions(proposition, context);

        ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class, results.get(0));
        assertEquals("rejected by a mention filter", invalid.getReason());
        assertSame(ValidationResult.Valid.INSTANCE, results.get(1));
    }

    @Test
    void filterCanReadKnownEntitiesFromTheContext() {
        SuggestedProposition proposition = MentionFixtures.proposition(
                "Thomas met Zed", MentionFixtures.mention("Thomas"), MentionFixtures.mention("Zed"));

        List<ValidationResult> results = new KnownEntitiesOnly().validateMentions(proposition, context);

        assertSame(ValidationResult.Valid.INSTANCE, results.get(0));
        ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class, results.get(1));
        assertEquals("not a known entity", invalid.getReason());
    }

    @Test
    void compositeAppliesBothFilters() {
        SuggestedProposition proposition = MentionFixtures.proposition(
                "Al met Thomas and Zed",
                MentionFixtures.mention("Al"), MentionFixtures.mention("Thomas"), MentionFixtures.mention("Zed"));

        List<ValidationResult> results =
                new CompositeMentionFilter(List.of(longSpans, new KnownEntitiesOnly())).validateMentions(proposition, context);

        assertEquals(3, results.size());
        assertEquals("rejected by a mention filter", ((ValidationResult.Invalid) results.get(0)).getReason());
        assertSame(ValidationResult.Valid.INSTANCE, results.get(1));
        assertEquals("not a known entity", ((ValidationResult.Invalid) results.get(2)).getReason());
    }
}
