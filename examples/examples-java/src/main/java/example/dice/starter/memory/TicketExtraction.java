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
package example.dice.starter.memory;

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.PropositionRepository;
import example.dice.starter.tickets.Ticket.TicketFiled;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns each filed ticket into stored facts.
 */
@Component
class TicketExtraction {

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final EntityResolver entityResolver;
    private final PropositionRepository propositions;
    private final NamedEntityDataRepository entities;

    /**
     * @param pipeline       turns ticket text into unsaved facts
     * @param supportSchema  the extraction schema, injected by bean name. The agent platform
     *                       is a {@code DataDictionary} bean as well.
     * @param entityResolver matches mentions to stored entities
     * @param propositions   where facts are stored
     * @param entities       where entities are stored
     */
    TicketExtraction(PropositionPipeline pipeline, DataDictionary supportSchema, EntityResolver entityResolver,
            PropositionRepository propositions, NamedEntityDataRepository entities) {
        this.pipeline = pipeline;
        this.schema = supportSchema;
        this.entityResolver = entityResolver;
        this.propositions = propositions;
        this.entities = entities;
    }

    /**
     * Extracts facts from a filed ticket and stores them under the ticket's account.
     *
     * @param event the ticket that was filed
     */
    @EventListener
    void on(TicketFiled event) {
        var ticket = event.ticket();

        // The account is the context. Every fact from this ticket carries it, and every read
        // in CustomerMemory starts from it.
        var context = SourceAnalysisContext
                .withContextId(ticket.account().value())
                .withEntityResolver(entityResolver)
                .withSchema(schema)
                // Off by default. Tickets are the support desk's own records, so the customers
                // and contacts they name become entities.
                .withMintNewEntities(true);

        // The ticket id becomes the source id, so every fact points back at its ticket.
        // A failed model call throws here. Nothing is stored for the ticket, and because the
        // listener is synchronous the exception reaches the caller of Tickets.file.
        var extracted = pipeline.processText(ticket.text(), ticket.id().value(), context);

        // processText returns unsaved results.
        extracted.persist(propositions, entities);
    }
}
