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

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows that Java can build an EntityMention from just a span and a type,
 * with the optional fields filled in by the shorter constructors.
 */
class EntityMentionJavaInteropTest {

    @Test
    void spanAndTypeAreEnough() {
        EntityMention mention = new EntityMention("Alice", "Person");

        assertEquals("Alice", mention.getSpan());
        assertEquals("Person", mention.getType());
        assertNull(mention.getResolvedId());
        assertEquals(MentionRole.OTHER, mention.getRole());
        assertTrue(mention.getHints().isEmpty());
    }

    @Test
    void resolvedIdAndRoleCanFollow() {
        assertEquals("person-1", new EntityMention("Alice", "Person", "person-1").getResolvedId());
        assertEquals(MentionRole.SUBJECT, new EntityMention("Alice", "Person", "person-1", MentionRole.SUBJECT).getRole());
    }

    @Test
    void shortAndFullConstructorsAgree() {
        assertEquals(new EntityMention("Alice", "Person", null, MentionRole.OTHER, Map.of()),
                new EntityMention("Alice", "Person"));
    }
}
