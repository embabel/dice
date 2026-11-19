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
package example.dice.support;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.PropositionPersistenceResult;
import com.embabel.dice.proposition.extraction.PersistUnit;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.provenance.ConnectorRef;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The front door of the support desk. Each chat goes through DICE once, and what it says is
 * stored under the Acme account.
 */
@Component
public class SupportDesk {

    /**
     * The one account this desk serves. It is the DICE context every fact is stored under and
     * every query starts from.
     */
    public static final String ACCOUNT = "acme";

    private final PropositionPipeline pipeline;
    private final DataDictionary schema;
    private final EntityResolver resolver;
    private final PersistUnitWriter writer;

    /**
     * @param pipeline turns chat text into unsaved facts
     * @param schema   the extraction schema. The agent platform has a {@code DataDictionary}
     *                 bean as well, so the qualifier names this one.
     * @param resolver finds the entity a mention names among those already stored
     * @param writer   stores a chat's facts and new entities as one unit
     */
    SupportDesk(PropositionPipeline pipeline, @Qualifier("supportDictionary") DataDictionary schema,
            @Qualifier("supportResolver") EntityResolver resolver,
            @Qualifier("supportWriter") PersistUnitWriter writer) {
        this.pipeline = pipeline;
        this.schema = schema;
        this.resolver = resolver;
        this.writer = writer;
    }

    /**
     * Reads one chat and stores the facts and entities DICE finds in it.
     *
     * @param chatId names the chat. It is the source of every fact read from the chat, and each
     *               fact's grounding points back at it.
     * @param text   what was said in the chat
     * @return what was stored
     * @throws RuntimeException when the model call or the write fails. Nothing is stored then.
     */
    public PropositionPersistenceResult chat(String chatId, String text) {
        var context = SourceAnalysisContext
                .withContextId(ACCOUNT)
                // The resolver searches the entities earlier chats created, so "Acme" in this
                // chat is the same entity as "Acme" in the last one.
                .withEntityResolver(resolver)
                .withSchema(schema)
                // Off by default. The chats are the desk's own records, so the customers,
                // contacts and plans they name become entities.
                .withMintNewEntities(true)
                // Every fact's provenance names the chat it came from.
                .withSourceLocator(new ConnectorRef("chat", chatId));

        // The chat id becomes the chunk id. processText returns unsaved results, and a failed
        // model call throws here before anything is stored.
        var extracted = pipeline.processText(text, chatId, context);

        // The facts and the entities they name are stored together. When a step fails the
        // writer puts both stores back, and persistedOrThrow throws.
        return writer
                .write(PersistUnit.of(
                        extracted.propositionsToPersist(),
                        extracted.newEntities(),
                        extracted.updatedEntities()))
                .persistedOrThrow();
    }
}
