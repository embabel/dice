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
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.SourceRevisionQueryCapable;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.SourceRevisionRef;
import example.dice.starter.tickets.Ticket.TicketFiled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
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
     * @param sourceQueries finds the stored facts that were read from a ticket. The fact store
     *                      implements it.
     * @throws IllegalStateException when the fact store cannot find facts by their source
     */
    TicketExtraction(PropositionPipeline pipeline, @Qualifier("supportSchema") DataDictionary schema,
            AccountStores accountStores, SourceRevisionQueryCapable sourceQueries) {
        // A store can implement the interface and still be unable to answer, for example when it
        // wraps a backend without source queries. It would then return nothing, every filing
        // would look new, and an edited ticket would keep what its earlier text said.
        if (!sourceQueries.getSupportsSourceRevisionQueries()) {
            throw new IllegalStateException(
                    "The fact store cannot find facts by the ticket they were read from. "
                            + "Ticket extraction needs that to skip a ticket it has stored and to "
                            + "replace what an edited ticket said.");
        }
        this.pipeline = pipeline;
        this.schema = schema;
        this.accountStores = accountStores;
        this.sourceQueries = sourceQueries;
    }

    /**
     * Extracts facts from a filed ticket and stores them under the ticket's account. A revision
     * of the ticket that is already stored, or older than one that is, is skipped. A newer
     * revision replaces the facts the earlier ones stored.
     *
     * @param event the ticket that was filed
     */
    @EventListener
    void on(TicketFiled event) {
        var ticket = event.ticket();
        var account = ticket.account();
        var ticketId = ticket.id().value();
        var stores = accountStores.of(account);

        // The ticket is the source of its facts, and its revision number is the version they
        // were read from. Both are stored with every fact.
        var source = new ConnectorRef("tickets", ticketId);
        var revision = new SourceRevisionRef(source.key(), Integer.toString(ticket.revision()));

        // One ticket of an account is handled at a time, and the lock is held through the model
        // call. DICE resolves the ticket's mentions inside processText, after the model replies,
        // and the entities it creates are only stored by the write below. Two tickets of one
        // account handled at once could each create the same new customer. Tickets of other
        // accounts run alongside.
        stores.lock().lock();
        try {
            // What the fact store holds for this ticket, from every revision and with any status.
            // This is the record of what has been stored, so it holds across restarts.
            var stored = sourceQueries.findBySourceKey(account.value(), source.key());

            // The same revision delivered again is skipped. So is an older revision delivered
            // late: storing it would replace what the newer one said.
            if (latestStoredRevision(stored, revision) >= ticket.revision()) {
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
                    // Every fact's provenance names the ticket and the revision it was read from.
                    .withSourceLocator(source)
                    .withSourceRevision(revision);

            // The ticket id becomes the chunk id, so every fact's grounding points back at its
            // ticket. A failed model call throws here. Nothing is stored for the ticket, and
            // because the listener is synchronous the exception reaches the caller of
            // Tickets.file.
            var extracted = pipeline.processText(ticket.text(), ticketId, context);

            // A newer revision replaces what the earlier ones said. Their facts stay stored and
            // become superseded, which takes them out of CustomerMemory. The entities they
            // named stay in the account's entity store, since another ticket may name them too.
            var replaced = stored.stream()
                    .filter(fact -> fact.getStatus() == PropositionStatus.ACTIVE)
                    .map(fact -> fact.withStatus(PropositionStatus.SUPERSEDED))
                    .toList();
            var facts = Stream.concat(replaced.stream(), extracted.propositionsToPersist().stream()).toList();

            // processText returns unsaved results. The writer stores the superseded facts, the
            // new facts and their entities as one unit. When a step fails it puts the facts and
            // entities back as they were and persistedOrThrow throws, so the next filing of
            // this revision extracts it again. A relationship written before the failure stays
            // in the entity store.
            stores.writer()
                    .write(PersistUnit.of(facts, extracted.newEntities(), extracted.updatedEntities()))
                    .persistedOrThrow();
        } finally {
            stores.lock().unlock();
        }
    }

    /**
     * The newest revision of a ticket that stored facts, or 0 when none did.
     *
     * <p>Superseded facts count, so a late copy of a revision that has been replaced is still
     * recognised. A revision whose text gave no facts leaves nothing behind to count.
     *
     * @param stored   the facts read from the ticket
     * @param revision any revision of the ticket, for its source key
     * @return the highest revision number among the facts' provenance
     */
    private static int latestStoredRevision(List<Proposition> stored, SourceRevisionRef revision) {
        return stored.stream()
                .flatMap(fact -> fact.getProvenanceEntries().stream())
                .filter(entry -> entry.getLocator().key().equals(revision.getSourceKey()))
                .map(entry -> entry.getSourceRevision())
                .filter(number -> number != null)
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
    }
}
