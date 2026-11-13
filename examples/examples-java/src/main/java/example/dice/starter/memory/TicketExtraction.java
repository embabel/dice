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
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.common.support.Sha256ContentHasher;
import com.embabel.dice.incremental.BookmarkKey;
import com.embabel.dice.incremental.ChunkHistoryStore;
import com.embabel.dice.incremental.HashKey;
import com.embabel.dice.incremental.ProcessedChunkRecord;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.PropositionRepository;
import example.dice.starter.tickets.Ticket.TicketFiled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Turns each filed ticket into stored facts.
 */
@Component
class TicketExtraction {

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final AccountEntities accountEntities;
    private final PropositionRepository propositions;
    private final ChunkHistoryStore history;

    /**
     * @param pipeline        turns ticket text into unsaved facts
     * @param schema          the extraction schema. The agent platform is a
     *                        {@code DataDictionary} bean as well, so the qualifier names this one.
     * @param accountEntities where each account's entities are stored
     * @param propositions    where facts are stored
     * @param history         remembers which tickets have been stored
     */
    TicketExtraction(PropositionPipeline pipeline, @Qualifier("supportSchema") DataDictionary schema,
            AccountEntities accountEntities, PropositionRepository propositions, ChunkHistoryStore history) {
        this.pipeline = pipeline;
        this.schema = schema;
        this.accountEntities = accountEntities;
        this.propositions = propositions;
        this.history = history;
    }

    /**
     * Extracts facts from a filed ticket and stores them under the ticket's account.
     *
     * @param event the ticket that was filed
     */
    @EventListener
    void on(TicketFiled event) {
        var ticket = event.ticket();
        var account = ticket.account();
        var ticketId = ticket.id().value();

        // A ticket can be filed again, for example when the event is delivered twice. The key
        // is the ticket id and its text, so the same ticket is skipped, a different ticket with
        // the same words is not, and an edited ticket is extracted again.
        var stored = HashKey.of(account.value(), Sha256ContentHasher.INSTANCE.hash(ticketId + "\n" + ticket.text()));
        if (history.isProcessed(stored)) {
            return;
        }

        // The resolver searches the store it is given by name. This one holds only the
        // account's entities, so "Acme" in a second ticket resolves to the entity the first
        // ticket created, and a name another account uses is a different entity.
        var entities = accountEntities.of(account);

        // The account is the context. Every fact from this ticket carries it, and every read
        // in CustomerMemory starts from it.
        var context = SourceAnalysisContext
                .withContextId(account.value())
                .withEntityResolver(EscalatingEntityResolver.withoutVector(entities))
                .withSchema(schema)
                // Off by default. Tickets are the support desk's own records, so the customers
                // and contacts they name become entities.
                .withMintNewEntities(true);

        // The ticket id becomes the source id, so every fact points back at its ticket.
        // A failed model call throws here. Nothing is stored for the ticket, and because the
        // listener is synchronous the exception reaches the caller of Tickets.file.
        var extracted = pipeline.processText(ticket.text(), ticketId, context);

        // processText returns unsaved results.
        extracted.persist(propositions, entities);

        // The ticket is marked as stored only after its facts are saved. If saving fails, the
        // ticket is extracted again the next time it is filed.
        history.recordProcessed(new ProcessedChunkRecord(
                BookmarkKey.of(account.value(), ticketId), stored, 0, 1, Instant.now()));
    }
}
