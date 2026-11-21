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
package example.dice.corrections;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.provenance.ConnectorRef;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Takes in one message at a time about the "acme" account and remembers what it says.
 */
@Component
public class CorrectionsDesk {

    /** The one account the desk hears about. Every fact carries it as its context. */
    static final String ACCOUNT = "acme";

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final EntityResolver resolver;
    private final PersistUnitWriter writer;

    /**
     * @param pipeline turns a message into revised, unsaved facts
     * @param schema   the extraction schema. The agent platform is a {@code DataDictionary}
     *                 bean as well, so the qualifier names this one.
     * @param resolver looks mentions up in the account's entities
     * @param writer   stores a message's facts and entities as one unit
     */
    CorrectionsDesk(PropositionPipeline pipeline, @Qualifier("correctionsSchema") DataDictionary schema,
            EntityResolver resolver, PersistUnitWriter writer) {
        this.pipeline = pipeline;
        this.schema = schema;
        this.resolver = resolver;
        this.writer = writer;
    }

    /**
     * Runs one message through extraction and revision, stores the outcome, and says what
     * revision decided for each fact in it.
     *
     * <p>Messages are taken one at a time. Each is revised against what the earlier ones
     * stored, and the entities a message creates are only stored by the write at the end, so
     * two messages handled at once could each create their own Acme.
     *
     * @param source    where the message came from, {@code "billing"} or {@code "chat"}. It is
     *                  how far the facts are trusted.
     * @param messageId the message's id in that source. It becomes the facts' grounding.
     * @param text      what the message says
     * @return one revision result for each fact the message gave: new, merged, reinforced,
     *         contradicted or generalized
     * @throws IllegalStateException when the facts or entities could not be stored
     */
    public synchronized List<RevisionResult> tell(String source, String messageId, String text) {
        var context = SourceAnalysisContext
                .withContextId(ACCOUNT)
                // Searches the account's entities by name, so "Acme" in a later message
                // resolves to the entity the first message created.
                .withEntityResolver(resolver)
                .withSchema(schema)
                // Off by default. The messages are about the desk's own customer, so the names
                // they mention become entities.
                .withMintNewEntities(true)
                // Every fact's provenance names the source and message. SourceAuthority reads
                // it to decide how far to trust the fact.
                .withSourceLocator(new ConnectorRef(source, messageId));

        var extracted = pipeline.processText(text, messageId, context);

        // processText returns unsaved results. The facts to store include the new ones, the
        // stored ones a repeat or a reinforcement changed, and the originals a contradiction
        // marked CONTRADICTED.
        writer.write(PersistUnit.of(
                        extracted.propositionsToPersist(), extracted.newEntities(), extracted.updatedEntities()))
                .persistedOrThrow();

        return extracted.getRevisionResults();
    }
}
