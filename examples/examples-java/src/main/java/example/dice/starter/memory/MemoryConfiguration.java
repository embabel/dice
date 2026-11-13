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

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.DiceEventListener;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.UngroundedMentionHandling;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import example.dice.starter.customers.Contact;
import example.dice.starter.customers.Customer;
import example.dice.starter.customers.Plan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The DICE setup for the support desk.
 */
@Configuration(proxyBeanMethods = false)
class MemoryConfiguration {

    /**
     * The schema lists the kinds of thing a ticket can mention. DICE shows it to the model,
     * and mentions come back tagged Customer, Contact or Plan.
     *
     * @return the support desk's schema
     */
    @Bean
    DataDictionary supportSchema() {
        return DataDictionary.fromClasses("support", Customer.class, Contact.class, Plan.class);
    }

    /**
     * The fact store. With no embedding service it has no similarity search.
     *
     * @return an empty in-memory store
     */
    @Bean
    PropositionRepository propositionRepository() {
        return new InMemoryPropositionRepository();
    }

    /**
     * The store for the entities that facts mention.
     *
     * @param supportSchema the schema the store reads entity types from
     * @return an empty in-memory store
     */
    @Bean
    NamedEntityDataRepository entityRepository(DataDictionary supportSchema) {
        return new InMemoryNamedEntityDataRepository(supportSchema);
    }

    /**
     * Decides whether a mention is an entity the store already has. It searches the entity
     * store by name, so "Acme" in a second ticket resolves to the entity the first ticket
     * created.
     *
     * <p>The search covers the whole entity store. An entity created from one account's ticket
     * also matches the same name in another account's ticket.
     *
     * @param entityRepository the store to search
     * @return the resolver
     */
    @Bean
    EntityResolver entityResolver(NamedEntityDataRepository entityRepository) {
        return EscalatingEntityResolver.withoutVector(entityRepository);
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
     * Republishes DICE's pipeline events as Spring application events, so any module can
     * handle one with an {@code @EventListener} method.
     *
     * @param events the application's event publisher
     * @return the listener handed to the pipeline
     */
    @Bean
    DiceEventListener diceEvents(ApplicationEventPublisher events) {
        return event -> events.publishEvent(event);
    }
}
