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

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.DiceEventListener;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.UngroundedMentionHandling;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The DICE setup for the support desk: where facts and entities are kept, how a mention finds
 * the entity it names, and the pipeline that reads a chat.
 *
 * <p>The desk serves one account, so there is one set of stores. Everything lives in memory for
 * as long as the application runs.
 */
@Configuration(proxyBeanMethods = false)
class SupportMemory {

    /**
     * The fact store. With no embedding service it has no similarity search.
     *
     * <p>The bean has the in-memory type because {@code PersistUnitWriter.inMemory}, which puts
     * the stores back after a failed write, takes that type.
     *
     * @return an empty in-memory store
     */
    @Bean
    InMemoryPropositionRepository propositionRepository() {
        return new InMemoryPropositionRepository();
    }

    /**
     * The entity store: the customers, contacts and plans the chats have named so far.
     *
     * @param schema the schema the store reads entity types from
     * @return an empty in-memory store
     */
    @Bean
    InMemoryNamedEntityDataRepository supportEntities(@Qualifier("supportSchema") DataDictionary schema) {
        return new InMemoryNamedEntityDataRepository(schema);
    }

    /**
     * Looks a mention up by name in the entity store, so "Acme" in a second chat is the entity
     * the first chat created rather than a new one.
     *
     * @param entities the store to search
     * @return the resolver
     */
    @Bean
    EntityResolver supportResolver(@Qualifier("supportEntities") InMemoryNamedEntityDataRepository entities) {
        return EscalatingEntityResolver.withoutVector(entities);
    }

    /**
     * Stores a chat's facts and new entities as one unit. When a step fails it puts both stores
     * back as they were.
     *
     * @param facts    the fact store
     * @param entities the entity store
     * @return the writer
     */
    @Bean
    PersistUnitWriter supportWriter(InMemoryPropositionRepository facts,
            @Qualifier("supportEntities") InMemoryNamedEntityDataRepository entities) {
        return PersistUnitWriter.inMemory(facts, entities);
    }

    /**
     * The pipeline that turns text into facts. It asks the model for propositions, checks each
     * mention against the sentence it is attached to, and resolves mentions to entities.
     * It has no reviser, so a fact that repeats or contradicts an earlier one is stored beside it.
     *
     * @param ai         supplies the model
     * @param diceEvents receives the pipeline's events
     * @return the pipeline
     */
    @Bean
    PropositionPipeline propositionPipeline(Ai ai, DiceEventListener diceEvents) {
        return PropositionPipeline
                .withExtractor(LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai))
                // A mention whose text is missing from its own sentence is a guess by the model.
                // Dropping it keeps the guess from becoming an entity.
                .withUngroundedMentions(UngroundedMentionHandling.DROP)
                .withEventListener(diceEvents);
    }

    /**
     * Republishes DICE's pipeline events as Spring application events, so anything in the
     * application can handle one with an {@code @EventListener} method.
     *
     * @param events the application's event publisher
     * @return the listener handed to the pipeline
     */
    @Bean
    DiceEventListener diceEvents(ApplicationEventPublisher events) {
        return event -> events.publishEvent(event);
    }
}
