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

import com.embabel.dice.common.NameNormalizer;
import com.embabel.dice.common.PersonNameNormalizer;
import com.embabel.dice.common.filter.GroundedMentionFilter;
import com.embabel.dice.common.resolver.InMemoryEntityResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The name rules as a Java caller writes them: the factories, the chain and a lambda.
 */
class NameNormalizerJavaInteropTest {

    private static final List<String> PERSON = List.of("Person");

    @Test
    void theStandardNormalizerIsReachedWithoutCompanion() {
        NameNormalizer standard = NameNormalizer.standard();

        assertEquals("Watson", standard.normalize("Dr. Watson", PERSON));
        assertEquals("Series A Round", standard.normalize("Series A Round", List.of("FundingRound")));
        assertEquals("Dr. Watson", NameNormalizer.whitespaceOnly().normalize(" Dr.  Watson ", PERSON));
        assertEquals("Person", PersonNameNormalizer.PERSON_LABEL);
    }

    @Test
    void theChainTunesTheRulesWithVarargs() {
        PersonNameNormalizer german = PersonNameNormalizer.english()
                .withPersonLabels("Person", "Contact")
                .withTitles("Herr", "Frau")
                .withSuffixes("sen", "jun")
                .withMiddleInitialsKept()
                .withNameOrderKept();

        assertEquals("Müller", german.normalize("Herr Müller", List.of("Contact")));
        assertEquals("Hans M Müller", german.normalize("Hans M Müller sen.", PERSON));
        assertEquals("Series Round", PersonNameNormalizer.english().forEveryName().normalize("Series A Round", List.of()));
        assertEquals("Dr. Watson", PersonNameNormalizer.english().withTitles().normalize("Dr. Watson", PERSON));
    }

    @Test
    void aLambdaIsANormalizer() {
        NameNormalizer upper = (name, labels) -> name.toUpperCase();

        assertEquals("WATSON", upper.normalize("Watson", PERSON));
    }

    @Test
    void resolversAndTheGroundingCheckTakeANormalizer() {
        NameNormalizer normalizer = NameNormalizer.whitespaceOnly();
        InMemoryEntityResolver resolver = new InMemoryEntityResolver();
        GroundedMentionFilter filter = new GroundedMentionFilter();

        assertNotSame(resolver, resolver.withNameNormalizer(normalizer));
        assertNotSame(filter, filter.withNameNormalizer(normalizer));
        assertNotSame(resolver, new InMemoryEntityResolver(new InMemoryEntityResolver.Config()));
    }
}
