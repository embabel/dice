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

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.revision.LlmPropositionReviser;
import com.embabel.dice.proposition.revision.PropositionReviser;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.spi.AuthorityWeightedTrustScorer;
import com.embabel.dice.spi.TemporalConflictDetector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The DICE setup for the corrections desk. There is one account, so there is one fact store,
 * one entity store and one resolver, all in memory.
 *
 * <p>What sets this apart from the starter example is the reviser. Each new fact is compared
 * with the facts already stored, and the reviser decides whether it repeats one, backs one
 * up, contradicts one, or is new.
 */
@Configuration(proxyBeanMethods = false)
class CorrectionsMemory {

    /**
     * The kinds of thing a message can mention. DICE shows them to the model, and mentions
     * come back tagged Customer or Plan.
     *
     * @return the desk's schema
     */
    @Bean
    DataDictionary correctionsSchema() {
        return DataDictionary.fromClasses("corrections", Customer.class, Plan.class);
    }

    /**
     * The fact store. The reviser finds the stored facts a new one might repeat or contradict
     * by similarity search here, so the store needs an embedding model.
     *
     * <p>The embedder is built here rather than offered as a bean, so nothing else in the
     * application picks it up as its embedding model.
     *
     * <p>The bean has the in-memory type because {@code PersistUnitWriter.inMemory} takes that
     * type.
     *
     * @return an empty in-memory store that embeds with the story's fixed vectors
     */
    @Bean
    InMemoryPropositionRepository propositionRepository() {
        return new InMemoryPropositionRepository(new StoryEmbedder());
    }

    /**
     * The entities the account's messages mention, such as Acme and its plans.
     *
     * @param schema the schema the store reads entity types from
     * @return an empty in-memory store
     */
    @Bean
    InMemoryNamedEntityDataRepository entityStore(@Qualifier("correctionsSchema") DataDictionary schema) {
        return new InMemoryNamedEntityDataRepository(schema);
    }

    /**
     * Looks mentions up by name in the entity store, so every "Acme" resolves to the entity
     * the first message created.
     *
     * @param entityStore the account's entities
     * @return the resolver
     */
    @Bean
    EntityResolver entityResolver(InMemoryNamedEntityDataRepository entityStore) {
        return EscalatingEntityResolver.withoutVector(entityStore);
    }

    /**
     * Stores one message's facts and entities as a unit, and puts both stores back as they
     * were when a step fails.
     *
     * @param facts       the fact store
     * @param entityStore the entity store
     * @return the writer
     */
    @Bean
    PersistUnitWriter persistUnitWriter(InMemoryPropositionRepository facts,
            InMemoryNamedEntityDataRepository entityStore) {
        return PersistUnitWriter.inMemory(facts, entityStore);
    }

    /**
     * The reviser. It asks the model how a new fact relates to the similar ones already
     * stored.
     *
     * <p>Every fact it touches is scored for trust by where it came from: billing facts get
     * 0.9 and chat facts 0.6, through {@link SourceAuthority}. When the model calls two facts
     * contradictory, the temporal conflict detector checks whether the fact is one that
     * changes over time, such as a customer's plan, and the newer one simply replaced the
     * older. {@link PredicateStampingReviser} marks plan facts that way first.
     *
     * @param ai supplies the model
     * @return the reviser the pipeline uses
     */
    @Bean
    PropositionReviser propositionReviser(Ai ai) {
        var reviser = LlmPropositionReviser.withLlm(LlmOptions.withDefaultLlm()).withAi(ai)
                .withTrustScorer(new AuthorityWeightedTrustScorer())
                .withAuthorityResolver(new SourceAuthority())
                .withConflictDetector(new TemporalConflictDetector());
        return new PredicateStampingReviser(reviser);
    }

    /**
     * The pipeline that turns a message into facts. It asks the model for propositions,
     * resolves their mentions to entities, then revises each against the fact store.
     *
     * @param ai      supplies the model
     * @param reviser compares each new fact with the stored ones
     * @param facts   the store the reviser searches
     * @return the pipeline
     */
    @Bean
    PropositionPipeline propositionPipeline(Ai ai, PropositionReviser reviser, InMemoryPropositionRepository facts) {
        return PropositionPipeline
                .withExtractor(LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai))
                .withRevision(reviser, facts);
    }
}
