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
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import example.dice.starter.customers.AccountId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What extraction keeps for each account. An account's stores are created empty the first
 * time the account is seen.
 *
 * <p>Entity resolution searches the store it is given. Given one account's store, a name in
 * that account's ticket can only match an entity the same account created.
 *
 * <p>Everything here lives in memory for as long as the application runs, and every account
 * seen stays. An application with many accounts keeps entities in a persistent store and looks
 * them up by account, and builds the resolver and writer for each ticket from that.
 *
 * <p>The lock keeps one account's tickets apart within this application. It does nothing for a
 * second instance. When several instances take tickets, deliver each account's tickets to one
 * of them at a time, for example from a queue partitioned by account.
 */
@Component
class AccountStores {

    /**
     * The stores of one account.
     *
     * @param entities the entities the account's tickets mention
     * @param resolver looks mentions up by name in {@code entities}
     * @param writer   stores a ticket's facts and entities as one unit, in the fact store and
     *                 {@code entities}
     * @param lock     held while one ticket of the account is extracted and stored
     */
    record Stores(NamedEntityDataRepository entities, EntityResolver resolver, PersistUnitWriter writer,
            Lock lock) {
    }

    private final DataDictionary schema;
    private final InMemoryPropositionRepository facts;
    private final Map<AccountId, Stores> stores = new ConcurrentHashMap<>();

    /**
     * @param schema the schema each entity store reads entity types from
     * @param facts  the fact store every account's writer saves to
     */
    AccountStores(@Qualifier("supportSchema") DataDictionary schema, InMemoryPropositionRepository facts) {
        this.schema = schema;
        this.facts = facts;
    }

    /**
     * The fact store every account shares. Each fact carries its account as its context.
     *
     * @return the store the writers save to
     */
    PropositionRepository facts() {
        return facts;
    }

    /**
     * The stores of one account.
     *
     * @param account whose stores to return
     * @return the account's stores
     */
    Stores of(AccountId account) {
        return stores.computeIfAbsent(account, unused -> {
            var entities = newEntityStore();
            return new Stores(
                    entities,
                    EscalatingEntityResolver.withoutVector(entities),
                    PersistUnitWriter.inMemory(facts, entities),
                    new ReentrantLock());
        });
    }

    /**
     * Creates the entity store of an account that has none yet.
     *
     * @return an empty in-memory store
     */
    NamedEntityDataRepository newEntityStore() {
        return new InMemoryNamedEntityDataRepository(schema);
    }
}
