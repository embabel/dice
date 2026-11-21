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
package com.embabel.dice.query.graph;

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shows that Java can build a GraphQuery with the static factories, passing the context id
 * as a plain string, and that the scope and hop limit it asks for are the ones it gets.
 */
class GraphQueryJavaInteropTest {

    private InMemoryPropositionRepository store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPropositionRepository();
        // Context "one" holds the chain A - B - C. Context "two" links A to D.
        store.save(edge("p-ab", "one", "A", "B"));
        store.save(edge("p-bc", "one", "B", "C"));
        store.save(edge("p-ad", "two", "A", "D"));
    }

    @Test
    void contextQuerySeesOnlyItsOwnEdges() {
        GraphQuery query = GraphQuery.of(store, "one");

        assertEquals(Set.of("B"), neighbourIds(query.neighborhood("A", 1, null)));
    }

    @Test
    void unscopedQuerySeesEveryContext() {
        GraphQuery query = GraphQuery.of(store);

        assertEquals(Set.of("B", "D"), neighbourIds(query.neighborhood("A", 1, null)));
    }

    @Test
    void maxDepthLimitsTheWalk() {
        GraphQuery shallow = GraphQuery.of(store, "one", 1);
        GraphQuery deep = GraphQuery.of(store, "one", 5);

        assertEquals(Set.of("B"), neighbourIds(shallow.neighborhood("A", 2, null)));
        assertEquals(Set.of("B", "C"), neighbourIds(deep.neighborhood("A", 2, null)));
    }

    @Test
    void depthBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GraphQuery.of(store, "one", 0));
    }

    @Test
    void blankContextIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GraphQuery.of(store, " "));
    }

    private static Proposition edge(String id, String contextId, String from, String to) {
        Instant now = Instant.now();
        List<EntityMention> mentions = List.of(
                new EntityMention(from, "Thing", from, MentionRole.SUBJECT),
                new EntityMention(to, "Thing", to, MentionRole.OBJECT));
        return Proposition.create(id, contextId, from + " relates to " + to, mentions,
                0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE);
    }

    private static Set<String> neighbourIds(GraphNeighborhood neighborhood) {
        return neighborhood.getNeighbours().stream()
                .map(RelatedEntity::getEntityId)
                .collect(Collectors.toSet());
    }
}
