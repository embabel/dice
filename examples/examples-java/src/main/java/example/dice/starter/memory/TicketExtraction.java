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
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.ProvenanceSubtractionCapable;
import com.embabel.dice.proposition.SourceRevisionQueryCapable;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.ProvenanceEvidenceKey;
import com.embabel.dice.provenance.SourceRevisionRef;
import example.dice.starter.tickets.Ticket.TicketFiled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns each filed ticket into stored facts.
 */
@Component
class TicketExtraction {

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final AccountStores accountStores;
    private final PropositionRepository facts;
    private final SourceRevisionQueryCapable sourceQueries;
    private final ProvenanceSubtractionCapable evidence;

    /**
     * @param pipeline      turns ticket text into unsaved facts
     * @param schema        the extraction schema. The agent platform is a
     *                      {@code DataDictionary} bean as well, so the qualifier names this one.
     * @param accountStores what is kept for each account, including the fact store
     * @throws IllegalStateException when the fact store cannot find facts by their source, or
     *                               cannot take one source's evidence off a fact
     */
    TicketExtraction(PropositionPipeline pipeline, @Qualifier("supportSchema") DataDictionary schema,
            AccountStores accountStores) {
        // Both abilities are read from the store the writer saves to, so the lookups below see
        // exactly what was written. A store can implement an interface and still be unable to
        // answer, for example when it wraps a backend without that feature, so the flag is checked
        // as well as the type.
        var facts = accountStores.facts();
        if (!(facts instanceof SourceRevisionQueryCapable sourceQueries)
                || !sourceQueries.getSupportsSourceRevisionQueries()) {
            throw new IllegalStateException(
                    "The fact store cannot find facts by the ticket they were read from. "
                            + "Ticket extraction needs that to skip a ticket it has stored and to "
                            + "replace what an edited ticket said.");
        }
        if (!(facts instanceof ProvenanceSubtractionCapable evidence)
                || !evidence.getSupportsProvenanceSubtraction()) {
            throw new IllegalStateException(
                    "The fact store cannot take one ticket's evidence off a fact. Ticket extraction "
                            + "needs that to replace what an edited ticket said and keep a fact "
                            + "another ticket also says.");
        }
        this.pipeline = pipeline;
        this.schema = schema;
        this.accountStores = accountStores;
        this.facts = facts;
        this.sourceQueries = sourceQueries;
        this.evidence = evidence;
    }

    /**
     * Extracts facts from a filed ticket and stores them under the ticket's account. A revision
     * of the ticket that is already stored, or older than one that is, is skipped. A newer
     * revision replaces what the earlier ones said.
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
            // The same revision delivered again is the usual repeat, and a lookup by exact
            // revision finds it without reading the rest of the ticket's history.
            if (!sourceQueries.findBySourceRevision(account.value(), revision).isEmpty()) {
                return;
            }

            // What the fact store holds for this ticket, from every revision and with any status.
            // This is the record of what has been stored, so it holds across restarts. An older
            // revision delivered late is skipped: storing it would replace what a newer one said.
            var stored = sourceQueries.findBySourceKey(account.value(), source.key());
            if (latestStoredRevision(stored, source) >= ticket.revision()) {
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
            // ticket. A failed model call throws here, before anything is changed, and because
            // the listener is synchronous the exception reaches the caller of Tickets.file.
            var extracted = pipeline.processText(ticket.text(), ticketId, context);

            // The earlier revisions are retired before the new one is written. If a later step
            // fails, the new revision is not stored, so the next delivery of it extracts it again
            // and finishes the job.
            retireEarlierRevisions(stored, source);

            // processText returns unsaved results. The writer stores the new facts and their
            // entities as one unit. When a step fails it puts the facts and entities back as they
            // were and persistedOrThrow throws. A relationship written before the failure stays
            // in the entity store.
            stores.writer()
                    .write(PersistUnit.of(
                            extracted.propositionsToPersist(), extracted.newEntities(), extracted.updatedEntities()))
                    .persistedOrThrow();
        } finally {
            stores.lock().unlock();
        }
    }

    /**
     * Takes away what a ticket's earlier revisions said. A fact only this ticket supports becomes
     * superseded, which takes it out of CustomerMemory and keeps it stored. A fact another ticket
     * also supports loses this ticket's evidence and stays current.
     *
     * <p>Only the facts' status, evidence and grounding change. The entities they named stay in
     * the account's entity store, since another ticket may name them too.
     *
     * <p>A fact that loses this ticket's evidence no longer records the earlier revision. When
     * every fact of that revision is kept this way and the new revision gives no facts, nothing
     * stored names either revision, so a late copy of the earlier one is extracted again.
     *
     * @param stored the facts read from the ticket, as found before extraction
     * @param source the ticket
     */
    private void retireEarlierRevisions(List<Proposition> stored, ConnectorRef source) {
        for (var fact : stored) {
            if (fact.getStatus() != PropositionStatus.ACTIVE) {
                continue;
            }
            var fromTicket = fact.getProvenanceEntries().stream()
                    .filter(entry -> entry.getLocator().key().equals(source.key()))
                    .toList();
            if (fromTicket.size() == fact.getProvenanceEntries().size()) {
                // The account's lock keeps its other tickets from adding evidence in between.
                facts.save(fact.withStatus(PropositionStatus.SUPERSEDED));
            } else {
                // The ticket's grounding goes with its evidence, so the fact no longer cites the
                // ticket. Both come off in one step.
                var keys = fromTicket.stream().map(ProvenanceEvidenceKey.INSTANCE::encode).toList();
                var grounding = fromTicket.stream()
                        .map(entry -> entry.getChunkId())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                evidence.subtractFoldedEvidence(fact.getId(), keys, grounding, List.of());
            }
        }
    }

    /**
     * The newest revision of a ticket that stored facts, or 0 when none did.
     *
     * <p>Superseded facts count, so a late copy of a revision that has been replaced is still
     * recognised. A revision whose text gave no facts leaves nothing behind to count.
     *
     * @param stored the facts read from the ticket
     * @param source the ticket
     * @return the highest revision number among the facts' provenance
     */
    private static int latestStoredRevision(List<Proposition> stored, ConnectorRef source) {
        return stored.stream()
                .flatMap(fact -> fact.getProvenanceEntries().stream())
                .filter(entry -> entry.getLocator().key().equals(source.key()))
                .map(entry -> entry.getSourceRevision())
                .filter(Objects::nonNull)
                .mapToInt(TicketExtraction::revisionNumber)
                .max()
                .orElse(0);
    }

    /**
     * Reads a revision this class wrote. DICE treats a revision as an opaque string, and these
     * are whole numbers only because {@link #on} writes them that way. A revision something else
     * wrote has no place in that order, so it counts as 0.
     *
     * @param revision a stored source revision
     * @return its number, or 0 when it is not one
     */
    private static int revisionNumber(String revision) {
        try {
            return Integer.parseInt(revision);
        } catch (NumberFormatException notOurs) {
            return 0;
        }
    }
}
