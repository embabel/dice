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

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The DICE setup for the desk. Everything lives in memory and belongs to the one account the
 * desk serves.
 */
@Configuration(proxyBeanMethods = false)
class RevisionMemory {

    /**
     * The kinds of thing a message can mention. DICE shows it to the model, and mentions come
     * back tagged Customer or Plan.
     *
     * @return the desk's schema
     */
    @Bean
    DataDictionary revisionDictionary() {
        return DataDictionary.fromClasses("revision", Customer.class, Plan.class);
    }

    /**
     * The fact store. With no embedding service it has no similarity search, which is why the
     * reviser finds its candidates by shared entity instead.
     *
     * @return an empty in-memory store
     */
    @Bean
    InMemoryPropositionRepository propositionRepository() {
        return new InMemoryPropositionRepository();
    }

    /**
     * The entities the account's messages mention.
     *
     * @param schema the schema the store reads entity types from. The agent platform is a
     *               {@code DataDictionary} bean as well, so the qualifier names this one.
     * @return an empty in-memory store
     */
    @Bean
    NamedEntityDataRepository revisionEntities(@Qualifier("revisionDictionary") DataDictionary schema) {
        return new InMemoryNamedEntityDataRepository(schema);
    }

    /**
     * Looks mentions up by name in the account's entities, so "Acme" in a later message is the
     * entity an earlier one created. Facts about the same entity are what the reviser compares.
     *
     * @param entities the account's entities
     * @return the resolver
     */
    @Bean
    EntityResolver entityResolver(NamedEntityDataRepository entities) {
        return EscalatingEntityResolver.withoutVector(entities);
    }

    /**
     * Stores a message's facts and entities as one unit, and puts both stores back when a step
     * fails.
     *
     * @param facts    the fact store
     * @param entities the account's entities
     * @return the writer
     */
    @Bean
    PersistUnitWriter persistUnitWriter(InMemoryPropositionRepository facts, NamedEntityDataRepository entities) {
        return PersistUnitWriter.inMemory(facts, entities);
    }

    /**
     * The reviser that asks an Embabel decision what each new fact means.
     *
     * @param ai supplies the decision service
     * @return the reviser
     */
    @Bean
    DecisionReviser decisionReviser(Ai ai) {
        return new DecisionReviser(ai);
    }

    /**
     * The pipeline that turns a message into facts. It asks the model for propositions,
     * resolves their mentions, and hands each one to the decision reviser against the fact
     * store.
     *
     * @param ai      supplies the extraction model
     * @param reviser decides what each new fact does to the stored ones
     * @param facts   the store the reviser reads candidates from
     * @return the pipeline
     */
    @Bean
    PropositionPipeline propositionPipeline(Ai ai, DecisionReviser reviser, InMemoryPropositionRepository facts) {
        return PropositionPipeline
                .withExtractor(LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai))
                .withRevision(reviser, facts);
    }
}
