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
import com.embabel.dice.common.support.Sha256ContentHasher;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.SourceRevisionQueryCapable;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.SourceRevisionRef;
import example.dice.starter.tickets.Ticket.TicketFiled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

/**
 * Turns each filed ticket into stored facts.
 */
@Component
class TicketExtraction {

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final AccountStores accountStores;
    private final SourceRevisionQueryCapable sourceQueries;

    /**
     * @param pipeline      turns ticket text into unsaved facts
     * @param schema        the extraction schema. The agent platform is a
     *                      {@code DataDictionary} bean as well, so the qualifier names this one.
     * @param accountStores what is kept for each account
     * @param sourceQueries finds the stored facts that were read from a source. The fact store
     *                      implements it.
     */
    TicketExtraction(PropositionPipeline pipeline, @Qualifier("supportSchema") DataDictionary schema,
            AccountStores accountStores, SourceRevisionQueryCapable sourceQueries) {
        this.pipeline = pipeline;
        this.schema = schema;
        this.accountStores = accountStores;
        this.sourceQueries = sourceQueries;
    }

    /**
     * Extracts facts from a filed ticket and stores them under the ticket's account. A ticket
     * filed again with the same text is skipped. A ticket filed again with new text replaces
     * the facts its earlier version stored.
     *
     * @param event the ticket that was filed
     */
    @EventListener
    void on(TicketFiled event) {
        var ticket = event.ticket();
        var account = ticket.account();
        var ticketId = ticket.id().value();
        var stores = accountStores.of(account);

        // The ticket is the source of its facts. It has no version number of its own, so the
        // hash of its text is the version.
        var source = new ConnectorRef("tickets", ticketId);
        var version = new SourceRevisionRef(source.key(), Sha256ContentHasher.INSTANCE.hash(ticket.text()));

        // One ticket of an account is handled at a time. Entity resolution reads the entities
        // earlier tickets stored, and a second delivery of this ticket has to see the version
        // the first one records. Tickets of other accounts run alongside.
        stores.lock().lock();
        try {
            // Filed again with the same text, for example when the event is delivered twice.
            if (version.getSourceRevision().equals(stores.storedVersions().get(ticket.id()))) {
                return;
            }

            // The account is the context. Every fact from this ticket carries it, and every read
            // in CustomerMemory starts from it.
            var context = SourceAnalysisContext
                    .withContextId(account.value())
                    // The resolver searches the account's own entities by name, so "Acme" in a
                    // second ticket resolves to the entity the first ticket created, and a name
                    // another account uses is a different entity.
                    .withEntityResolver(stores.resolver())
                    .withSchema(schema)
                    // Off by default. Tickets are the support desk's own records, so the customers
                    // and contacts they name become entities.
                    .withMintNewEntities(true)
                    // Every fact's provenance names the ticket and the version it was read from.
                    .withSourceLocator(source)
                    .withSourceRevision(version);

            // The ticket id becomes the chunk id, so every fact's grounding points back at its
            // ticket. A failed model call throws here. Nothing is stored for the ticket, and
            // because the listener is synchronous the exception reaches the caller of
            // Tickets.file.
            var extracted = pipeline.processText(ticket.text(), ticketId, context);

            // An edited ticket replaces what its earlier version said. The facts read from
            // earlier versions stay stored and become superseded, which takes them out of
            // CustomerMemory.
            var replaced = sourceQueries.findBySourceKey(account.value(), source.key()).stream()
                    .filter(fact -> fact.getStatus() == PropositionStatus.ACTIVE)
                    .map(fact -> fact.withStatus(PropositionStatus.SUPERSEDED))
                    .toList();
            var facts = Stream.concat(replaced.stream(), extracted.propositionsToPersist().stream()).toList();

            // processText returns unsaved results. The writer stores the superseded facts, the
            // new facts and their entities as one unit. When a step fails it puts the facts and
            // entities back as they were and persistedOrThrow throws. A relationship written
            // before the failure stays in the entity store.
            stores.writer()
                    .write(PersistUnit.of(facts, extracted.newEntities(), extracted.updatedEntities()))
                    .persistedOrThrow();

            // The version is recorded only after its facts are stored. A ticket whose save
            // failed is extracted again the next time it is filed.
            stores.storedVersions().put(ticket.id(), version.getSourceRevision());
        } finally {
            stores.lock().unlock();
        }
    }
}
