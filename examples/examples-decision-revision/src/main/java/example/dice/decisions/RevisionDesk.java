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
package example.dice.decisions;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.provenance.ConnectorRef;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Takes messages about the account "acme" one at a time and remembers what they say.
 */
@Service
public class RevisionDesk {

    /** The one account this desk serves. It is the DICE context of every fact. */
    public static final String ACCOUNT = "acme";

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final EntityResolver resolver;
    private final PersistUnitWriter writer;

    /**
     * @param pipeline extracts facts and revises them with the decision reviser
     * @param schema   the desk's schema
     * @param resolver looks mentions up in the account's entities
     * @param writer   stores each message's results as one unit
     */
    RevisionDesk(PropositionPipeline pipeline, @Qualifier("revisionDictionary") DataDictionary schema,
            EntityResolver resolver, PersistUnitWriter writer) {
        this.pipeline = pipeline;
        this.schema = schema;
        this.resolver = resolver;
        this.writer = writer;
    }

    /**
     * Runs one message through the pipeline and stores what came out: new facts, and stored
     * facts the decision merged, reinforced or contradicted.
     *
     * <p>One message is handled at a time. Mentions are resolved inside the pipeline and the
     * entities it creates are only stored by the write that follows, so two messages at once
     * could each create the same customer.
     *
     * @param messageId the message's id, which becomes the grounding of its facts
     * @param text      what the message says
     * @return what each extracted fact did to the stored ones
     */
    public synchronized List<RevisionResult> tell(String messageId, String text) {
        var context = SourceAnalysisContext
                .withContextId(ACCOUNT)
                .withEntityResolver(resolver)
                .withSchema(schema)
                // Messages are the desk's own records, so the customers and plans they name
                // become entities, and later facts about them have something to match on.
                .withMintNewEntities(true)
                .withSourceLocator(new ConnectorRef("messages", messageId));

        var extracted = pipeline.processText(text, messageId, context);
        writer.write(PersistUnit.of(extracted)).persistedOrThrow();
        return extracted.getRevisionResults();
    }
}
