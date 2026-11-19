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
package example.dice.backends;

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Remembers facts about the companies Acme deals with, and answers questions about them.
 *
 * <p>This is all the storage code the application has. It only knows the
 * {@link PropositionRepository} interface, so it runs unchanged on the in-memory store and on
 * Neo4j. Spring hands it whichever repository {@code embabel.dice.store.type} selects.
 *
 * <p>Every fact is stored in the DICE context {@value #CONTEXT}.
 */
@Service
public class MemoryDesk {

    /** The DICE context every fact on this desk belongs to. */
    public static final String CONTEXT = "acme";

    /** How fast a fact loses confidence. Low, since what the desk is told stays true for a while. */
    private static final double DECAY = 0.01;

    /** How much a fact matters, on DICE's 0 to 1 scale. Every fact here counts the same. */
    private static final double IMPORTANCE = 0.5;

    private final PropositionRepository propositions;

    /**
     * @param propositions the fact store, whichever backend it is
     */
    public MemoryDesk(PropositionRepository propositions) {
        this.propositions = propositions;
    }

    /**
     * Stores a fact about one entity.
     *
     * @param text       the fact, as a sentence
     * @param entityId   the id of the entity the fact is about
     * @param entityName the entity's name as the sentence spells it
     * @param entityType the entity's type, such as {@code Company}
     * @param confidence how sure the desk is, from 0 to 1
     * @return the fact as stored
     */
    public Proposition remember(String text, String entityId, String entityName, String entityType, double confidence) {
        var now = Instant.now();
        var mention = new EntityMention(entityName, entityType, entityId, MentionRole.SUBJECT);
        var fact = Proposition.create(
                UUID.randomUUID().toString(),
                CONTEXT,
                text,
                List.of(mention),
                confidence,
                DECAY,
                IMPORTANCE,
                null,
                List.of(),
                now,
                now,
                now,
                PropositionStatus.ACTIVE);
        return propositions.save(fact);
    }

    /**
     * The facts the desk currently holds about one entity.
     *
     * @param entityId the id the facts were remembered under
     * @return the active facts that mention it
     */
    public List<Proposition> about(String entityId) {
        return propositions.query(
                PropositionQuery.againstContext(CONTEXT)
                        .withStatus(PropositionStatus.ACTIVE)
                        .withEntityId(entityId));
    }

    /**
     * Marks a fact as wrong. It stays stored, as {@code CONTRADICTED}, and {@link #about} stops
     * returning it.
     *
     * @param id the fact's id
     * @return the fact as stored now
     * @throws IllegalArgumentException when there is no fact with that id
     */
    public Proposition correct(String id) {
        var fact = propositions.findById(id);
        if (fact == null) {
            throw new IllegalArgumentException("No fact with id " + id);
        }
        return propositions.save(fact.withStatus(PropositionStatus.CONTRADICTED));
    }

    /**
     * Looks one fact up, whatever its status.
     *
     * @param id the fact's id
     * @return the fact, or null when there is none
     */
    public @Nullable Proposition get(String id) {
        return propositions.findById(id);
    }
}
